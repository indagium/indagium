package com.indagium

import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationSettingsCodecTest {
    @Test
    fun copyDefaultAndRegexSummaryRoundTripAndKeepWikiDefaultOffForSummary() {
        assertEquals(AnnotationCopyFormat.JIRA_WIKI, AppSettings().annotationCopyFormat)
        assertFalse(AppSettings().showRegexFilterSummary)

        val configured = AppSettings(
            annotationCopyFormat = AnnotationCopyFormat.MARKDOWN,
            showRegexFilterSummary = true,
            annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA,
        )
        val restored = settingsFromJson(configured.settingsJson())!!

        assertEquals(AnnotationCopyFormat.MARKDOWN, restored.annotationCopyFormat)
        assertTrue(restored.showRegexFilterSummary)
        assertEquals(AnnotationLogBlockStyle.JIRA_JAVA, restored.annotationLogBlockStyle)
    }

    @Test
    fun oldSettingsKeepWikiStyleAndNewDefaults() {
        val restored = settingsFromJson("""{"annotationLogBlockStyle":"JIRA_JAVA"}""")!!

        assertEquals(AnnotationLogBlockStyle.JIRA_JAVA, restored.annotationLogBlockStyle)
        assertEquals(AnnotationCopyFormat.JIRA_WIKI, restored.annotationCopyFormat)
        assertFalse(restored.showRegexFilterSummary)
    }

    @Test
    fun invalidStoredCopyFormatFallsBackToWikiButExplicitCloudIsKept() {
        val invalid = settingsFromJson("""{"annotationCopyFormat":"NOPE"}""")!!
        assertEquals(AnnotationCopyFormat.JIRA_WIKI, invalid.annotationCopyFormat)

        val cloud = settingsFromJson(AppSettings(annotationCopyFormat = AnnotationCopyFormat.JIRA_CLOUD).settingsJson())!!
        assertEquals(AnnotationCopyFormat.JIRA_CLOUD, cloud.annotationCopyFormat)
    }
}
