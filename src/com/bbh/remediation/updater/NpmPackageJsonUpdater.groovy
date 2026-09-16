package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class NpmPackageJsonUpdater implements ManifestUpdater {

    private static final Pattern SECTION = Pattern.compile('(?s)("(?:dependencies|devDependencies|peerDependencies|optionalDependencies)"\\s*:\\s*\\{)([^{}]*)(\\})')

    String ecosystem() { return GoldenFix.NPM }

    List<String> filePatterns() { return ['package.json'] }

    @NonCPS
    boolean supports(String relativePath) {
        String normalized = (relativePath ?: '').replace('\\', '/')
        return UpdaterSupport.fileName(normalized) == 'package.json' && !normalized.contains('node_modules/')
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List notes = []

        String updated = UpdaterSupport.replaceGroup(content, SECTION, 2) { Matcher m ->
            String section = m.group(2)
            String newSection = section
            for (Map fix : fixes) {
                String q = Pattern.quote(fix.name as String)
                String target = fix.targetVersion as String
                boolean handled = false
                Pattern simple = Pattern.compile('"' + q + '"\\s*:\\s*"(\\^|~|>=|=)?\\s*v?(\\d[\\w.\\-+]*)"')
                newSection = UpdaterSupport.replaceGroup(newSection, simple, 2) { Matcher x ->
                    handled = true
                    String declared = x.group(2)
                    if (!VersionUtils.isConcreteVersion(declared) || !VersionUtils.isUpgrade(declared, target)) return null
                    changes << UpdaterSupport.change(relativePath, fix, declared, target)
                    return target
                }
                if (!handled) {
                    Matcher any = Pattern.compile('"' + q + '"\\s*:\\s*"([^"]*)"').matcher(newSection)
                    if (any.find()) {
                        notes << UpdaterSupport.note(relativePath, fix, "version spec '${any.group(1)}' is not a simple version".toString())
                    }
                }
            }
            return newSection == section ? null : newSection
        }
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }
}
