package com.bbh.deploy

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState

class VmDeployService implements Serializable {
    private final def         script
    private final PipelineState state
    private final OsHelper    os

    VmDeployService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void deployRd() {
        deployTo('rd', 'rdltaapps1.testbbh.com')
    }

    void deployQc() {
        deployTo('qc', 'qcltaapps1.testbbh.com')
    }

    private void deployTo(String envName, String defaultHost) {
        Map cfg = vmConfig(envName, defaultHost)
        String host = cfg.host as String
        String user = cfg.user as String
        String dir  = cfg.deployDir as String
        String scr  = cfg.deployScript as String
        os.run("ssh -o StrictHostKeyChecking=no ${user}@${host} uptime")
        os.chmodX(scr)
        sshRun(host, user, "mkdir -p ${dir}")
        scpTo("./${scr}", host, user, "${dir}/")
        scpTo("./${cfg.versionFile}", host, user, "${dir}/")
        sshRun(host, user, "${dir}/${scr.tokenize('/').last()}")
    }

    private Map vmConfig(String envName, String defaultHost) {
        def vmCfg = state.cfg.deploy?.vm?.get(envName) ?: [:]
        return [
                host        : vmCfg.host         ?: defaultHost,
                user        : vmCfg.user         ?: 'taadmin',
                deployDir   : vmCfg.deployDir    ?: '/opt/ta/deployment',
                deployScript: vmCfg.deployScript ?: 'scripts/deployment/zero-downtime-deployment.sh',
                versionFile : vmCfg.versionFile  ?: 'scripts/deployment/version.properties'
        ]
    }

    void publishToNexus() {
        def tool = state.cfg.buildTool ?: 'gradle'
        def cmd
        if (os.isWindows()) {
            cmd = (tool == 'maven')
                ? (script.fileExists('mvnw.cmd') ? '.\\mvnw.cmd' : 'mvn')
                : (script.fileExists('gradlew.bat') ? '.\\gradlew.bat' : 'gradle')
        } else {
            cmd = (tool == 'maven')
                ? (script.fileExists('mvnw') ? './mvnw' : 'mvn')
                : (script.fileExists('gradlew') ? './gradlew' : 'gradle')
        }
        switch (tool) {
            case 'gradle': os.run("${cmd} publish"); break
            case 'maven':  os.run("${cmd} deploy");  break
            default:       script.echo "[NEXUS] Publish not configured for build tool: ${tool}"
        }
    }

    void buildDockerImage() {
        def osCfg = state.cfg.deploy?.openshift?.rd ?: [:]
        def cluster       = osCfg.cluster
        def credId        = osCfg.credentialsId
        def projectDeploy = osCfg.projectDeployment
        def appName       = osCfg.appName
        script.openshift.withCluster(cluster, credId) {
            script.openshift.withProject(projectDeploy) {
                if (!script.openshift.selector("bc", appName).exists()) {
                    script.openshift.apply(script.openshift.process(script.readFile('bcConfig.yml'), "-p APP_NAME=${appName}"))
                }
                def build = script.openshift.selector("bc", appName).startBuild("--from-dir=.")
                build.logs("-f")
                script.openshift.selector("deployment", appName).rollout().status()
            }
        }
    }

    void bumpVersion(String versionFile, String newVersion) {
        if (!newVersion) { script.echo "[VERSION] No version specified - skipping."; return }
        if (os.isWindows()) {
            script.powershell "(Get-Content '${versionFile}') -replace 'APP_VERSION=.*', 'APP_VERSION=${newVersion}' | Set-Content '${versionFile}'"
        } else {
            script.sh "sed -i 's/APP_VERSION=.*/APP_VERSION=${newVersion}/' '${versionFile}'"
        }
        script.echo "[VERSION] Bumped to ${newVersion} in ${versionFile}"
    }

    private void sshRun(String host, String user, String remoteCmd) {
        os.run("ssh -o StrictHostKeyChecking=no ${user}@${host} \"${remoteCmd}\"")
    }

    private void scpTo(String localPath, String host, String user, String remotePath) {
        os.run("scp ${localPath} ${user}@${host}:${remotePath}")
    }
}