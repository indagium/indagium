package com.indagium.utils

import com.indagium.model.AnnotationLogBlockStyle

/**
 * The short, user-facing attribution shared by analysis exports and exported diagram images.
 * Keeping the product text and website in one place prevents Markdown, HTML, and PNG output from
 * slowly acquiring different spellings as each export path evolves.
 */
const val ANALYSIS_ATTRIBUTION_PRODUCT = "Indagium"
const val ANALYSIS_ATTRIBUTION_URL = "https://indagium.com"

/** Plain analysis attribution, useful to callers that need to compare/compose a formatted sink. */
fun analysisAttributionText(): String = "Analyzed with $ANALYSIS_ATTRIBUTION_PRODUCT"

/**
 * Markdown presentation of the analysis attribution. Jira wiki links and ordinary Markdown use
 * different delimiters, so the setting is passed in explicitly at the one shared sink helper.
 */
fun analysisAttributionMarkdown(style: AnnotationLogBlockStyle): String = when (style) {
    AnnotationLogBlockStyle.INDENTED -> "Analyzed with [$ANALYSIS_ATTRIBUTION_PRODUCT]($ANALYSIS_ATTRIBUTION_URL)"
    AnnotationLogBlockStyle.JIRA_JAVA -> "Analyzed with [$ANALYSIS_ATTRIBUTION_PRODUCT|$ANALYSIS_ATTRIBUTION_URL]"
}

/** HTML presentation of the analysis attribution. */
fun analysisAttributionHtml(): String =
    "<hr><p>Analyzed with <a href=\"$ANALYSIS_ATTRIBUTION_URL\">$ANALYSIS_ATTRIBUTION_PRODUCT</a></p>"

/** Exact footer label used by every branded diagram PNG destination. */
fun diagramPngAttributionText(): String = "Created with $ANALYSIS_ATTRIBUTION_PRODUCT · indagium.com"

/** Appends the report footer in the selected Markdown dialect. It is intentionally unconditional:
 *  even an empty analysis should identify the tool that produced the artifact. */
fun StringBuilder.appendAnalysisAttribution(style: AnnotationLogBlockStyle) {
    if (isNotEmpty()) {
        if (last() != '\n') appendLine()
        appendLine()
    }
    appendLine("---")
    appendLine()
    appendLine(analysisAttributionMarkdown(style))
}
