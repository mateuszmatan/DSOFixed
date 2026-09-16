package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine

import static com.bbh.report.utils.HtmlReportConstants.*

class HtmlSecurityReportService extends AbstractHtmlReportService implements Serializable {

    HtmlSecurityReportService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        super(script, state, os, policy, securityAppscanScnrs, securityStageOrder, securitySecStageNames, securityPhaseGroups, securityReportsToParse)
    }

}
