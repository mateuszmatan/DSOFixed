import com.bbh.build.BuildService
import com.bbh.config.ConfigLoader
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.report.HtmlSecurityReportService
import com.bbh.scanner.AppScanService
import com.bbh.scanner.NexusIqService
import com.bbh.scanner.SonarService
import com.bbh.metrics.InfluxDbService
import com.bbh.deploy.OpenshiftService
import com.bbh.deploy.VmDeployService

import groovy.transform.Field

@Field PipelineState _state
@Field OsHelper          _os
@Field PolicyEngine      _policy
@Field ConfigLoader      _config
@Field BuildService      _build
@Field AppScanService    _appScan
@Field SonarService      _sonar
@Field NexusIqService    _nexusIq
@Field HtmlSecurityReportService _report
@Field InfluxDbService   _influx
@Field OpenshiftService  _openshift
@Field VmDeployService   _vm

private void _setup() {
    if (_state != null) return
    _state     = new PipelineState()
    _os        = new OsHelper(this)
    _policy    = new PolicyEngine(this, _state, _os)
    _build     = new BuildService(this, _state, _os, _policy)
    _config    = new ConfigLoader(this, _state)
    _appScan   = new AppScanService(this, _state, _os, _policy, _build)
    _sonar     = new SonarService(this, _state, _os, _build)
    _nexusIq   = new NexusIqService(this, _state, com.bbh.remediation.GoldenFixFactory.create(this, _state))
    _report    = new HtmlSecurityReportService(this, _state, _os, _policy)
    _influx    = new InfluxDbService(this, _state, _os)
    _openshift = new OpenshiftService(this, _state)
    _vm        = new VmDeployService(this, _state, _os)
}

def initialize() {
    _setup()
    _os.detect()
    env.OS_TYPE = _os.getType()
    _os.chmodX('gradlew')
    _config.initialize()
}

def appscanSetup()                               { _setup(); _appScan.setup() }
def appscanLogin()                               { _setup(); _appScan.cliLogin() }
def appscanResolveSourceDir(String p = null)     { _setup(); _appScan.resolveSourceDir(p) }
def appscanGenerateIRX(int timeoutMin = 120)     { _setup(); _appScan.generateIrx(timeoutMin) }
def appscanQueue(Map config)                               { _setup(); _appScan.queueSast(config) }
def appscanWait()                                { _setup(); _appScan.waitSast() }
def appscanDownloadReports()                     { _setup(); _appScan.downloadSastReports() }
def appscanRenameSastReport()                    { _setup(); _appScan.renameSastReport() }
def reportArtifactBuild()     { _setup(); _build.reportArtifactBuild() }
def reportUnitTests()         { _setup(); _build.reportUnitTests() }
def buildArtifact()           { _setup(); _build.buildArtifact() }
def unitTests()               { _setup(); _build.unitTests() }
def checkCoverage(int min = 60) {
    _setup()
    _build.checkCoverage(min ?: (_state.coverage?.minRequired ?: 60))
}

