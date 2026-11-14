package com.snagar.easynotes

import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.util.NlsActions
import com.intellij.util.Consumer
import java.awt.Component

class MyErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): @NlsActions.ActionText String = "Submit Error Report"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>
    ): Boolean {
        // For now, just indicate successful reporting to JetBrains
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }
}
