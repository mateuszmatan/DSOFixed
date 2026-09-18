package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.cloudbees.groovy.cps.NonCPS

import static com.bbh.report.utils.HtmlReportConstants.*

class HtmlSastReportService extends AbstractHtmlReportService implements Serializable {

    HtmlSastReportService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        super(script, state, os, policy, sastAppscanScnrs, sastStageOrder, sastSecStageNames, sastPhaseGroups, sastReportsToParse)
    }

    @NonCPS
    @Override
    protected boolean showScannerSummaryRows() {
        return false
    }
}
