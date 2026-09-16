package com.bbh.remediation.updater

import com.bbh.utils.VersionUtils
import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

class UpdaterSupport implements Serializable {

    @NonCPS
    static String fileName(String path) {
        String p = (path ?: '').replace('\\', '/')
        int i = p.lastIndexOf('/')
        return i >= 0 ? p.substring(i + 1) : p
    }

    @NonCPS
    static Map indexByKey(List<Map> fixes) {
        Map index = [:]
        for (Map fix : (fixes ?: [])) index[fix.key as String] = fix
        return index
    }

    @NonCPS
    static String replaceGroup(String text, Pattern pattern, int group, Closure replacement) {
        if (text == null) return null
        Matcher m = pattern.matcher(text)
        StringBuilder out = new StringBuilder(text.length() + 64)
        int last = 0
        while (m.find()) {
            if (m.start(group) < 0) continue
            def repl = replacement.call(m)
            if (repl != null) {
                out.append(text, last, m.start(group)).append(repl as String)
                last = m.end(group)
            }
        }
        out.append(text, last, text.length())
        return out.toString()
    }

    @NonCPS
    static Map change(String path, Map fix, String from, String to) {
        return [
                file           : path,
                ecosystem      : fix.ecosystem,
                component      : fix.displayName,
                componentKeys  : [fix.key],
                from           : from,
                to             : to,
                property       : '',
                remediationType: fix.remediationType ?: ''
        ]
    }

    @NonCPS
    static Map propertyChange(String path, Map property, String from) {
        return [
                file           : path,
                ecosystem      : property.ecosystem,
                component      : (property.components as List).join(', '),
                componentKeys  : new ArrayList(property.componentKeys as List),
                from           : from,
                to             : property.targetVersion,
                property       : property.name,
                remediationType: ''
        ]
    }

    @NonCPS
    static Map note(String path, Map fix, String reason) {
        return [
                file        : path,
                component   : fix.displayName,
                componentKey: fix.key,
                from        : fix.currentVersion,
                to          : fix.targetVersion,
                reason      : reason
        ]
    }

    @NonCPS
    static Map propertyRequest(String name, Map fix, String path) {
        return [
                name         : name,
                ecosystem    : fix.ecosystem,
                targetVersion: fix.targetVersion,
                componentKeys: [fix.key],
                components   : [fix.displayName],
                referencedIn : [path]
        ]
    }

    @NonCPS
    static List<Map> mergePropertyRequests(List<Map> requests) {
        Map byName = new LinkedHashMap()
        for (Map r : (requests ?: [])) {
            Map existing = byName[r.name] as Map
            if (!existing) {
                byName[r.name] = [
                        name         : r.name,
                        ecosystem    : r.ecosystem,
                        targetVersion: r.targetVersion,
                        componentKeys: new ArrayList(r.componentKeys as List),
                        components   : new ArrayList(r.components as List),
                        referencedIn : new ArrayList(r.referencedIn as List)
                ]
                continue
            }
            existing.targetVersion = VersionUtils.max(existing.targetVersion as String, r.targetVersion as String)
            addAllUnique(existing.componentKeys as List, r.componentKeys as List)
            addAllUnique(existing.components as List, r.components as List)
            addAllUnique(existing.referencedIn as List, r.referencedIn as List)
        }
        return new ArrayList(byName.values())
    }

    @NonCPS
    private static void addAllUnique(List target, List source) {
        for (def item : (source ?: [])) {
            if (!target.contains(item)) target << item
        }
    }
}
