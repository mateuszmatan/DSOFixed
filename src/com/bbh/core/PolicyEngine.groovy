package com.bbh.core

class PolicyEngine implements Serializable {
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
        String stageName = scannerStageName(scannerKey)
        String scanName = (script.env?.APPSCAN_SCAN_NAME ?: '').trim()
        String destFile = scanName
                ? (scannerKey == 'dast' ? "appscan-dast-report-${scanName}.html" : "appscan-report-${scanName}.html")
                : reportPath(scannerKey).tokenize('/').last()

        if (stageName == 'SCA (SonarQube)') {
            enforceQualityGate(scannerKey, stageName, destFile)
            return
        }
        enforceCounts(scannerKey, stageName, destFile)
    }

    private void enforceQualityGate(String scannerKey, String stageName, String destFile) {
        def required = state.cfgDefaults.tools?.sonar?.qualityGate?.waitForQualityGate
        required = (required == null) ? true : required
        if (!required) {
            state.recordScan(scannerKey, 'PASS', destFile)
            if (stageName && !state.stageResults[stageName]) state.stageResults[stageName] = 'PASS'
            script.echo "QualityGate not required"
            return
        }
        def timeoutMinutes = state.cfgDefaults.tools?.sonar?.qualityGate?.timeoutMinutes ?: 5
        script.timeout(time: timeoutMinutes, unit: 'MINUTES') {
            try {
                def qualityGate = script.waitForQualityGate()
                script.echo "Pipeline Quality Gate status: ${qualityGate.status}"
                if (qualityGate.status != 'OK') {
                    state.sonarResults['status'] = 'FAIL'
                    state.policyStatus['sonar'] = 'FAIL'
                    state.recordSonar('FAIL')
                    state.stageResults[stageName] = 'FAIL'
                    state.recordScan('sonar', 'FAIL', destFile)
                    state.stageErrors[stageName] = "Security policies not fulfilled! Quality gate failed"
                    script.error("Quality gate failed")
                }
                state.recordScan(scannerKey, 'PASS', destFile)
                if (stageName && !state.stageResults[stageName]) state.stageResults[stageName] = 'PASS'
                script.echo "[POLICY] ${scannerKey.toUpperCase()}: satisfied."
            } catch (e) {
                script.error("Couldn't get result status! Timeout passed!")
            }
        }
    }

    private void enforceCounts(String scannerKey, String stageName, String destFile) {
        def counts = state.vulnCounts[scannerKey] ?: [critical: 0, high: 0, medium: 0, low: 0]
        def limits = state.policyLimits[scannerKey] ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]
        List violations = countViolations(counts, limits)

        if (violations) {
            state.recordScan(scannerKey, 'FAIL', destFile)
            if (stageName) {
                state.stageResults[stageName] = 'FAIL'
                state.stageErrors[stageName] = "Security policies not fulfilled! " + violations.join(', ')
            }
            script.currentBuild.result = 'FAILURE'
            script.error("Security policies not fulfilled! ${scannerKey.toUpperCase()}: " + violations.join(', '))
        }

        state.recordScan(scannerKey, 'PASS', destFile)
        if (stageName && !state.stageResults[stageName]) state.stageResults[stageName] = 'PASS'
        script.echo "[POLICY] ${scannerKey.toUpperCase()}: satisfied."
    }

    List countViolations(Map counts, Map limits) {
        List violations = []
        def check = { String severity, String limitKey, String label ->
            int value = (counts[severity] ?: 0) as int
            int limit = (limits[limitKey] ?: 0) as int
            if (value > limit) violations << "${label} ${value}/${limit}".toString()
        }
        check('critical', 'maxCritical', 'Critical')
        check('high', 'maxHigh', 'High')
        check('medium', 'maxMedium', 'Medium')
        return violations
    }

    void checkCoverage(int minLine) {
        script.echo "[COVERAGE] Checking coverage. Minimum: ${minLine}%"
        if ((state.coverage.line as double) < (minLine as double)) {
            String reason = "Line coverage ${state.coverage.line}% below required ${minLine}%"
            state.policyStatus['coverage'] = 'FAIL'
            state.stageError('Unit tests', reason)
            script.error("[COVERAGE] POLICY VIOLATION: ${reason}")
        }
        state.policyStatus['coverage'] = 'PASS'
    }

    String scannerStageName(String key) {
        def map = [
            sast: 'SAST - Static Application Security Tests - HCL AppScan',
            sca:  'SCA (SonarQube)',
            dast: 'DAST - Dynamic Application Security Tests - HCL AppScan'
        ]
        return map[key]
    }

    String reportPath(String key) {
        def scanName = script.env.APPSCAN_SCAN_NAME ?: 'report'
        def map = [
            sast: "${script.env.WORKSPACE}/appscan-report-${scanName}.html",
            sca:  "${script.env.WORKSPACE}/appscan-sca-report-${scanName}.html",
            dast: "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html"
        ]
        return map[key]
    }
}
