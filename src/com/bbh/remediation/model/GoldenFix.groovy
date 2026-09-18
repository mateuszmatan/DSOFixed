package com.bbh.remediation.model

import com.cloudbees.groovy.cps.NonCPS

class GoldenFix implements Serializable {

    @NonCPS
    static List<String> supportedEcosystems() {
        return ['maven', 'npm', 'pypi']
    }

    @NonCPS
    static Map create(String ecosystem, String group, String name, String currentVersion, String targetVersion,
                      String remediationType, String packageUrl, int threatLevel, Boolean direct, String application) {
        return [
                ecosystem      : ecosystem,
                group          : group ?: '',
                name           : name,
                currentVersion : currentVersion,
                targetVersion  : targetVersion,
                remediationType: remediationType ?: '',
                packageUrl     : packageUrl ?: '',
                threatLevel    : threatLevel,
                direct         : direct,
                application    : application ?: '',
                key            : key(ecosystem, group, name),
                displayName    : displayName(ecosystem, group, name)
        ]
    }

    @NonCPS
    static String key(String ecosystem, String group, String name) {
        switch (ecosystem) {
            case 'pypi': return "pypi:${normalizePypiName(name)}".toString()
            case 'npm':  return "npm:${(name ?: '').toLowerCase()}".toString()
            default:     return "maven:${group ?: ''}:${name ?: ''}".toString()
        }
    }

    @NonCPS
    static String displayName(String ecosystem, String group, String name) {
        return (ecosystem == 'maven' && group) ? "${group}:${name}".toString() : (name ?: '')
    }

    @NonCPS
    static String normalizePypiName(String name) {
        return (name ?: '').toLowerCase().replaceAll(/[-_.]+/, '-')
    }
}
