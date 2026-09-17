package com.bbh.report.utils

import groovy.transform.Immutable

@Immutable
class HtmlReportConstants implements Serializable {

    static final Set runStatuses = ['PASS', 'FAIL', 'WARN']

    static final def sonarBadges = [
            [label: 'Duplicated lines', metric: 'duplicated_lines_density'], [label: 'Lines of code', metric: 'ncloc'],
            [label: 'Quality gate status', metric: 'alert_status'], [label: 'Security hotspots', metric: 'security_hotspots'],
            [label: 'Bugs', metric: 'bugs'], [label: 'Code smells', metric: 'code_smells'],
            [label: 'Vulnerabilities', metric: 'vulnerabilities'], [label: 'Maintainability rating', metric: 'sqale_rating'],
            [label: 'Reliability rating', metric: 'reliability_rating'], [label: 'Security rating', metric: 'security_rating'],
            [label: 'Technical debt', metric: 'sqale_index']
    ]

    static final String MONITOR     = 'Monitor source changes (download sources)'
    static final String UNIT        = 'Unit tests'
    static final String NIQ         = 'Dependencies scan (Nexus IQ)'
    static final String SAST        = 'SAST - Static Application Security Tests - HCL AppScan'
    static final String SONAR       = 'SCA (SonarQube)'
    static final String SNAPSHOT    = 'Nexus delivery (Static analysis passed)'
    static final String DEPLOY_RD   = 'Lower test region deployment'
    static final String REGRESSION  = 'Regression tests (>60% user stories coverage)'
    static final String SMOKE       = 'Smoke tests'
    static final String PERFORMANCE = 'Performance tests'
    static final String DAST        = 'DAST - Dynamic Application Security Tests - HCL AppScan'
    static final String RELEASE     = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
    static final String DEPLOY_QC   = 'Higher test environment deployment'

    static final securityAppscanScnrs = [
            [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html', stage: SAST]
    ]

    static final securityPhaseGroups = [
            [label: 'Dev',                                  color: '#3b82f6', stages: [MONITOR, UNIT]],
            [label: 'Static security & code quality scans', color: '#2563eb', stages: [NIQ, SAST, SONAR]],
            [label: 'Delivery',                             color: '#172554', stages: [SNAPSHOT]]
    ]

    static final securityStageOrder = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT]

    static final securitySecStageNames = [SAST, SONAR, NIQ]

    static final securityReportsToParse = ['sast']

    static final extendedAppscanScnrs = [
            [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: DAST]
    ]

    static final extendedStageOrder = [MONITOR, DEPLOY_RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, DEPLOY_QC]

    static final extendedSecStageNames = [DAST]

    static final extendedPhaseGroups = [
            [label: 'Dev',      color: '#3b82f6', stages: [MONITOR]],
            [label: 'Delivery', color: '#1d4ed8', stages: [DEPLOY_RD]],
            [label: 'Tests',    color: '#1e40af', stages: [REGRESSION, SMOKE, PERFORMANCE]],
            [label: 'Security', color: '#1e3a8a', stages: [DAST]],
            [label: 'Delivery', color: '#172554', stages: [RELEASE, DEPLOY_QC]]
    ]

    static final extendedReportsToParse = ['dast']

    static final appscanScnrs = [
            [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html', stage: SAST],
            [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: DAST]
    ]

    static final stageOrder = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT, DEPLOY_RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, DEPLOY_QC]

    static final secStageNames = [SAST, SONAR, DAST, NIQ]

    static final phaseGroups = [
            [label: 'Development',                          color: '#3b82f6', stages: [MONITOR, UNIT]],
            [label: 'Static security & code quality scans', color: '#2563eb', stages: [NIQ, SAST, SONAR]],
            [label: 'Delivery',                             color: '#1d4ed8', stages: [SNAPSHOT, DEPLOY_RD]],
            [label: 'Tests',                                color: '#1e40af', stages: [REGRESSION, SMOKE, PERFORMANCE]],
            [label: 'Security',                             color: '#1e3a8a', stages: [DAST]],
            [label: 'Delivery',                             color: '#172554', stages: [RELEASE, DEPLOY_QC]]
    ]

    static final reportsToParse = ['sast', 'dast']
}
