package com.bbh.report.utils

import groovy.transform.Immutable

@Immutable
class HtmlReportConstants implements Serializable {

    static final Set runStatuses = ['PASS', 'FAIL', 'WARN', 'OVERRIDE']

    static final def sonarBadges = [
            [label: 'Duplicated lines', metric: 'duplicated_lines_density'], [label: 'Lines of code', metric: 'ncloc'],
            [label: 'Quality gate status', metric: 'alert_status'], [label: 'Security hotspots', metric: 'security_hotspots'],
            [label: 'Bugs', metric: 'bugs'], [label: 'Code smells', metric: 'code_smells'],
            [label: 'Vulnerabilities', metric: 'vulnerabilities'], [label: 'Maintainability rating', metric: 'sqale_rating'],
            [label: 'Reliability rating', metric: 'reliability_rating'], [label: 'Security rating', metric: 'security_rating'],
            [label: 'Technical debt', metric: 'sqale_index']
    ]

    static final securityAppscanScnrs = [
            [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html',      stage: 'SAST - Static Application Security Tests - HCL AppScan']
    ]

    static final securityPhaseGroups = [
            [label: 'Dev',                                  color: '#3b82f6', stages: ['Monitor source changes (download sources)', 'Unit tests']],
            [label: 'Static security & code quality scans', color: '#2563eb', stages: ['Dependencies scan (Nexus IQ)', 'SCA (SonarQube)', 'SAST - Static Application Security Tests - HCL AppScan']],
            [label: 'Delivery',                             color: '#172554', stages: ['Nexus delivery (Static analysis passed)']]

    ]

    static final securityStageOrder = [
            'Monitor source changes (download sources)', 'Unit tests',
            'Dependencies scan (Nexus IQ)', 'SCA (SonarQube)',
            'SAST - Static Application Security Tests - HCL AppScan',
            'Nexus delivery (Static analysis passed)',
    ]

    static final securitySecStageNames = [
            'SAST - Static Application Security Tests - HCL AppScan',
            'SCA (SonarQube)',
            'Dependencies scan (Nexus IQ)'
    ]

    static final securityReportsToParse =  ['sast']


    static final extendedAppscanScnrs = [
            [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: 'DAST - Dynamic Application Security Tests - HCL AppScan']
    ]

    static final extendedStageOrder = [
            'Monitor source changes (download sources)', 'Lower test region deployment',
            'Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests',
            'DAST - Dynamic Application Security Tests - HCL AppScan',
            'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities', 'Higher test environment deployment'
    ]

    static final extendedSecStageNames = [
            'DAST - Dynamic Application Security Tests - HCL AppScan'
    ]

    static final extendedPhaseGroups = [
            [label: 'Dev',                                  color: '#3b82f6', stages: ['Monitor source changes (download sources)']],
            [label: 'Delivery',                             color: '#1d4ed8', stages: ['Lower test region deployment']],
            [label: 'Tests',                                color: '#1e40af', stages: ['Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests']],
            [label: 'Security',                             color: '#1e3a8a', stages: ['DAST - Dynamic Application Security Tests - HCL AppScan']],
            [label: 'Delivery',                             color: '#172554', stages: ['Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities', 'Higher test environment deployment']]
    ]

    static final extendedReportsToParse =  ['dast']

    static final appscanScnrs = [
            [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html',      stage: 'SAST - Static Application Security Tests - HCL AppScan'],
            [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: 'DAST - Dynamic Application Security Tests - HCL AppScan']
    ]

    static final stageOrder = [
            'Monitor source changes (download sources)', 'Unit tests',
            'Dependencies scan (Nexus IQ)', 'SCA (SonarQube)',
            'SAST - Static Application Security Tests - HCL AppScan',
            'Nexus delivery (Static analysis passed)', 'Lower test region deployment',
            'Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests',
            'DAST - Dynamic Application Security Tests - HCL AppScan',
            'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities',
            'Higher test environment deployment'
    ]

    static final secStageNames = [
            'SAST - Static Application Security Tests - HCL AppScan',
            'SCA (SonarQube)',
            'DAST - Dynamic Application Security Tests - HCL AppScan',
            'Dependencies scan (Nexus IQ)'
    ]

    static final phaseGroups = [
            [label: 'Development',                          color: '#3b82f6', stages: ['Monitor source changes (download sources)', 'Unit tests']],
            [label: 'Static security & code quality scans', color: '#2563eb', stages: ['Dependencies scan (Nexus IQ)', 'SCA (SonarQube)', 'SAST - Static Application Security Tests - HCL AppScan']],
            [label: 'Delivery',                             color: '#1d4ed8', stages: ['Nexus delivery (Static analysis passed)', 'Lower test region deployment']],
            [label: 'Tests',                                color: '#1e40af', stages: ['Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests']],
            [label: 'Security',                             color: '#1e3a8a', stages: ['DAST - Dynamic Application Security Tests - HCL AppScan']],
            [label: 'Delivery',                             color: '#172554', stages: ['Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities', 'Higher test environment deployment']]
    ]

    static final reportsToParse =  ['sast', 'dast']

}
