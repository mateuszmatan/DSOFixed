
import groovy.transform.Field
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.config.ConfigLoader
import com.bbh.build.BuildService
import com.bbh.scanner.AppScanService
import com.bbh.scanner.SonarService
import com.bbh.scanner.NexusIqService
import com.bbh.deploy.VmDeployService
import com.bbh.deploy.OpenshiftService
import com.bbh.metrics.InfluxDbService
import com.bbh.report.HtmlReportService

@Field PipelineState     _state
@Field OsHelper          _os
@Field PolicyEngine      _policy
@Field ConfigLoader      _config
@Field BuildService      _build
@Field AppScanService    _appScan
@Field SonarService      _sonar
@Field NexusIqService    _nexusIq
@Field VmDeployService   _vmDeploy
@Field OpenshiftService  _openshift
@Field InfluxDbService   _influx
@Field HtmlReportService _report

private void _setup() {
    if (_state != null) return
    _state     = new PipelineState()
    _os        = new OsHelper(this)
    _policy    = new PolicyEngine(this, _state, _os)
    _config    = new ConfigLoader(this, _state)
    _build     = new BuildService(this, _state, _os, _policy)
    _appScan   = new AppScanService(this, _state, _os, _policy, _build)
    _sonar     = new SonarService(this, _state, _os, _build)
    _nexusIq   = new NexusIqService(this, _state, com.bbh.remediation.GoldenFixFactory.create(this, _state))
    _vmDeploy  = new VmDeployService(this, _state, _os)
    _openshift = new OpenshiftService(this, _state)
    _influx    = new InfluxDbService(this, _state, _os)
    _report    = new HtmlReportService(this, _state, _os, _policy)
}

def initialize() {
    _setup()
    _os.detect()
    env.OS_TYPE = _os.getType()
    _os.chmodX('gradlew')
    _os.checkJavaVersionsAvailable()
    _config.initialize()
}

def appscanSetup()                               { _setup(); _appScan.setup() }
def appscanLogin()                               { _setup(); _appScan.cliLogin() }
def appscanResolveSourceDir(String p = null)     { _setup(); _appScan.resolveSourceDir(p) }
def appscanGenerateIRX(int timeoutMin = 120)      { _setup(); _appScan.generateIrx(timeoutMin) }
def appscanQueue(Map config)                               { _setup(); _appScan.queueSast(config) }
def appscanWait()                                { _setup(); _appScan.waitSast() }
def appscanDownloadReports()                     { _setup(); _appScan.downloadSastReports() }
def appscanRenameSastReport()                    { _setup(); _appScan.renameSastReport() }
def appscanRenameDastReport()                    { _setup(); _appScan.renameDastReport() }

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

def dastScan() {
    _setup()
    _appScan.dastScan()
    def counts = _state.vulnCounts.dast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def buildArtifact()           { _setup(); _build.buildArtifact() }
def unitTests()               { _setup(); _build.unitTests() }
def checkCoverage(int min = 60) {
    _setup()
    _build.checkCoverage(min ?: (_state.coverage?.minRequired ?: 60))
}
def reportArtifactBuild()     { _setup(); _build.reportArtifactBuild() }
def reportUnitTests()         { _setup(); _build.reportUnitTests() }

def depVulnScan()             { _setup(); _nexusIq.scan() }
def codeQualityScan()         { _setup(); _sonar.scan() }

def smokeTests() {
    _setup()
    _build.runTestJobs('Smoke tests', 'Smoke Tests', _state.cfg.tests?.smoke ?: [:])
}
def regressionTests() {
    _setup()
    _build.runTestJobs('Regression tests (>60% user stories coverage)', 'Regression Tests', _state.cfg.tests?.regression ?: [:])
}
def performanceTests() {
    _setup()
    _build.runTestJobs('Performance tests', 'Performance Tests', _state.cfg.tests?.performance ?: [:])
}

def deployRD()                              { _setup(); _vmDeploy.deployRd() }
def deployQC()                              { _setup(); _vmDeploy.deployQc() }
def publishArtifactQC()                     { _setup(); _vmDeploy.publishToNexus() }
def bumpVersion(String vf, String ver)      { _setup(); _vmDeploy.bumpVersion(vf, ver) }

def buildDockerImage()                      { _setup(); _openshift.buildDockerImage() }
def copyImageToNexus()                      { _setup(); _openshift.copyImageToNexus() }
def checkDeploymentRepo()                   { _setup(); _openshift.checkDeploymentRepo() }
def deployOpenshift(String envName)         { _setup(); _openshift.deploy(envName) }
def nexusDelivery(String envName)           { _setup(); _openshift.deliverToNexus(envName) }

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

void setupJavaVersion() {
    env.JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-17.0.19.0.10-2.el9.x86_64"
    env.PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
}

def section(String text)      { _setup(); new com.bbh.core.StageLogger(this, _state).section(text) }
def startSection(String text) { _setup(); new com.bbh.core.StageLogger(this, _state).startSection(text) }
def endSection(String text)   { _setup(); new com.bbh.core.StageLogger(this, _state).endSection(text) }

def call(Map config = [:]) {
    def dsl        = this
    def sastVulns  = 0
    def dastVulns  = 0

    if (config.projectNames) env.PROJECT_NAMES = config.projectNames

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {

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
                        checkout scm
                        dsl.setupJavaVersion()
                        dsl.initialize()
                    }
                }
                post {
                    always { script { dsl.stageDone('Monitor source changes (download sources)') } }
                    success { script { dsl.stagePass('Monitor source changes (download sources)'); dsl.logStageResult('Monitor source changes (download sources)', 'PASS') } }
                    failure { script { dsl.stageFail('Monitor source changes (download sources)'); dsl.logStageResult('Monitor source changes (download sources)', 'FAIL') } }
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
                    always { script { dsl.stageDone('SAST - Static Application Security Tests - HCL AppScan') } }
                    success { script { dsl.stagePass('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'PASS') } }
                    failure { script { dsl.stageFail('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'FAIL') } }
                    unstable { script { dsl.stageWarn('SAST - Static Application Security Tests - HCL AppScan'); dsl.logStageResult('SAST - Static Application Security Tests - HCL AppScan', 'WARN') } }
                }
            }
        }

        post {
            always {
                script {
                    try { dsl.publishReleaseGate() }
                    catch (Throwable t) { echo "[WARN] Could not write the release gate state: ${t.message}" }
                    try { dsl.generateHtmlReport() }
                    catch (Throwable t) { echo "[WARN] Could not generate HTML report: ${t.message}" }
                    try { dsl.feedInfluxDB("sast") }
                    catch (Throwable t) { echo "[WARN] Could not feed InfluxDB metrics: ${t.message}" }
                    if (!fileExists('report/pipeline-report.html')) {
                        writeFile file: 'report/pipeline-report.html',
                                text: "<html><head><meta charset='UTF-8'><title>Pipeline Report</title></head><body style='font-family:sans-serif;padding:24px'><h2>Pipeline Report</h2><p>Report could not be generated - check build logs.</p></body></html>"
                    }
                    echo "Vulnerabilities: SAST=${sastVulns}, DAST=${dastVulns}"
                    dsl.section('Pipeline finished')
                }
                archiveArtifacts(
                        artifacts:         'report/pipeline-report.html,appscan-report*.html,appscan-dast-report*.html,appscan-dast-report*.pdf',
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
                script { dsl.section('Pipeline completed successfully!') }
            }
            failure {
                script { dsl.section('Pipeline FAILED') }
            }
            unstable {
                script { dsl.section('Pipeline completed with warnings (unstable).') }
            }
        }
    }
}
