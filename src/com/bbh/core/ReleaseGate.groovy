package com.bbh.core

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class ReleaseGate implements Serializable {

    static final Map SCANNER_LABELS = [
            sast: 'SAST (AppScan)',
            sca : 'SCA (SonarQube)',
            niq : 'Dependencies (Nexus IQ)',
            dast: 'DAST (AppScan)'
    ]

    static final List BLOCKING_STAGE_STATUSES = ['FAIL', 'BLOCKED']

    private final def           script
    private final PipelineState state

    ReleaseGate(def script, PipelineState state) {
        this.script = script
        this.state  = state
    }

    boolean allowed(String stageName) {
        Map decision = evaluate()
        script.echo decision.log as String
        if (decision.allowed) return true
        state.stageResults[stageName] = 'BLOCKED'
        state.stageError(stageName, decision.reason as String)
        script.currentBuild.result = 'UNSTABLE'
        return false
    }

    Map evaluate() {
        Map gateCfg = (state.cfgDefaults.releaseGate ?: [:]) as Map
        List scanners = (gateCfg.scanners ?: ['sast', 'sca', 'niq', 'dast']) as List
        Map coverage = BuildUtils.booleanValue(gateCfg.requireCoverage, true) ? ([:] + (state.coverage ?: [:])) : [:]
        List carried = []
        carried.addAll(upstreamViolations())
        carried.addAll(failedStageViolations())
        return decide(state.projectsVulnCounts ?: [:], state.hardLimits ?: [:], scanners, coverage, carried)
    }

    void publish() {
        Map decision = evaluate()
        script.writeJSON(file: stateFileName(), json: [
                job       : (script.env.JOB_NAME ?: '') as String,
                build     : (script.env.BUILD_NUMBER ?: '') as String,
                allowed   : decision.allowed,
                violations: decision.violations
        ])
        script.echo "[RELEASE-GATE] State written to ${stateFileName()} (allowed=${decision.allowed})"
    }

    List upstreamViolations() {
        String file = stateFileName()
        if (!script.fileExists(file)) return []
        try {
            def parsed = script.readJSON(file: file)
            List out = []
            (parsed?.violations ?: []).each { out << "${it} (previous pipeline)".toString() }
            return out
        } catch (Exception e) {
            script.echo "[RELEASE-GATE] Could not read ${file}: ${e.message}"
            return []
        }
    }

    List failedStageViolations() {
        List out = []
        (state.stageResults ?: [:]).each { name, status ->
            if (BLOCKING_STAGE_STATUSES.contains(status as String)) {
                out << "stage '${name}' did not pass (${status})".toString()
            }
        }
        return out
    }

    private String stateFileName() {
        return (state.cfgDefaults.releaseGate?.stateFile ?: 'release-gate.json') as String
    }

    @NonCPS
    static Map decide(Map projectsVulnCounts, Map hardLimits, List scanners, Map coverage, List carriedViolations) {
        List violations = []
        violations.addAll(carriedViolations ?: [])

        (projectsVulnCounts ?: [:]).each { project, byScanner ->
            (scanners ?: []).each { scanner ->
                Map counts = ((byScanner ?: [:]) as Map).get(scanner) as Map
                if (counts != null) {
                    Map limits = ((hardLimits ?: [:]).get(scanner) ?: [:]) as Map
                    String label = (SCANNER_LABELS[scanner] ?: scanner) as String
                    violations.addAll(severityViolations(project as String, label, counts, limits))
                }
            }
        }

        if (coverage && coverage.get('enabled')) {
            double line = (coverage.get('line') ?: 0.0) as double
            double required = (coverage.get('minRequiredHard') ?: 60) as double
            if (line < required) {
                violations << "line coverage ${line}% below the required ${required as int}%".toString()
            }
        }

        boolean allowed = violations.isEmpty()
        String reason = allowed ? '' : ('Nexus release and QC deployment blocked - the library security policy is not met: ' + violations.join(' | '))
        String log = allowed
                ? '[RELEASE-GATE] Library security policy met - Nexus release and QC deployment allowed'
                : "[RELEASE-GATE] ${reason}".toString()
        return [allowed: allowed, violations: violations, reason: reason, log: log]
    }

    @NonCPS
    private static List severityViolations(String project, String label, Map counts, Map limits) {
        List out = []
        out.addAll(severityViolation(project, label, 'critical', counts, limits, 'maxCritical'))
        out.addAll(severityViolation(project, label, 'high', counts, limits, 'maxHigh'))
        out.addAll(severityViolation(project, label, 'medium', counts, limits, 'maxMedium'))
        return out
    }

    @NonCPS
    private static List severityViolation(String project, String label, String severity, Map counts, Map limits, String limitKey) {
        int value = (counts.get(severity) ?: 0) as int
        int limit = (limits.get(limitKey) ?: 0) as int
        if (value <= limit) return []
        return ["${project} ${label} ${severity} ${value} > ${limit}".toString()]
    }
}
