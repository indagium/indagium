package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.LinuxFilePickerMode
import com.indagium.ui.AUTOSAVE_MAGIC_CURRENT
import com.indagium.ui.LinuxFilePickerPropertyResult
import com.indagium.ui.applyLinuxFilePickerMode
import com.indagium.ui.linuxOsReleaseId
import com.indagium.ui.resolvesDisableGtkFileDialogs
import com.indagium.ui.restoredSettingsFromAutosave
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxFilePickerModeTest {
    @Test
    fun automaticResolutionUsesOnlyExactOsReleaseId() {
        assertTrue(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID=debian\nID_LIKE=ubuntu"))
        assertTrue(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID=arch"))
        assertTrue(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID=manjaro"))
        assertTrue(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID=fedora"))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID=ubuntu\nID_LIKE=debian"))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "ID_LIKE=debian"))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, "NAME=Broken\nID"))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, ""))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.AUTOMATIC, null))
    }

    @Test
    fun osReleaseParserReadsQuotedIdAndIgnoresIdLike() {
        assertEquals("fedora", linuxOsReleaseId("NAME=Fedora\nID=\"fedora\"\nID_LIKE=\"rhel fedora\""))
        assertEquals("arch", linuxOsReleaseId("ID='arch'\nID_LIKE=debian"))
        assertEquals(null, linuxOsReleaseId("ID_LIKE=arch"))
    }

    @Test
    fun explicitModesOverrideAutomaticDistributionChoice() {
        val ubuntu = "ID=ubuntu"

        assertTrue(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.COMPATIBILITY_X11, ubuntu))
        assertFalse(resolvesDisableGtkFileDialogs(LinuxFilePickerMode.NATIVE_GTK, "ID=debian"))
    }

    @Test
    fun explicitJvmPropertyWinsWithoutWritingAReplacement() {
        val writes = mutableListOf<Pair<String, String>>()

        val result = applyLinuxFilePickerMode(
            settings = AppSettings(linuxFilePickerMode = LinuxFilePickerMode.COMPATIBILITY_X11),
            osName = "Linux",
            osReleaseReader = { "ID=ubuntu" },
            propertyReader = { "false" },
            propertyWriter = { key, value -> writes += key to value },
        )

        assertEquals(LinuxFilePickerPropertyResult.ExistingJvmOverride, result)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun nonLinuxDoesNotReadOrWriteTheAwtProperty() {
        val writes = mutableListOf<Pair<String, String>>()

        val result = applyLinuxFilePickerMode(
            settings = AppSettings(linuxFilePickerMode = LinuxFilePickerMode.COMPATIBILITY_X11),
            osName = "Mac OS X",
            osReleaseReader = { error("must not read os-release on non-Linux") },
            propertyReader = { error("must not read JVM property on non-Linux") },
            propertyWriter = { key, value -> writes += key to value },
        )

        assertEquals(LinuxFilePickerPropertyResult.NotLinux, result)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun appliesResolvedPropertyOnLinuxWhenNoJvmOverrideExists() {
        val writes = mutableListOf<Pair<String, String>>()

        val result = applyLinuxFilePickerMode(
            settings = AppSettings(linuxFilePickerMode = LinuxFilePickerMode.AUTOMATIC),
            osName = "Linux",
            osReleaseReader = { "ID=debian" },
            propertyReader = { null },
            propertyWriter = { key, value -> writes += key to value },
        )

        assertIs<LinuxFilePickerPropertyResult.Applied>(result)
        assertEquals(true, result.disableGtkFileDialogs)
        assertEquals(listOf("sun.awt.disableGtkFileDialogs" to "true"), writes)
    }

    @Test
    fun filePickerModeRoundTripsThroughJsonAndDefaultsToAutomatic() {
        val saved = AppSettings(linuxFilePickerMode = LinuxFilePickerMode.COMPATIBILITY_X11)
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~"
            else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }

        assertEquals(LinuxFilePickerMode.COMPATIBILITY_X11, settingsFromJson(saved.settingsJson())!!.linuxFilePickerMode)
        assertEquals(LinuxFilePickerMode.AUTOMATIC, settingsFromJson("{}")!!.linuxFilePickerMode)
        assertEquals(LinuxFilePickerMode.AUTOMATIC, settingsFromJson("{\"linuxFilePickerMode\":\"bad\"}")!!.linuxFilePickerMode)
        assertEquals(LinuxFilePickerMode.AUTOMATIC, settingsFromToken(legacyToken)!!.linuxFilePickerMode)
    }

    @Test
    fun startupSettingsReaderRestoresPickerModeBeforeAppStateIsCreated() {
        val cacheFile = File(createTempDirectory("indagium-picker-mode").toFile(), "autosave.cache")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            AppSettings(linuxFilePickerMode = LinuxFilePickerMode.NATIVE_GTK).settingsJson().toByteArray(),
        )
        cacheFile.writeText("$AUTOSAVE_MAGIC_CURRENT\nsettings\t$encoded\ntabs\n")

        assertEquals(LinuxFilePickerMode.NATIVE_GTK, restoredSettingsFromAutosave(cacheFile).linuxFilePickerMode)
    }
}
