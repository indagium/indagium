package com.indagium

import com.indagium.generated.BuildInfo
import com.indagium.ui.AppState
import com.indagium.ui.LICENSE_VERSION
import com.indagium.ui.loadLicenseAgreement
import com.indagium.ui.loadThirdPartyLicenseEntries
import com.indagium.ui.loadThirdPartyLicenseText
import com.indagium.ui.settingsFromJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val agreement = assertNotNull(loadLicenseAgreement())
        if (BuildInfo.LICENSE_KIND == "eula") {
            assertContains(agreement, "Indagium End User License Agreement")
            assertContains(agreement, "commercial and non-commercial purposes")
            assertContains(agreement, "optional premium functionality")
            assertContains(agreement, "Third-party components are not relicensed under this EULA")
            assertFalse(agreement.contains("## Noncompete"))
            assertFalse(agreement.contains("Required Notice: Copyright 2026 Roman Arnaut"))
        } else {
            assertContains(agreement, "PolyForm Perimeter License 1.0.0")
            assertContains(agreement, "Any purpose is a permitted purpose, except for providing to others any product that competes with the software.")
            assertContains(agreement, "Required Notice: Copyright 2026 Roman Arnaut")
        }
        assertFalse(agreement.contains("PolyForm Noncommercial License"))
        assertFalse(agreement.contains("separate written commercial license"))
    }

    @Test
    fun absentCorruptOrUnreadableAgreementCannotBeAccepted() {
        assertNull(loadLicenseAgreement { null })
        assertNull(loadLicenseAgreement { "Accept this error instead of the agreement" })
        assertNull(loadLicenseAgreement { error("Unreadable resource") })
    }

    @Test
    fun stateCannotPersistAcceptanceWhenTheAgreementResourceIsMissing() {
        val state = AppState()
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        try {
            thread.contextClassLoader = object : ClassLoader(previousLoader) {
                override fun getResourceAsStream(name: String): java.io.InputStream? =
                    if (name == "licenses/indagium-license-agreement.md") null else super.getResourceAsStream(name)
            }
            state.acceptLicenseAgreement()
            assertTrue(state.needsLicenseAcceptance)
            assertNull(state.settings.acceptedLicenseVersion)
        } finally {
            thread.contextClassLoader = previousLoader
            state.close()
        }
    }

    @Test
    fun legacyPolyformAcceptanceDoesNotAcceptTheNewAgreementIdentity() {
        val state = AppState()
        try {
            state.updateSettings { it.copy(acceptedLicenseVersion = "2026-07-19") }
            assertTrue(state.needsLicenseAcceptance)
            assertTrue(LICENSE_VERSION.startsWith("${BuildInfo.LICENSE_KIND}-"))
            assertEquals(64, LICENSE_VERSION.substringAfter(':').length)
        } finally {
            state.close()
        }
    }

    @Test
    fun thirdPartyLicenceViewerLoadsDistinctWrapperAndNativeLicencesOffline() {
        val entries = loadThirdPartyLicenseEntries()
        val wrapper = entries.single { it.id == "zstd-jni" }
        val native = entries.single { it.id == "zstandard-native" }
        val ffmpeg = entries.single { it.id == "ffmpeg" }

        assertEquals("BSD-2-Clause", wrapper.licenseExpression)
        assertEquals("BSD-3-Clause", native.licenseExpression)
        assertTrue(wrapper.licenseFiles.single().contains("zstd-jni-license"))
        assertTrue(native.licenseFiles.single().contains("zstd-license"))
        assertContains(loadThirdPartyLicenseText(ffmpeg), "GNU LESSER GENERAL PUBLIC LICENSE")
        assertContains(loadThirdPartyLicenseText(ffmpeg), "GNU GENERAL PUBLIC LICENSE")
        assertContains(loadThirdPartyLicenseText(native), "Meta Platforms")
        assertTrue(entries.any { it.id == "source-delivery" })
        val notices = entries.single { it.id == "third-party-notices" }
        val whisper = entries.single { it.id == "whisper-cpp" }
        val languageTool = entries.single { it.id == "languagetool" }
        assertContains(loadThirdPartyLicenseText(notices), "OpenAI Whisper model weights")
        assertContains(loadThirdPartyLicenseText(whisper), "MIT License")
        assertContains(loadThirdPartyLicenseText(languageTool), "GNU LESSER GENERAL PUBLIC LICENSE")
    }

    @Test
    fun everyLicenceViewerEntryResolvesItsBundledTexts() {
        val entries = loadThirdPartyLicenseEntries()

        assertEquals(entries.size, entries.map { it.id }.toSet().size, "Licence inventories reuse an id")
        entries.filter { it.licenseFiles.isNotEmpty() }.forEach { entry ->
            assertFalse(loadThirdPartyLicenseText(entry).contains("[Missing bundled legal resource"), entry.id)
        }
        assertFalse(entries.any { it.id == "media-closure" }, "Coverage-only rows stay out of the viewer")
        assertContains(loadThirdPartyLicenseText(entries.single { it.id == "openregex" }), "Version 3, 29 June 2007")
        assertContains(loadThirdPartyLicenseText(entries.single { it.id == "javax-xml-binding" }), "CDDL")
        assertContains(loadThirdPartyLicenseText(entries.single { it.id == "r8" }), "the R8 project authors")
    }

    private fun com.indagium.model.AppSettings?.orEmpty() = this ?: com.indagium.model.AppSettings()
}
