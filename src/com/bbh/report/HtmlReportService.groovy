package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine

import static com.bbh.report.utils.HtmlReportConstants.*

class HtmlReportService extends AbstractHtmlReportService implements Serializable {

    HtmlReportService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        super(script, state, os, policy, appscanScnrs, stageOrder, secStageNames, phaseGroups, reportsToParse)

    }

}
