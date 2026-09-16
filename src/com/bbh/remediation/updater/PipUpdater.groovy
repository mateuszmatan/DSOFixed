package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class PipUpdater implements ManifestUpdater {

    String ecosystem() { return GoldenFix.PYPI }

    List<String> filePatterns() { return ['requirements*.txt', 'constraints*.txt', 'pyproject.toml'] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath)
        return (name ==~ /(?i)(requirements|constraints).*\.txt/) || name == 'pyproject.toml'
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List notes = []
        boolean pyproject = UpdaterSupport.fileName(relativePath) == 'pyproject.toml'
        String updated = content

        for (Map fix : fixes) {
            String np = namePattern(fix.name as String)
            if (!pyproject) {
                Pattern p = Pattern.compile('(?im)^[ \\t]*' + np + '(?:[ \\t]*\\[[^\\]\\r\\n]*\\])?[ \\t]*(===|==|~=|>=)[ \\t]*([^\\s;,#\\\\]+)([^\\r\\n]*)')
                if (content.contains('--hash=')) {
                    if (p.matcher(updated).find()) {
                        notes << UpdaterSupport.note(relativePath, fix, 'file uses --hash pinning - regenerate it with pip-compile')
                    }
                    continue
                }
                updated = UpdaterSupport.replaceGroup(updated, p, 2) { Matcher m ->
                    return bump(relativePath, fix, m.group(1), m.group(2), m.group(3), changes, notes)
                }
            } else {
                Pattern pep508 = Pattern.compile('(?i)(["\'])' + np + '(?:\\[[^\\]"\']*\\])?\\s*(===|==|~=|>=)\\s*([^"\',;\\s]+)([^"\']*)\\1')
                updated = UpdaterSupport.replaceGroup(updated, pep508, 3) { Matcher m ->
                    return bump(relativePath, fix, m.group(2), m.group(3), m.group(4), changes, notes)
                }
                List<Pattern> poetry = [
                        Pattern.compile('(?im)^[ \\t]*["\']?' + np + '["\']?[ \\t]*=[ \\t]*"(\\^|~|==|>=)?[ \\t]*(\\d[^"]*)"'),
                        Pattern.compile('(?im)^[ \\t]*["\']?' + np + '["\']?[ \\t]*=[ \\t]*\\{[^}\\r\\n]*?version[ \\t]*=[ \\t]*"(\\^|~|==|>=)?[ \\t]*(\\d[^"]*)"')
                ]
                for (Pattern p : poetry) {
                    updated = UpdaterSupport.replaceGroup(updated, p, 2) { Matcher m ->
                        return bump(relativePath, fix, m.group(1) ?: '==', m.group(2), '', changes, notes)
                    }
                }
            }
        }
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }

    @NonCPS
    private static String bump(String path, Map fix, String operator, String declared, String rest, List changes, List notes) {
        String target = fix.targetVersion as String
        if (operator == '>=' && (rest ?: '').contains('<')) {
            notes << UpdaterSupport.note(path, fix, "requirement '${operator}${declared}${rest.trim()}' has an upper bound - update it manually".toString())
            return null
        }
        if (!VersionUtils.isConcreteVersion(declared)) {
            notes << UpdaterSupport.note(path, fix, "declared version '${declared}' is not a simple version".toString())
            return null
        }
        if (!VersionUtils.isUpgrade(declared, target)) return null
        changes << UpdaterSupport.change(path, fix, declared, target)
        return target
    }

    @NonCPS
    private static String namePattern(String name) {
        List parts = GoldenFix.normalizePypiName(name).tokenize('-')
        return parts.collect { Pattern.quote(it as String) }.join('[-_.]+')
    }
}
