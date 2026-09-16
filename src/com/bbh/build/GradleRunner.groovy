package com.bbh.build

import com.bbh.utils.BuildUtils

class GradleRunner implements BuildRunner, Serializable {
    private final def steps
    private final Map cfg

    GradleRunner(def steps, Map cfg) {
        this.steps = steps
        this.cfg = cfg
    }

    @Override
    boolean ifExist() {
        return steps.sh (
            script: '[ -f "./gradlew" ]',
            returnStatus: true
        ) == 0
    }


    private def makeExecutable() {
        return steps.sh (
            script: 'chmod +x gradlew',
            returnStdout: true
        )
    }

    @Override
    def run() {
        String gradle = "./gradlew"
        def tasks = BuildUtils.normalizeTokens(cfg.tasks)
        def flags = BuildUtils.normalizeTokens(cfg.flags)

        if (!tasks || tasks.isEmpty()) {
            steps.error "Gradle Runner: 'tasks' must be provided (e.g. ['clean','build'] or 'clean build')"
        }

        String cmd = ([gradle] + tasks + flags).collect {BuildUtils.shellQuoteIfNeeded(it) }.join(' ')
        String label = (cfg.label ?: "Gradle: ${tasks.join(' ')}") as String
        boolean returnStdout = (cfg.returnStdout ?: false) as boolean

        steps.echo "Execute Gradle Command: ${cmd}"

        makeExecutable()
        Closure body = {
            if (cfg.env instanceof Map && !cfg.env.isEmpty()) {
                def envList = cfg.env.collect { k, v -> "${k}=${v}"}
                steps.withEnv(envList) {
                    return BuildUtils.execSh(steps, cmd, label, returnStdout)
                }
            }
            return BuildUtils.execSh(steps, cmd, label, returnStdout)
        }

        if (cfg.dir) {
            return steps.dir(cfg.dir as String) {
                body()
            }
        }
        return body()
    }

}
