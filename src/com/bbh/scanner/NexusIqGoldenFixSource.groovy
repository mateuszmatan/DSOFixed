package com.bbh.scanner

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.GoldenFixSource
import com.bbh.utils.BuildUtils
import com.bbh.utils.RestClient
import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

class NexusIqGoldenFixSource implements GoldenFixSource {

    static final List<String> DEFAULT_REMEDIATION_PREFERENCE = [
            'recommended-non-breaking-with-dependencies',
            'next-no-violations-with-dependencies',
            'next-no-violations',
            'recommended-non-breaking',
            'next-non-failing-with-dependencies',
            'next-non-failing'
    ]

    private final def        script
    private final RestClient rest

    NexusIqGoldenFixSource(def script) {
        this.script = script
        this.rest   = new RestClient(script)
    }

    List<Map> fetchGoldenFixes(Map scanRef, Map cfg) {
        String server      = ((scanRef.serverUrl ?: '') as String).replaceAll('/+$', '')
        String application = (scanRef.application ?: '') as String
        String scanId      = (scanRef.scanId ?: '') as String
        if (!server || !application || !scanId) {
            script.echo "[GOLDENFIX] Nexus IQ serverUrl, application or scan id missing for '${application}' - cannot fetch the GoldenFix list"
            return []
        }
        int minThreat          = cfg.minThreatLevel != null ? cfg.minThreatLevel.toString().toInteger() : 2
        boolean onlyDirect     = BuildUtils.booleanValue(cfg.onlyDirectDependencies, true)
        List ecosystems        = (cfg.ecosystems ?: GoldenFix.SUPPORTED_ECOSYSTEMS) as List
        List preference        = (cfg.remediationPreference ?: DEFAULT_REMEDIATION_PREFERENCE) as List
        String stageId         = (scanRef.stage ?: 'build') as String
        String credentialsId   = (scanRef.credentialsId ?: 'nexusiqP') as String

        List<Map> fixes = []
        script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'NIQ_GF_USER', passwordVariable: 'NIQ_GF_PASS')]) {
            Map auth = [type: 'basic', userVar: 'NIQ_GF_USER', passVar: 'NIQ_GF_PASS']
            String appEnc = RestClient.urlEncode(application)

            def apps = rest.getJson("${server}/api/v2/applications?publicId=${appEnc}", auth, "Nexus IQ: resolve application ${application}")
            String internalId = firstApplicationId(apps)
            if (!internalId) script.error("[GOLDENFIX] Nexus IQ application '${application}' not found")

            def report = rest.getJson("${server}/api/v2/applications/${appEnc}/reports/${RestClient.urlEncode(scanId)}/policy", auth, "Nexus IQ: policy report ${scanId}")
            List<Map> candidates = selectCandidates(report, minThreat, onlyDirect, ecosystems)
            script.echo "[GOLDENFIX] ${application}: ${candidates.size()} violating ${onlyDirect ? 'direct ' : ''}component(s) eligible for GoldenFix (threat level >= ${minThreat})"

            String remediationUrl = "${server}/api/v2/components/remediation/application/${RestClient.urlEncode(internalId)}?stageId=${RestClient.urlEncode(stageId)}"
            for (int i = 0; i < candidates.size(); i++) {
                Map c = candidates[i]
                try {
                    def payload = c.componentIdentifier ? [componentIdentifier: c.componentIdentifier] : [packageUrl: c.packageUrl]
                    def remediation = rest.postJson(remediationUrl, payload, auth, "Nexus IQ: remediation for ${c.displayName}")
                    Map pick = pickVersion(remediation, c.version as String, preference)
                    if (pick) {
                        fixes << GoldenFix.create(c.ecosystem as String, c.group as String, c.name as String, c.version as String,
                                pick.version as String, pick.type as String, c.packageUrl as String, c.threatLevel as int,
                                c.direct as Boolean, application)
                        script.echo "[GOLDENFIX]   ${c.displayName} ${c.version} -> ${pick.version} (${pick.type})"
                    } else {
                        script.echo "[GOLDENFIX]   ${c.displayName} ${c.version}: Nexus IQ offers no remediation version"
                    }
                } catch (Exception e) {
                    if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                    script.echo "[GOLDENFIX]   ${c.displayName} ${c.version}: remediation lookup failed - ${e.message}"
                }
            }
        }
        return fixes
    }

    @NonCPS
    static String firstApplicationId(def json) {
        def apps = json?.applications
        return (apps instanceof List && !apps.isEmpty()) ? (apps[0]?.id as String) : null
    }

    @NonCPS
    static List<Map> selectCandidates(def report, int minThreat, boolean onlyDirect, List ecosystems) {
        List<Map> out = []
        Set<String> seen = new HashSet<String>()
        List components = (report?.components ?: []) as List
        for (def comp : components) {
            def identifier = comp?.componentIdentifier
            String format  = identifier?.format as String
            if (!format || !ecosystems.contains(format)) continue

            List active = ((comp.violations ?: []) as List).findAll { !(it?.waived) }
            if (active.isEmpty()) continue
            int threat = 0
            for (def v : active) threat = Math.max(threat, (v?.policyThreatLevel ?: 0) as int)
            if (threat < minThreat) continue

            Boolean direct = comp?.dependencyData != null ? (comp.dependencyData.directDependency as Boolean) : null
            if (onlyDirect && direct == Boolean.FALSE) continue

            Map coordinates = (identifier.coordinates ?: [:]) as Map
            String group = ''
            String name  = ''
            if (format == GoldenFix.MAVEN) {
                group = coordinates.groupId as String
                name  = coordinates.artifactId as String
            } else if (format == GoldenFix.NPM) {
                name = coordinates.packageId as String
            } else if (format == GoldenFix.PYPI) {
                name = coordinates.name as String
            }
            String version = coordinates.version as String
            if (!name || !version) continue
            if (!seen.add(GoldenFix.key(format, group, name) + '@' + version)) continue

            out << [
                    ecosystem          : format,
                    group              : group ?: '',
                    name               : name,
                    version            : version,
                    displayName        : GoldenFix.displayName(format, group, name),
                    componentIdentifier: identifier,
                    packageUrl         : (comp.packageUrl ?: '') as String,
                    threatLevel        : threat,
                    direct             : direct
            ]
        }
        return out
    }

    @NonCPS
    static Map pickVersion(def response, String currentVersion, List preference) {
        List changes = (response?.remediation?.versionChanges ?: []) as List
        for (def type : preference) {
            for (def change : changes) {
                if (change?.type != type) continue
                def component = change?.data?.component
                String version = component?.componentIdentifier?.coordinates?.version as String
                if (!version) version = versionFromPurl(component?.packageUrl as String)
                if (version && VersionUtils.isUpgrade(currentVersion, version)) {
                    return [type: type as String, version: version]
                }
            }
        }
        return null
    }

    @NonCPS
    static String versionFromPurl(String purl) {
        if (!purl) return null
        def m = (purl =~ /@([^?#]+)/)
        return m.find() ? URLDecoder.decode(m.group(1), 'UTF-8') : null
    }
}
