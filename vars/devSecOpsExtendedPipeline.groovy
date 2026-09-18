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
import com.bbh.report.HtmlExtendedReportService

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
@Field HtmlExtendedReportService _report

private void _setup() {
    if (_state != null) return
    _state     = new PipelineState()
    _os        = new OsHelper(this)
    _policy    = new PolicyEngine(this, _state, _os)
    _config    = new ConfigLoader(this, _state)
    _build     = new BuildService(this, _state, _os, _policy)
    _appScan   = new AppScanService(this, _state, _os, _policy, _build)
    _sonar     = new SonarService(this, _state, _os, _build)
    _nexusIq   = new NexusIqService(this, _state, _policy)
    _vmDeploy  = new VmDeployService(this, _state, _os)
    _openshift = new OpenshiftService(this, _state)
    _influx    = new InfluxDbService(this, _state, _os)
    _report    = new HtmlExtendedReportService(this, _state, _os, _policy)
}

def initialize() {
    _setup()
    _os.detect()
    env.OS_TYPE = _os.getType()
    _os.chmodX('gradlew')
    _config.initialize()
}

def appscanSetup()                               { _setup(); _appScan.setup() }

def dastScan() {
    _setup()
    _appScan.dastScan()
    def counts = _state.vulnCounts.dast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def buildArtifact()           { _setup(); _build.buildArtifact() }
def unitTests()               { _setup(); _build.unitTests() }
def checkCoverage() {
    _setup()
    _build.checkCoverage()
}
def reportArtifactBuild()     { _setup(); _build.reportArtifactBuild() }
def reportUnitTests()         { _setup(); _build.reportUnitTests() }

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

def releaseAllowed(String stageName) {
    _setup()
    return new com.bbh.core.ReleaseGate(this, _state).allowed(stageName)
}

def publishReleaseGate() {
    _setup()
    new com.bbh.core.ReleaseGate(this, _state).publish()
}
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

def logStageResult(String stageName, String status) {
    _setup()
    new com.bbh.core.StageLogger(this, _state).logStageResult(stageName, status)
}

def finishStage(String name) { _setup(); new com.bbh.core.StageLogger(this, _state).finish(name) }
def failStage(String name)   { _setup(); new com.bbh.core.StageLogger(this, _state).fail(name) }

def section(String text)      { _setup(); new com.bbh.core.StageLogger(this, _state).section(text) }
def startSection(String text) { _setup(); new com.bbh.core.StageLogger(this, _state).startSection(text) }
def endSection(String text)   { _setup(); new com.bbh.core.StageLogger(this, _state).endSection(text) }

def call(Map config = [:]) {
    def dsl        = this
    def sastVulns  = 0
    def dastVulns  = 0

    if (config.projectNames) env.PROJECT_NAMES = config.projectNames
    if (config.securityPipeline) env.Security_Pipeline = config.securityPipeline

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            booleanParam(
                name:         'DEPLOY_HIGHER_ENV',
                defaultValue: false,
                description:  'Deploy to higher test environment (QC). Allowed only when the library security policy is met'
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
            APPSCAN_HOST       = 'bbh.cloud.appscan.com'
            APPSCAN_SERVER_URL = 'https://bbh.cloud.appscan.com'
            APPSCAN_TOOLS_DIR  = "${WORKSPACE}/.appscan-tools"
            APPSCAN_LOG_DIR    = "${WORKSPACE}/.appscan-logs"
            APPSCAN_HOME_DIR   = "${WORKSPACE}/.appscan-home"
            APPSCAN_BIN_DIR    = "${WORKSPACE}/.appscan-bin"
        }

        stages {
            stage('Monitor source changes (download sources)') {
                steps {
                    script {
                        dsl.stageStart('Monitor source changes (download sources)')
                        copyArtifacts(
                                projectName: config.securityPipeline,
                                filter: 'config.yaml,release-gate.json',
                                selector: lastSuccessful()
                        )
                        dsl.initialize()
                    }
                }
                post {
                    always   { script { dsl.stageDone('Monitor source changes (download sources)') } }
                    success  { script { dsl.finishStage('Monitor source changes (download sources)') } }
                    failure  { script { dsl.failStage('Monitor source changes (download sources)') } }
                    unstable { script { dsl.finishStage('Monitor source changes (download sources)') } }
                }
            }

            stage('Lower test region deployment') {
                steps {
                    script {
                        dsl.stageStart('Lower test region deployment')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            if ((dsl.getCFG().deployTarget ?: 'vm') == 'openshift') {
                                dsl.checkDeploymentRepo()
                                dsl.deployOpenshift('rd')
                            } else {
                                dsl.deployRD()
                            }
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Lower test region deployment') } }
                    success  { script { dsl.finishStage('Lower test region deployment') } }
                    failure  { script { dsl.failStage('Lower test region deployment') } }
                    unstable { script { dsl.finishStage('Lower test region deployment') } }
                }
            }

            stage('Regression tests (>60% user stories coverage)') {
                steps {
                    script {
                        dsl.stageStart('Regression tests (>60% user stories coverage)')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.regressionTests()
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Regression tests (>60% user stories coverage)') } }
                    success  { script { dsl.finishStage('Regression tests (>60% user stories coverage)') } }
                    failure  { script { dsl.failStage('Regression tests (>60% user stories coverage)') } }
                    unstable { script { dsl.finishStage('Regression tests (>60% user stories coverage)') } }
                }
            }

            stage('Smoke tests') {
                steps {
                    script {
                        dsl.stageStart('Smoke tests')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.smokeTests()
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Smoke tests') } }
                    success  { script { dsl.finishStage('Smoke tests') } }
                    failure  { script { dsl.failStage('Smoke tests') } }
                    unstable { script { dsl.finishStage('Smoke tests') } }
                }
            }

            stage('Performance tests') {
                steps {
                    script {
                        dsl.stageStart('Performance tests')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.performanceTests()
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Performance tests') } }
                    success  { script { dsl.finishStage('Performance tests') } }
                    failure  { script { dsl.failStage('Performance tests') } }
                    unstable { script { dsl.finishStage('Performance tests') } }
                }
            }

            stage('DAST - Dynamic Application Security Tests - HCL AppScan') {
                steps {
                    script {
                        dsl.stageStart('DAST - Dynamic Application Security Tests - HCL AppScan')
                        dsl.appscanSetup()
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dastVulns += dsl.dastScan()
                        }
                    }
                }
                post {
                    always   { script { dsl.stageDone('DAST - Dynamic Application Security Tests - HCL AppScan') } }
                    success  { script { dsl.finishStage('DAST - Dynamic Application Security Tests - HCL AppScan') } }
                    failure  { script { dsl.failStage('DAST - Dynamic Application Security Tests - HCL AppScan') } }
                    unstable { script { dsl.finishStage('DAST - Dynamic Application Security Tests - HCL AppScan') } }
                }
            }

            stage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') {
                when { expression { return dsl.releaseAllowed('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                steps {
                    script {
                        dsl.stageStart('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            dsl.publishArtifactQC()
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                    success  { script { dsl.finishStage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                    failure  { script { dsl.failStage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                    unstable { script { dsl.finishStage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                }
            }

            stage('Higher test environment deployment') {
                when { expression { return params.DEPLOY_HIGHER_ENV && dsl.releaseAllowed('Higher test environment deployment') } }
                steps {
                    script {
                        dsl.stageStart('Higher test environment deployment')
                        def projects = dsl.getProjects()
                        for (pName in projects) {
                            dsl.switchProject(pName)
                            if ((dsl.getCFG().deployTarget ?: 'vm') == 'openshift') {
                                dsl.deployOpenshift('qc')
                            } else {
                                dsl.deployQC()
                            }
                        }
                    }
                }
                post {
                    always  { script { dsl.stageDone('Higher test environment deployment') } }
                    success  { script { dsl.finishStage('Higher test environment deployment') } }
                    failure  { script { dsl.failStage('Higher test environment deployment') } }
                    unstable { script { dsl.finishStage('Higher test environment deployment') } }
                }
            }
        }

        post {
            always {
                script {
                    try { dsl.generateHtmlReport() }
                    catch (Throwable t) { echo "[WARN] Could not generate HTML report: ${t.message}" }
                    try { dsl.publishReleaseGate() }
                    catch (Throwable t) { echo "[WARN] Could not write the release gate state: ${t.message}" }
                    try { dsl.feedInfluxDB('extended') }
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
