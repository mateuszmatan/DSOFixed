package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class MavenPomUpdater implements ManifestUpdater {

    private static final Pattern DEPENDENCY = Pattern.compile('(?s)<dependency\\s*>(.*?)</dependency\\s*>')
    private static final Pattern VERSION    = Pattern.compile('<version>\\s*([^<]*?)\\s*</version>')
    private static final Pattern PROPERTIES = Pattern.compile('(?s)<properties\\s*>(.*?)</properties\\s*>')
    private static final Pattern PROPERTY_REF = Pattern.compile('^\\$\\{([^}]+)\\}$')
    private static final String  EXCLUSIONS = '(?s)<exclusions\\s*>.*?</exclusions\\s*>'

    String ecosystem() { return GoldenFix.MAVEN }

    List<String> filePatterns() { return ['pom.xml'] }

    @NonCPS
    boolean supports(String relativePath) {
        return UpdaterSupport.fileName(relativePath) == 'pom.xml'
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        Map byKey = UpdaterSupport.indexByKey(fixes)
        List changes = []
        List properties = []
        List notes = []

        String updated = UpdaterSupport.replaceGroup(content, DEPENDENCY, 1) { Matcher m ->
            String block  = m.group(1)
            String header = block.replaceAll(EXCLUSIONS, '')
            String groupId    = tag(header, 'groupId')
            String artifactId = tag(header, 'artifactId')
            Map fix = (groupId && artifactId) ? byKey[GoldenFix.key(GoldenFix.MAVEN, groupId, artifactId)] as Map : null
            if (!fix) return null

            Matcher vm = VERSION.matcher(header)
            if (!vm.find()) {
                notes << UpdaterSupport.note(relativePath, fix, 'version is not declared here (managed by a parent POM or BOM)')
                return null
            }
            String declared = vm.group(1)
            Matcher pm = PROPERTY_REF.matcher(declared)
            if (pm.matches()) {
                properties << UpdaterSupport.propertyRequest(pm.group(1), fix, relativePath)
                return null
            }
            if (!VersionUtils.isConcreteVersion(declared)) {
                notes << UpdaterSupport.note(relativePath, fix, "declared version '${declared}' is a range or dynamic version".toString())
                return null
            }
            String target = fix.targetVersion as String
            if (!VersionUtils.isUpgrade(declared, target)) return null

            changes << UpdaterSupport.change(relativePath, fix, declared, target)
            return UpdaterSupport.replaceGroup(block, VERSION, 1) { Matcher x -> x.group(1) == declared ? target : null }
        }
        return [content: updated, changes: changes, properties: properties, notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        List changes = []
        String updated = UpdaterSupport.replaceGroup(content, PROPERTIES, 1) { Matcher m ->
            String section = m.group(1)
            String newSection = section
            for (Map prop : properties) {
                String name   = prop.name as String
                String target = prop.targetVersion as String
                Pattern p = Pattern.compile('<' + Pattern.quote(name) + '>\\s*([^<]*?)\\s*</' + Pattern.quote(name) + '>')
                newSection = UpdaterSupport.replaceGroup(newSection, p, 1) { Matcher x ->
                    String value = x.group(1)
                    if (!VersionUtils.isConcreteVersion(value) || !VersionUtils.isUpgrade(value, target)) return null
                    changes << UpdaterSupport.propertyChange(relativePath, prop, value)
                    return target
                }
            }
            return newSection == section ? null : newSection
        }
        return [content: updated, changes: changes]
    }

    @NonCPS
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile('<' + name + '>\\s*([^<]*?)\\s*</' + name + '>').matcher(xml)
        return m.find() ? m.group(1) : null
    }
}
