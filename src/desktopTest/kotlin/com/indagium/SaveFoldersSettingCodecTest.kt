package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The save-folder fields (the five folders plus the three AI test folders) (Settings → General → Storage — see AppSettings' own doc comments)
// are JSON-only, appended last, same rule as every other settings-JSON-only field. Shaped after
// HomeRecentsLayoutSettingCodecTest.
class SaveFoldersSettingCodecTest {
    @Test
    fun everySaveFolderFieldRoundTripsThroughKeyedSettingsJson() {
        val settings = AppSettings(
            defaultSaveDir = "/tmp/analysis",
            saveRootDir = "/tmp/root",
            captureSessionsDir = "/tmp/sessions",
            captureSnapshotsDir = "/tmp/snapshots",
            captureZipDir = "/tmp/zips",
            testSuitesDir = "/tmp/test-suites",
            testRunsDir = "/tmp/test-runs",
            testIssuesDir = "/tmp/test-issues",
            lastSaveDialogDir = "/tmp/last",
        )

        val decoded = settingsFromJson(settings.settingsJson())!!

        assertEquals("/tmp/analysis", decoded.defaultSaveDir)
        assertEquals("/tmp/root", decoded.saveRootDir)
        assertEquals("/tmp/sessions", decoded.captureSessionsDir)
        assertEquals("/tmp/snapshots", decoded.captureSnapshotsDir)
        assertEquals("/tmp/zips", decoded.captureZipDir)
        assertEquals("/tmp/test-suites", decoded.testSuitesDir)
        assertEquals("/tmp/test-runs", decoded.testRunsDir)
        assertEquals("/tmp/test-issues", decoded.testIssuesDir)
        assertEquals("/tmp/last", decoded.lastSaveDialogDir)
    }

    @Test
    fun unsetTestFoldersAreNotWrittenAndDecodeBackToNull() {
        val json = AppSettings().settingsJson()

        assertEquals(false, json.contains("testSuitesDir") || json.contains("testRunsDir") || json.contains("testIssuesDir"))
        val decoded = settingsFromJson(json)!!
        assertNull(decoded.testSuitesDir)
        assertNull(decoded.testRunsDir)
        assertNull(decoded.testIssuesDir)
    }

    @Test
    fun absentJsonDecodesEveryNewSaveFolderFieldToNull() {
        // "{}" stands in for a settings blob written before this feature existed — every new field
        // must default to null (unconfigured), not fall back to some computed path, so an old
        // blob's decode stays exactly the "nothing configured" state it always was.
        val decoded = settingsFromJson("{}")!!

        assertNull(decoded.saveRootDir)
        assertNull(decoded.captureSessionsDir)
        assertNull(decoded.captureSnapshotsDir)
        assertNull(decoded.captureZipDir)
        assertNull(decoded.testSuitesDir)
        assertNull(decoded.testRunsDir)
        assertNull(decoded.testIssuesDir)
        assertNull(decoded.lastSaveDialogDir)
    }
}
