package com.bbh.report.utils

enum BuildResult {

    IN_PROGRESS('IN_PROGRESS'),
    SUCCESS('SUCCESS'),
    FAILURE('FAILURE'),
    UNSTABLE('UNSTABLE')

    BuildResult(String result) {
        this.result = result
    }

    String result
}
