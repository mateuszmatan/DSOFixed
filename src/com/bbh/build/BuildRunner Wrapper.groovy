package com.bbh.build

class BuildRunnerWrapper implements BuildRunner, Serializable{

    private final BuildRunner runner

    BuildRunnerWrapper(def steps, Map cfg, String tool) {
        if (tool == 'gradle')
            runner = new GradleRunner(steps, cfg?.gradle as Map)
        else if (tool == 'maven')
            runner = new MavenRunner(steps, cfg?.maven as Map)

        if (!runner)
            steps.error "Build Tool is not specified in configuration"
    }

    @Override
    boolean ifExist() {
        return runner.ifExist()
    }

    @Override
    def run() {
        return runner.run()
    }
}
