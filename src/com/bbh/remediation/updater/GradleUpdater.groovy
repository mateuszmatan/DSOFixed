package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class GradleUpdater implements ManifestUpdater {

    private static final Pattern PROPERTY_REF = Pattern.compile('^\\$\\{?([A-Za-z_][\\w.]*)\\}?$')
    private static final Pattern TOML_VERSIONS_SECTION = Pattern.compile('(?ms)^\\[versions\\][ \\t]*$(.*?)(?=^\\[|\\z)')

    String ecosystem() { return GoldenFix.MAVEN }

    List<String> filePatterns() { return ['build.gradle', 'build.gradle.kts', 'gradle.properties', '*.versions.toml'] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath)
        return name in ['build.gradle', 'build.gradle.kts', 'gradle.properties'] || name.endsWith('.versions.toml')
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List properties = []
        List notes = []
        if (UpdaterSupport.fileName(relativePath) == 'gradle.properties') {
            return [content: content, changes: changes, properties: properties, notes: notes]
        }

        String updated = content
        for (Map fix : fixes) {
            String g = Pattern.quote(fix.group as String)
            String a = Pattern.quote(fix.name as String)
            List<Map> declarations = [
                    [pattern: Pattern.compile('([\'"])' + g + ':' + a + ':([^\'":@\\s]+)((?::[^\'"@\\s]*)?(?:@[^\'"\\s]*)?)\\1'), group: 2],
                    [pattern: Pattern.compile('group\\s*[:=]\\s*([\'"])' + g + '\\1\\s*,\\s*name\\s*[:=]\\s*([\'"])' + a + '\\2\\s*,\\s*version\\s*[:=]\\s*([\'"])([^\'"]+)\\3'), group: 4],
                    [pattern: Pattern.compile('module\\s*=\\s*"' + g + ':' + a + '"\\s*,\\s*version\\s*=\\s*"([^"]+)"'), group: 1]
            ]
            for (Map declaration : declarations) {
                int group = declaration.group as int
                updated = UpdaterSupport.replaceGroup(updated, declaration.pattern as Pattern, group) { Matcher m ->
                    return handleDeclaredVersion(relativePath, fix, m.group(group), changes, properties, notes)
                }
            }
            Pattern ref = Pattern.compile('(?:module\\s*=\\s*"' + g + ':' + a + '"|group\\s*=\\s*"' + g + '"\\s*,\\s*name\\s*=\\s*"' + a + '")\\s*,\\s*version\\.ref\\s*=\\s*"([^"]+)"')
            Matcher rm = ref.matcher(updated)
            while (rm.find()) {
                properties << UpdaterSupport.propertyRequest(rm.group(1), fix, relativePath)
            }
        }
        return [content: updated, changes: changes, properties: properties, notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        List changes = []
        String fileName = UpdaterSupport.fileName(relativePath)
        String updated = content

        for (Map prop : properties) {
            String q = Pattern.quote(prop.name as String)
            String target = prop.targetVersion as String
            Closure bump = { Matcher m, int group ->
                String value = m.group(group)
                if (!VersionUtils.isConcreteVersion(value) || !VersionUtils.isUpgrade(value, target)) return null
                changes << UpdaterSupport.propertyChange(relativePath, prop, value)
                return target
            }

            if (fileName == 'gradle.properties') {
                Pattern p = Pattern.compile('(?m)^[ \\t]*' + q + '[ \\t]*[=:][ \\t]*([^\\s#]+)')
                updated = UpdaterSupport.replaceGroup(updated, p, 1) { Matcher m -> bump(m, 1) }
            } else if (fileName.endsWith('.versions.toml')) {
                Pattern p = Pattern.compile('(?m)^[ \\t]*"?' + q + '"?[ \\t]*=[ \\t]*"([^"]+)"')
                updated = UpdaterSupport.replaceGroup(updated, TOML_VERSIONS_SECTION, 1) { Matcher section ->
                    String original = section.group(1)
                    String replaced = UpdaterSupport.replaceGroup(original, p, 1) { Matcher m -> bump(m, 1) }
                    return replaced == original ? null : replaced
                }
            } else {
                List<Pattern> patterns = [
                        Pattern.compile('(?<![\\w.])(?:(?:rootProject|project)\\.)?(?:ext(?:ra)?\\.)?' + q + '\\s*=\\s*([\'"])([^\'"\\r\\n]+)\\1'),
                        Pattern.compile('(?:extra\\[\\s*"' + q + '"\\s*\\]\\s*=|set\\(\\s*[\'"]' + q + '[\'"]\\s*,|' + q + '\\s+by\\s+extra\\()\\s*([\'"])([^\'"\\r\\n]+)\\1')
                ]
                for (Pattern p : patterns) {
                    updated = UpdaterSupport.replaceGroup(updated, p, 2) { Matcher m -> bump(m, 2) }
                }
            }
        }
        return [content: updated, changes: changes]
    }

    @NonCPS
    private static String handleDeclaredVersion(String path, Map fix, String declared, List changes, List properties, List notes) {
        Matcher pm = PROPERTY_REF.matcher(declared ?: '')
        if (pm.matches()) {
            String name = pm.group(1)
            if (!name.startsWith('libs.')) {
                List segments = name.tokenize('.')
                properties << UpdaterSupport.propertyRequest(segments[segments.size() - 1] as String, fix, path)
            }
            return null
        }
        if (!VersionUtils.isConcreteVersion(declared)) {
            notes << UpdaterSupport.note(path, fix, "declared version '${declared}' is dynamic or a range".toString())
            return null
        }
        String target = fix.targetVersion as String
        if (!VersionUtils.isUpgrade(declared, target)) return null
        changes << UpdaterSupport.change(path, fix, declared, target)
        return target
    }
}
