package com.indagium

import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.AppSettings
import com.indagium.model.RecordFieldNameColor
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
        val teams = settingsFromJson(AppSettings(annotationCopyFormat = AnnotationCopyFormat.TEAMS).settingsJson())!!
        assertEquals(AnnotationCopyFormat.TEAMS, teams.annotationCopyFormat)
    }

    @Test
    fun recordFieldNameColourDefaultsToAccentAndRoundTrips() {
        assertEquals(RecordFieldNameColor.ACCENT, AppSettings().recordFieldNameColor)
        assertEquals(null, AppSettings().recordFieldNameCustomArgb)

        val custom = AppSettings(recordFieldNameColor = RecordFieldNameColor.CUSTOM, recordFieldNameCustomArgb = 0xFF06B6D4L)
        val restoredCustom = settingsFromJson(custom.settingsJson())!!
        assertEquals(RecordFieldNameColor.CUSTOM, restoredCustom.recordFieldNameColor)
        assertEquals(0xFF06B6D4L, restoredCustom.recordFieldNameCustomArgb)

        val plain = settingsFromJson(AppSettings(recordFieldNameColor = RecordFieldNameColor.PLAIN).settingsJson())!!
        assertEquals(RecordFieldNameColor.PLAIN, plain.recordFieldNameColor)
        assertEquals(null, plain.recordFieldNameCustomArgb)
    }

    @Test
    fun oldSettingsWithoutTheFieldNameKeysLoadAsAccentAndAnUnknownValueFallsBack() {
        val old = settingsFromJson("""{"annotationLogBlockStyle":"JIRA_JAVA"}""")!!
        assertEquals(RecordFieldNameColor.ACCENT, old.recordFieldNameColor)
        assertEquals(null, old.recordFieldNameCustomArgb)

        val unknown = settingsFromJson("""{"recordFieldNameColor":"NEON"}""")!!
        assertEquals(RecordFieldNameColor.ACCENT, unknown.recordFieldNameColor)
    }
}
