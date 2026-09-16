package com.bbh.core

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

class ReleaseGate implements Serializable {

    static final Map SCANNER_LABELS = [
            sast: 'SAST (AppScan)',
            sca : 'SCA (SonarQube)',
            niq : 'Dependencies (Nexus IQ)',
            dast: 'DAST (AppScan)'
    ]

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
        Map coverage = BuildUtils.booleanValue(gateCfg.requireCoverage, true) ? new HashMap(state.coverage ?: [:]) : [:]
        return decide(state.projectsVulnCounts ?: [:], state.hardLimits ?: [:], scanners, coverage, upstreamViolations())
    }

    void publish() {
        Map decision = evaluate()
        script.writeFile(file: stateFileName(), text: JsonOutput.toJson([
                job       : script.env.JOB_NAME ?: '',
                build     : script.env.BUILD_NUMBER ?: '',
                allowed   : decision.allowed,
                violations: decision.violations
        ]))
        script.echo "[RELEASE-GATE] State written to ${stateFileName()} (allowed=${decision.allowed})"
    }

    List<String> upstreamViolations() {
        String file = stateFileName()
        if (!script.fileExists(file)) return []
        try {
            return parseViolations(script.readFile(file))
        } catch (Exception e) {
            script.echo "[RELEASE-GATE] Could not read ${file}: ${e.message}"
            return []
        }
    }

    private String stateFileName() {
        return (state.cfgDefaults.releaseGate?.stateFile ?: 'release-gate.json') as String
    }

    @NonCPS
    static List<String> parseViolations(String json) {
        def parsed = new JsonSlurperClassic().parseText(json ?: '{}')
        List violations = (parsed?.violations ?: []) as List
        return violations.collect { "${it} (previous pipeline)".toString() }
    }

    @NonCPS
    static Map decide(Map projectsVulnCounts, Map hardLimits, List scanners, Map coverage, List upstreamViolations) {
        List violations = new ArrayList(upstreamViolations ?: [])

        for (def entry : projectsVulnCounts.entrySet()) {
            String project = entry.key as String
            Map byScanner = (entry.value ?: [:]) as Map
            for (def scanner : scanners) {
                Map counts = byScanner.get(scanner) as Map
                if (counts == null) continue
                Map limits = (hardLimits.get(scanner) ?: [:]) as Map
                String label = (SCANNER_LABELS[scanner] ?: scanner) as String
                violations.addAll(severityViolations(project, label, counts, limits))
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
        String reason = allowed ? '' : ("Nexus release and QC deployment blocked - the library security policy is not met: " + violations.join(' | '))
        String log = allowed
                ? '[RELEASE-GATE] Library security policy met - Nexus release and QC deployment allowed'
                : "[RELEASE-GATE] ${reason}".toString()
        return [allowed: allowed, violations: violations, reason: reason, log: log]
    }

    @NonCPS
    private static List<String> severityViolations(String project, String label, Map counts, Map limits) {
        List<String> out = []
        out.addAll(severityViolation(project, label, 'critical', counts, limits, 'maxCritical'))
        out.addAll(severityViolation(project, label, 'high', counts, limits, 'maxHigh'))
        out.addAll(severityViolation(project, label, 'medium', counts, limits, 'maxMedium'))
        return out
    }

    @NonCPS
    private static List<String> severityViolation(String project, String label, String severity, Map counts, Map limits, String limitKey) {
        int value = (counts.get(severity) ?: 0) as int
        int limit = (limits.get(limitKey) ?: 0) as int
        if (value <= limit) return []
        return ["${project} ${label} ${severity} ${value} > ${limit}".toString()]
    }
}
