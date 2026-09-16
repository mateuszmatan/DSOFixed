package com.bbh.build

import com.bbh.utils.BuildUtils

class MavenRunner implements BuildRunner, Serializable{
    private final def steps
    private final Map cfg
    private final mvnPath

    MavenRunner(def steps, Map cfg) {
        this.steps = steps
        this.cfg = cfg
        this.mvnPath = cfg?.mvnPath
    }

    @Override
    boolean ifExist() {
        boolean ifMaven = steps.sh(
                script: """
                    [ -x '${mvnPath}/bin/mvn' ] && ${mvnPath}/bin/mvn -v
                """,
                returnStatus: true
        ) == 0

        if (ifMaven) {
            steps.echo "Maven is installed on this agent"
            steps.sh "${mvnPath}/bin/mvn -version"
        } else {
            steps.echo "Maven is NOT installed on this agent"
        }

        return ifMaven
    }

    @Override
    def run() {
        String maven = "mvn"
        def goals = BuildUtils.normalizeTokens(cfg.goals)
        def flags = BuildUtils.normalizeTokens(cfg.flags)

        if (!goals || goals.isEmpty()) {
            steps.error "MavenRunner: 'goals' must be provided (e.g. ['clean','package'] or 'clean package')"
        }

        if (!mvnPath) {
            steps.error "Maven Runner: 'mvnPath' must be provided"
        }

        String cmd = ([maven] + goals + flags).collect {BuildUtils.shellQuoteIfNeeded(it) }.join(' ') as String
        String label = (cfg.label ?: "Maven: ${goals.join(' ')}") as String
        boolean returnStdout = (cfg.returnStdout ?: false) as boolean

        steps.echo "Execute Maven Command: ${cmd}"
        steps.echo "M2_HOME=${mvnPath}"

        Closure body = {
            List<String> envList = [
                "M2_HOME=${mvnPath}",
                "PATH+MAVEN=${mvnPath}/bin"
            ]

            if (cfg.env instanceof Map && !cfg.env.isEmpty()) {
                envList.addAll(cfg.env.collect { k, v -> "${k}=${v}"} as List<String>)
            }

            steps.withEnv(envList) {
                return BuildUtils.execSh(steps, cmd, label, returnStdout)
            }
        }

        if (cfg.dir) {
            return steps.dir(cfg.dir as String) {
                body()
            }
        }
        return body()
    }
}