def appscanEnforcePolicy() {
    _setup()
    _policy.enforceScanner('sast')
    def counts = _state.vulnCounts.sast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def sonarscanEnforcePolicy() {
    _setup()
    _policy.enforceScanner('sca')

    def counts = _state.vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}


def depVulnScan()             { _setup(); _nexusIq.scan() }
def codeQualityScan()         { _setup(); _sonar.scan() }

def generateHtmlReport()      { _setup(); _report.generate() }
def feedInfluxDB(String pipelineType)            { _setup(); _influx.send(pipelineType) }

def getProjects()                          { _setup(); return _config.resolveProjectNames() }
def getCFG()                               { _setup(); return _state.cfg }
def switchProject(String projectName)      { _setup(); _config.switchProject(projectName) }

def stagePass(String name)                 { _setup(); _state.stagePass(name) }
def stageFail(String name)                 { _setup(); _state.stageFail(name) }
def stageWarn(String name)                 { _setup(); _state.stageWarn(name) }
def stageError(String name, String reason) { _setup(); _state.stageError(name, reason) }
def stageStart(String name)                { _setup(); _state.stageStart(name) }
def stageDone(String name)                 { _setup(); _state.stageDone(name) }
def buildDockerImage(String projectName)   { _setup(); _openshift.buildDockerImage(projectName) }
def copyImageToNexus()                     { _setup(); _openshift.copyImageToNexus() }
def runExtendedPipeline()                  { _setup(); _openshift.runExtendedPipeline() }
def pushToNexus(String projectName)        {_setup();  _vm.pushToNexus(projectName)}

def releaseAllowed(String stageName) {
    _setup()
    return new com.bbh.core.ReleaseGate(this, _state).allowed(stageName)
}

def publishReleaseGate() {
    _setup()
    new com.bbh.core.ReleaseGate(this, _state).publish()
}

def logStageResult(String stageName, String status) {
    _setup()
    new com.bbh.core.StageLogger(this, _state).logStageResult(stageName, status)
}

def section(String text)      { _setup(); new com.bbh.core.StageLogger(this, _state).section(text) }
def startSection(String text) { _setup(); new com.bbh.core.StageLogger(this, _state).startSection(text) }
def endSection(String text)   { _setup(); new com.bbh.core.StageLogger(this, _state).endSection(text) }

def call(Map config = [:]) {
    def dsl        = this
    def sastVulns  = 0

    if (config.projectNames) env.PROJECT_NAMES = config.projectNames

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            booleanParam(
                name:         'RUN_EXTENDED_PIPELINE',
                defaultValue: false,
                description:  'Run extended pipeline steps'
            )

            choice(
                name:         'AGENT_NAME',
                choices:      config.agentNames,
                description:  'Jenkins agent label'
            )
        }

        agent { label params.AGENT_NAME }

        environment {
            SA_LINUX_URL       = 'https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Linux/SAClientUtil-8.0.1646_Linux-SAClientUtil_8.0.1646_Linux.zip'
            SA_WIN_URL         = 'https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Win/SAClientUtil-8.0.1646_Win-SAClientUtil_8.0.1646_Win.zip'
            PROXY_HOST         = 'tstproxy.bbh.com'
            PROXY_PORT         = '9090'
            PROXY_USER         = 'PROXY_ASOCJenk'
        }

        stages {
            stage('Monitor source changes (download sources)') {
                steps {
                    script {
                        dsl.stageStart('Monitor source changes (download sources)')
                        dsl.initialize()
                    }
                }
                post {
                    always   { script { dsl.stageDone('Monitor source changes (download sources)') } }
                    success  { script { dsl.stagePass('Monitor source changes (download sources)'); dsl.logStageResult('Monitor source changes (download sources)', 'PASS') } }
                    failure  { script { dsl.stageFail('Monitor source changes (download sources)'); dsl.logStageResult('Monitor source changes (download sources)', 'FAIL') } }
                }
            }

            stage('Unit tests') {
                steps {
                    script {
                        dsl.stageStart('Unit tests')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.buildArtifact()
                            dsl.unitTests()
                            dsl.checkCoverage((dsl.getCFG().coverage?.minLine ?: 60) as int)
                        }
                    }
                }
                post {
                    always   { script { dsl.stageDone('Unit tests') } }
                    success  { script { dsl.stagePass('Unit tests'); dsl.logStageResult('Unit tests', 'PASS') } }
                    failure  { script { dsl.stageFail('Unit tests'); dsl.logStageResult('Unit tests', 'FAIL') } }
                    unstable { script { dsl.stageWarn('Unit tests'); dsl.logStageResult('Unit tests', 'WARN') } }
                }
            }

            stage('Dependencies scan (Nexus IQ)') {
                steps {
                    script {
                        dsl.stageStart('Dependencies scan (Nexus IQ)')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.depVulnScan()
                        }
                    }
                }
                post {
                    always   { script { dsl.stageDone('Dependencies scan (Nexus IQ)') } }
                    success  { script { dsl.stagePass('Dependencies scan (Nexus IQ)'); dsl.logStageResult('Dependencies scan (Nexus IQ)', 'PASS') } }
                    failure  { script { dsl.stageFail('Dependencies scan (Nexus IQ)'); dsl.logStageResult('Dependencies scan (Nexus IQ)', 'FAIL') } }
                    unstable { script { dsl.stageWarn('Dependencies scan (Nexus IQ)'); dsl.logStageResult('Dependencies scan (Nexus IQ)', 'WARN') } }
                }
            }

            stage('SCA (SonarQube)') {
                steps {
                    script {
                        dsl.stageStart('SCA (SonarQube)')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.codeQualityScan()
                            dsl.sonarscanEnforcePolicy()
                        }
                    }
                }
                post {
                    always   { script { dsl.stageDone('SCA (SonarQube)') } }
                    success  { script { dsl.stagePass('SCA (SonarQube)'); dsl.logStageResult('SCA (SonarQube)', 'PASS') } }
                    failure  { script { dsl.stageFail('SCA (SonarQube)'); dsl.logStageResult('SCA (SonarQube)', 'FAIL') } }
                    unstable { script { dsl.stageWarn('SCA (SonarQube)'); dsl.logStageResult('SCA (SonarQube)', 'WARN') } }
                }
            }

            stage('SAST - Static Application Security Tests - HCL AppScan') {
                steps {
                    script {
                        dsl.stageStart('SAST - Static Application Security Tests - HCL AppScan')
                        dsl.appscanSetup()
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.appscanResolveSourceDir()
                        }
                        dsl.appscanLogin()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.appscanGenerateIRX()
                            dsl.appscanQueue(config)
                        }
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.appscanWait()
                            dsl.appscanDownloadReports()
                            dsl.appscanRenameSastReport()
                            sastVulns += dsl.appscanEnforcePolicy()
                       }
                    }
                }
                post {
                    always   { script { dsl.stageDone('SAST - Static Application Security Tests - HCL AppScan') } }
                    success  { script { dsl.stagePass('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'PASS') } }
                    failure  { script { dsl.stageFail('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'FAIL') } }
                    unstable { script { dsl.stageWarn('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'WARN') } }
                }
            }

            stage('Nexus delivery (Static analysis passed)') {
                when { expression { return dsl.releaseAllowed('Nexus delivery (Static analysis passed)') } }
                steps {
                    script {
                        dsl.stageStart('Nexus delivery (Static analysis passed)')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.reportArtifactBuild()
                            dsl.reportUnitTests()
                            if ((dsl.getCFG().deployTarget ?: 'vm') == 'openshift') {
                                dsl.buildDockerImage(pName)
                                dsl.copyImageToNexus()
                            } else {
                                dsl.pushToNexus(pName)
                            }
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Nexus delivery (Static analysis passed)') } }
                    success { script { dsl.stagePass('Nexus delivery (Static analysis passed)'); dsl.logStageResult('Nexus delivery (Static analysis passed)', 'PASS') } }
                    failure { script { dsl.stageFail('Nexus delivery (Static analysis passed)'); dsl.logStageResult('Nexus delivery (Static analysis passed)', 'FAIL') } }
                }
            }
        }

        post {
            always {
                script {
                    try { dsl.publishReleaseGate() }
                    catch (Throwable t) { echo "[WARN] Could not write the release gate state: ${t.message}" }
                    try {
                        dsl.generateHtmlReport()
                    }
                    catch (Throwable t) {
                        echo "[WARN] Could not generate HTML report: ${t.message}"
                    }
                    try {
                        dsl.feedInfluxDB('security')
                    } catch (Throwable t) {
                        echo "[WARN] Could not feed InfluxDB metrics: ${t.message}"
                    }
                    if (!fileExists('report/pipeline-report.html')) {
                        writeFile file: 'report/pipeline-report.html',
                                text: "<html><head><meta charset='UTF-8'><title>Pipeline Report</title></head><body style='font-family:sans-serif;padding:24px'><h2>Pipeline Report</h2><p>Report could not be generated - check build logs.</p></body></html>"
                    }
                    echo "Vulnerabilities: SAST=${sastVulns}"
                    dsl.section('Pipeline finished')
                }
                archiveArtifacts(
                    artifacts:         'report/pipeline-report.html,appscan-report*.html,config.yaml,release-gate.json',
                    fingerprint:       true,
                    allowEmptyArchive: true
                )
                publishHTML([
                    allowMissing:          true,
                    alwaysLinkToLastBuild: true,
                    keepAll:               true,
                    reportDir:             'report',
                    reportFiles:           'pipeline-report.html',
                    reportName:            'Pipeline Report',
                    reportTitles:          ''
                ])

                cleanWs(notFailBuild: true)
            }
            success {
                script { dsl.runExtendedPipeline()
                    dsl.section('Pipeline completed successfully!') }
            }
            failure {
                script { dsl.section('Pipeline FAILED') }
            }
            unstable {
                script { dsl.runExtendedPipeline()
                    dsl.section('Pipeline completed with warnings (unstable).') }
            }
        }
    }
}
