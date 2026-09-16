package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine

import static com.bbh.report.utils.HtmlReportConstants.*

class HtmlExtendedReportService extends AbstractHtmlReportService implements Serializable {

    HtmlExtendedReportService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        super(script, state, os, policy, extendedAppscanScnrs, extendedStageOrder, extendedSecStageNames, extendedPhaseGroups, extendedReportsToParse)
    }

}
