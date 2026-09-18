package com.bbh.core

class StageLogger implements Serializable {

    private static final String BAR     = '-' * 60
    private static final String SECTION = '-' * 65

    private final def           script
    private final PipelineState state

    StageLogger(def script, PipelineState state) {
        this.script = script
        this.state  = state
    }

    void finish(String stageName) {
        state.stagePass(stageName)
        logStageResult(stageName, state.stageStatus(stageName))
    }

    void fail(String stageName) {
        state.stageFail(stageName)
        logStageResult(stageName, 'FAIL')
    }

    void logStageResult(String stageName, String status) {
        script.echo BAR
        script.echo "[STAGE]  ${stageName}"
        script.echo "[RESULT] ${status}"
        if (stageName.contains('SAST')) {
            logScanner('sast', 'SAST')
        } else if (stageName.contains('SCA')) {
            logSonar()
        } else if (stageName.contains('DAST')) {
            logScanner('dast', 'DAST')
        } else if (stageName == 'Unit tests' && state.coverage.enabled) {
            logCoverage()
        }
        script.echo BAR
    }

    void section(String text)      { script.echo "${SECTION}\n--- ${text} ---\n${SECTION}" }
    void startSection(String text) { script.echo "${SECTION}\n--- Starting: ${text} ---\n${SECTION}" }
    void endSection(String text)   { script.echo "${SECTION}\n--- End of: ${text} ---\n${SECTION}" }

    private void logScanner(String key, String label) {
        def counts = state.vulnCounts[key] ?: [critical: 0, high: 0, medium: 0, low: 0]
        def limits = state.policyLimits[key] ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]
        script.echo "[${label}]   Found:  Critical=${counts.critical}  High=${counts.high}  Medium=${counts.medium}  Low=${counts.low}"
        script.echo "[${label}]   Limits: Critical<=${limits.maxCritical}  High<=${limits.maxHigh}  Medium<=${limits.maxMedium}"
    }

    private void logSonar() {
        def counts = state.vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]
        def limits = state.policyLimits.sca ?: [maxCritical: 0, maxHigh: 0]
        state.sonarResults['critical'] = counts.critical as int
        state.sonarResults['high']     = counts.high as int
        state.sonarResults['medium']   = counts.medium as int
        state.sonarResults['low']      = counts.low as int
        script.echo "[SCA]    Found:  Critical=${counts.critical}  High=${counts.high}  Medium=${counts.medium}  Low=${counts.low}"
        script.echo "[SCA]    Limits: Critical<=${limits.maxCritical}  High<=${limits.maxHigh}"
    }

    private void logCoverage() {
        boolean ok = (state.coverage.line as double) >= (state.coverage.minRequired as double)
        script.echo "[COV]    Line: ${state.coverage.line}%  required: ${state.coverage.minRequired}%  ${ok ? 'OK' : 'BELOW THRESHOLD'}"
        script.echo "[COV]    Covered: ${state.coverage.covered}  Missed: ${state.coverage.missed}  Total: ${state.coverage.total}"
    }
}
