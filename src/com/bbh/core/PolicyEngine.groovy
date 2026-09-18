package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class PolicyEngine implements Serializable {

    static final String BLOCK_NOTE = 'The Nexus release and the QC deployment stay blocked until this is fixed.'

    static final Map SCANNER_LABELS = [
            sast: 'SAST (AppScan)',
            sca : 'SCA (SonarQube)',
            niq : 'Dependencies (Nexus IQ)',
            dast: 'DAST (AppScan)'
    ]

    static final Map STAGE_NAMES = [
            sast: 'SAST - Static Application Security Tests - HCL AppScan',
            sca : 'SCA (SonarQube)',
            niq : 'Dependencies scan (Nexus IQ)',
            dast: 'DAST - Dynamic Application Security Tests - HCL AppScan'
    ]

    private final def           script
    private final PipelineState state
    private final OsHelper      os

    PolicyEngine(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void enforceScanner(String scannerKey) {
        if (!scannerKey) script.error "enforceScanner: scannerKey required (sast|sca|dast)"
        if (scannerKey == 'sca') {
            enforceQualityGate()
            return
        }
        registerFindings(scannerKey, state.vulnCounts[scannerKey] as Map, reportFileName(scannerKey))
    }

    void registerFindings(String scannerKey, Map counts, String reportFile) {
        String stageName = scannerStageName(scannerKey)
        Map limits = (state.policyLimits[scannerKey] ?: [:]) as Map
        List violations = countViolations(counts ?: [:], limits)
        if (violations) {
            state.policyStatus[scannerKey] = 'WARN'
            state.recordScan(scannerKey, 'WARN', reportFile)
            warn(stageName, "${SCANNER_LABELS[scannerKey]} policy not met: ${violations.join(', ')}. ${BLOCK_NOTE}")
            return
        }
        state.policyStatus[scannerKey] = 'PASS'
        state.recordScan(scannerKey, 'PASS', reportFile)
        script.echo "[POLICY] ${SCANNER_LABELS[scannerKey]}: policy satisfied."
    }

    void missingCoverage(String reason) {
        int required = (state.coverage.minRequired ?: 60) as int
        state.coverage.enabled = false
        state.policyStatus['coverage'] = 'WARN'
        warn('Unit tests', "${reason} - the required ${required}% line coverage cannot be verified. ${BLOCK_NOTE}")
    }

    void checkCoverage() {
        String stageName = 'Unit tests'
        int required = (state.coverage.minRequired ?: 60) as int
        if (!state.coverage.enabled) {
            missingCoverage('No coverage report found')
            return
        }
        double line = (state.coverage.line ?: 0.0) as double
        if (line < (required as double)) {
            state.policyStatus['coverage'] = 'WARN'
            warn(stageName, "Line coverage ${line}% is below the required ${required}%. ${BLOCK_NOTE}")
            return
        }
        state.policyStatus['coverage'] = 'PASS'
        script.echo "[COVERAGE] Line coverage ${line}% meets the required ${required}%."
    }

    void warn(String stageName, String message) {
        state.stageWarn(stageName)
        state.stageError(stageName, message)
        script.unstable("[POLICY] ${message}")
    }

    @NonCPS
    List countViolations(Map counts, Map limits) {
        List violations = []
        [['critical', 'maxCritical', 'critical'], ['high', 'maxHigh', 'high'], ['medium', 'maxMedium', 'medium']].each { entry ->
            int value = (counts[entry[0]] ?: 0) as int
            int limit = (limits[entry[1]] ?: 0) as int
            if (value > limit) violations << "${entry[2]} ${value} of max ${limit}".toString()
        }
        return violations
    }

    String scannerStageName(String key) {
        return STAGE_NAMES[key]
    }

    String reportPath(String key) {
        String scanName = script.env.APPSCAN_SCAN_NAME ?: 'report'
        Map paths = [
                sast: "${script.env.WORKSPACE}/appscan-report-${scanName}.html",
                sca : "${script.env.WORKSPACE}/appscan-sca-report-${scanName}.html",
                dast: "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html"
        ]
        return paths[key]
    }

    String reportFileName(String key) {
        String scanName = (script.env?.APPSCAN_SCAN_NAME ?: '').trim()
        if (!scanName) return reportPath(key).tokenize('/').last()
        return key == 'dast' ? "appscan-dast-report-${scanName}.html" : "appscan-report-${scanName}.html"
    }

    private void enforceQualityGate() {
        String stageName = scannerStageName('sca')
        String reportFile = reportFileName('sca')
        def required = state.cfgDefaults.tools?.sonar?.qualityGate?.waitForQualityGate
        required = (required == null) ? true : required
        if (!required) {
            state.recordScan('sonar', 'PASS', reportFile)
            state.recordSonar('PASS')
            script.echo "[POLICY] SonarQube quality gate not required."
            return
        }
        def timeoutMinutes = state.cfgDefaults.tools?.sonar?.qualityGate?.timeoutMinutes ?: 5
        String status = 'UNKNOWN'
        try {
            script.timeout(time: timeoutMinutes, unit: 'MINUTES') {
                status = script.waitForQualityGate()?.status ?: 'UNKNOWN'
            }
        } catch (Exception e) {
            status = 'TIMEOUT'
            script.echo "[POLICY] SonarQube quality gate could not be read: ${e.message}"
        }
        script.echo "[POLICY] SonarQube quality gate status: ${status}"
        if (status != 'OK') {
            state.sonarResults['status'] = 'WARN'
            state.policyStatus['sonar'] = 'WARN'
            state.recordSonar('WARN')
            state.recordScan('sonar', 'WARN', reportFile)
            warn(stageName, "SonarQube quality gate is ${status}. ${BLOCK_NOTE}")
            return
        }
        state.sonarResults['status'] = 'PASS'
        state.policyStatus['sonar'] = 'PASS'
        state.recordSonar('PASS')
        state.recordScan('sonar', 'PASS', reportFile)
    }
}
