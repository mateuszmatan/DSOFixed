package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.remediation.updater.GradleUpdater
import com.bbh.remediation.updater.MavenPomUpdater
import com.bbh.remediation.updater.NpmPackageJsonUpdater
import com.bbh.remediation.updater.PipUpdater
import com.bbh.scanner.NexusIqGoldenFixSource
import com.bbh.scm.BitbucketPullRequestPublisher
import com.bbh.scm.GitSourceRepository

class GoldenFixFactory implements Serializable {

    static GoldenFixService create(def script, PipelineState state) {
        return new GoldenFixService(
                script,
                state,
                new NexusIqGoldenFixSource(script),
                new GitSourceRepository(script),
                new BitbucketPullRequestPublisher(script),
                [new MavenPomUpdater(), new GradleUpdater(), new NpmPackageJsonUpdater(), new PipUpdater()]
        )
    }
}
