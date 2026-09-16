package com.bbh.remediation.model

import com.cloudbees.groovy.cps.NonCPS

class GoldenFix implements Serializable {

    static final String MAVEN = 'maven'
    static final String NPM   = 'npm'
    static final String PYPI  = 'pypi'

    static final List<String> SUPPORTED_ECOSYSTEMS = [MAVEN, NPM, PYPI]

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
            case PYPI: return "${PYPI}:${normalizePypiName(name)}".toString()
            case NPM:  return "${NPM}:${(name ?: '').toLowerCase()}".toString()
            default:   return "${MAVEN}:${group ?: ''}:${name ?: ''}".toString()
        }
    }

    @NonCPS
    static String displayName(String ecosystem, String group, String name) {
        return (ecosystem == MAVEN && group) ? "${group}:${name}".toString() : (name ?: '')
    }

    @NonCPS
    static String normalizePypiName(String name) {
        return (name ?: '').toLowerCase().replaceAll(/[-_.]+/, '-')
    }
}
