package com.bbh.config

import com.bbh.core.PipelineState

class ConfigLoader implements Serializable {

    private static final List<String> OVERRIDABLE = ['sast', 'sca', 'niq', 'coverage']

    private final def           script
    private final PipelineState state

    ConfigLoader(def script, PipelineState state) {
        this.script = script
        this.state  = state
    }

    void initialize() {
        state.commitTime = System.currentTimeMillis()

        String defaultsText = script.libraryResource('defaults.yaml')
        def defaultsYaml    = script.readYaml(text: defaultsText)
        state.cfgDefaults   = defaultsYaml?.defaults ?: [:]

        def projectNames = resolveProjectNames()
        if (!projectNames) {
            script.echo "[INIT] No PROJECT_NAMES defined - using defaults."
            state.cfg = [:]
        } else {
            if (!script.fileExists('config.yaml')) {
                script.error "[INIT] config.yaml not found in workspace. Projects must provide config.yaml with a 'projects:' section."
            }
            def raw = script.readYaml(file: 'config.yaml')
            if (!raw?.projects) {
                script.error "[INIT] config.yaml must contain a 'projects:' section."
            }
            for (int i = 0; i < projectNames.size(); i++) {
                def pName = projectNames[i]
                if (!raw.projects.containsKey(pName)) {
                    script.error "[INIT] Project '${pName}' not found in config.yaml. Available: ${raw.projects.keySet().join(', ')}"
                }
                state.projectsAllCfg[pName] = deepMerge(state.cfgDefaults, raw.projects[pName] as Map)
            }
            def primaryName = projectNames[0]
            state.cfg = state.projectsAllCfg[primaryName]
            state.currentProjectName = primaryName
            script.env.CURRENT_PROJECT_NAME = primaryName
            script.echo "[INIT] Projects: ${projectNames.join(', ')} | primary: ${primaryName} | buildTool: ${state.cfg.buildTool ?: 'gradle'}"
            def scanName = buildScanName(state.cfg.tools?.sonar?.projectName ?: primaryName)
            script.env.APPSCAN_SCAN_NAME = scanName
            script.echo "[INIT] APPSCAN_SCAN_NAME=${scanName}"
        }

        def keyId = state.cfg.asoc?.keyId?.trim()
        if (!keyId) script.error "[INIT] asoc.keyId must be set in config.yaml"
        script.env.APPSCAN_KEY_ID = keyId

        applyPolicyLimits()
        script.echo "[INIT] OS: ${script.env.OS_TYPE ?: 'linux'}"
    }

    List<String> resolveProjectNames() {
        def raw = (script.env.PROJECT_NAMES ?: script.env.PROJECT_NAME ?: '').trim()
        if (raw) return raw.split(',').collect { it.trim() }.findAll { it }
        if (script.fileExists('config.yaml')) {
            try {
                def cfg = script.readYaml(file: 'config.yaml')
                def keys = cfg?.projects?.keySet()?.toList() ?: []
                if (keys) {
                    script.echo "[INIT] PROJECT_NAMES not set - auto-detected from config.yaml: ${keys.join(', ')}"
                    return keys
                }
            } catch (ignored) {}
        }
        return []
    }

    void switchProject(String projectName) {
        if (!state.projectsAllCfg.containsKey(projectName)) {
            script.error "[SWITCH] Project '${projectName}' not loaded."
        }
        state.cfg = state.projectsAllCfg[projectName]
        state.currentProjectName = projectName
        script.env.CURRENT_PROJECT_NAME = projectName
        script.env.APPSCAN_SCAN_NAME = buildScanName(state.cfg.tools?.sonar?.projectName ?: projectName)
        applyPolicyLimits()
    }

    private String buildScanName(String base) {
        return base.replaceAll(/[^a-zA-Z0-9_-]/, '-').replaceAll(/-+/, '-').toLowerCase().trim()
    }

    void applyPolicyLimits() {
        state.hardLimits = [
                sast: limits(state.cfgDefaults.sast),
                sca : limits(state.cfgDefaults.sca),
                dast: limits(state.cfgDefaults.dast),
                niq : limits(state.cfgDefaults.tools?.nexusIq)
        ]
        state.policyLimits = [
                sast: limits(state.cfg.sast),
                sca : limits(state.cfg.sca),
                dast: state.hardLimits.dast,
                niq : limits(state.cfg.tools?.nexusIq)
        ]
        state.projectsPolicyLimits[state.currentProjectName] = state.policyLimits

        int hardCoverage = (state.cfgDefaults.coverage?.minLine ?: 60) as int
        state.coverage.minRequiredHard = hardCoverage
        state.coverage.minRequired = (state.cfg.coverage?.minLine ?: hardCoverage) as int

        logPolicyLimits()
    }

    private void logPolicyLimits() {
        script.echo "[POLICY] Project '${state.currentProjectName}' thresholds (pipeline fails above them):"
        ['sast', 'sca', 'niq'].each { key ->
            def effective = state.policyLimits[key] ?: [:]
            def hard = state.hardLimits[key] ?: [:]
            String overridden = effective == hard ? '' : "  (library policy: C<=${hard.maxCritical} H<=${hard.maxHigh} M<=${hard.maxMedium})"
            script.echo "[POLICY]   ${key.toUpperCase()}: C<=${effective.maxCritical} H<=${effective.maxHigh} M<=${effective.maxMedium}${overridden}"
        }
        def dast = state.hardLimits.dast ?: [:]
        script.echo "[POLICY]   DAST: C<=${dast.maxCritical} H<=${dast.maxHigh} M<=${dast.maxMedium}  (not overridable)"
        String coverageNote = state.coverage.minRequired == state.coverage.minRequiredHard ? '' : "  (library policy: ${state.coverage.minRequiredHard}%)"
        script.echo "[POLICY]   Coverage: min ${state.coverage.minRequired}%${coverageNote}"
        if (hasOverrides()) {
            script.echo "[POLICY] Project thresholds are relaxed - the artifact is not released to Nexus and QC deployment is blocked while the library policy is exceeded"
        }
    }

    boolean hasOverrides() {
        for (String key : ['sast', 'sca', 'niq']) {
            if (state.policyLimits[key] != state.hardLimits[key]) return true
        }
        return state.coverage.minRequired != state.coverage.minRequiredHard
    }

    private Map limits(def cfg) {
        return [
                maxCritical: (cfg?.maxCritical ?: 0) as int,
                maxHigh    : (cfg?.maxHigh ?: 0) as int,
                maxMedium  : (cfg?.maxMedium ?: 0) as int
        ]
    }

    private Map deepMerge(Map base, Map override) {
        def result = [:]
        result.putAll(base)
        override?.each { k, v ->
            result[k] = (v instanceof Map && result[k] instanceof Map)
                    ? deepMerge(result[k] as Map, v as Map) : v
        }
        return result
    }
}
