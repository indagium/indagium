package com.openlog

import com.openlog.ui.AppState
import com.openlog.ui.LICENSE_VERSION
import com.openlog.ui.loadLicenseAgreement
import com.openlog.ui.settingsFromJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseAgreementTest {
    @Test
    fun oldAndMalformedSettingsDoNotGrantLicenseAcceptance() {
        assertEquals(null, settingsFromJson("{}").orEmpty().acceptedLicenseVersion)
        assertEquals(null, settingsFromJson("{\"acceptedLicenseVersion\":null}").orEmpty().acceptedLicenseVersion)

        val state = AppState()
        try {
            assertTrue(state.needsLicenseAcceptance)
        } finally {
            state.close()
        }
    }

    @Test
    fun acceptancePersistsImmediatelyAndVersionChangesRequireItAgain() {
        val cacheFile = File(createTempDirectory("openlog-license").toFile(), "state.cache")
        val state = AppState(autosaveFile = cacheFile)
        try {
            assertTrue(state.needsLicenseAcceptance)
            state.acceptLicenseAgreement()
            assertFalse(state.needsLicenseAcceptance)
        } finally {
            state.close()
        }

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true)
        try {
            assertEquals(LICENSE_VERSION, restored.settings.acceptedLicenseVersion)
            assertFalse(restored.needsLicenseAcceptance)
            restored.updateSettings { it.copy(acceptedLicenseVersion = "older-terms") }
            assertTrue(restored.needsLicenseAcceptance)
        } finally {
            restored.close()
        }
    }

    @Test
    fun packagedAgreementContainsTheRequiredTerms() {
        val agreement = loadLicenseAgreement()

        assertContains(agreement, "PolyForm Perimeter License 1.0.0")
        assertContains(agreement, "Any purpose is a permitted purpose, except for providing to others any product that competes with the software.")
        assertContains(agreement, "Required Notice: Copyright 2026 Roman Arnaut")
        assertContains(agreement, "The openLog name, logo, icon, and branding")
        assertFalse(agreement.contains("PolyForm Noncommercial License"))
        assertFalse(agreement.contains("separate written commercial license"))
    }

    private fun com.openlog.model.AppSettings?.orEmpty() = this ?: com.openlog.model.AppSettings()
}
