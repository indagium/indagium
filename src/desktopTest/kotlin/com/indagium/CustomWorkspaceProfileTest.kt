package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.CustomWorkspaceProfile
import com.indagium.model.ThemePreset
import com.indagium.ui.AppState
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.customProfilesFromJson
import com.indagium.ui.decodeWorkspaceProfileFile
import com.indagium.ui.describeProfileSpec
import com.indagium.ui.encodeWorkspaceProfileFile
import com.indagium.ui.profileSpecFromJson
import com.indagium.ui.resolved
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.uniqueProfileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomWorkspaceProfileTest {
    private val spec = WorkspaceProfile.LOGCAT_QUERY.spec

    @Test
    fun customProfilesRoundTripThroughSettingsJson() {
        val profiles = listOf(
            CustomWorkspaceProfile("custom-a", "Night shift", spec),
            CustomWorkspaceProfile("custom-b", "Wide", WorkspaceProfile.COMPARE.spec),
        )
        val decoded = settingsFromJson(AppSettings(customWorkspaceProfiles = profiles).settingsJson())!!

        assertEquals(profiles, decoded.customWorkspaceProfiles)
    }

    @Test
    fun missingKeyDecodesToNoCustomProfiles() {
        assertEquals(emptyList(), settingsFromJson("{}")!!.customWorkspaceProfiles)
        assertEquals(emptyList(), settingsFromJson(AppSettings().settingsJson())!!.customWorkspaceProfiles)
    }

    @Test
    fun profileListReadingSkipsBadEntriesAndFixesBadIds() {
        val json = Json.parseToJsonElement(
            """
            [
              {"id":"custom-ok","name":"Fine","spec":{}},
              {"id":"custom-ok","name":"Same id","spec":{}},
              {"id":"classic","name":"Collides with a built-in","spec":{}},
              {"name":"No id","spec":{}},
              {"id":"custom-blank","name":"  ","spec":{}},
              "not an object"
            ]
            """.trimIndent(),
        )

        val read = customProfilesFromJson(json)

        assertEquals(listOf("Fine", "Same id", "Collides with a built-in", "No id"), read.map { it.name })
        assertEquals("custom-ok", read[0].id)
        assertEquals(read.size, read.map { it.id }.toSet().size, "ids must be unique")
        assertTrue(read.all { it.id.startsWith("custom-") })
    }

    @Test
    fun profileFileRoundTrips() {
        val text = encodeWorkspaceProfileFile("Night shift", spec)

        val decoded = decodeWorkspaceProfileFile(text).getOrThrow()

        assertEquals("Night shift", decoded.name)
        assertEquals(spec, decoded.spec)
        assertEquals("indagium-workspace-profile", Json.parseToJsonElement(text).let { (it as JsonObject)["format"].toString().trim('"') })
    }

    @Test
    fun profileFileRejectsWrongFormatNewerVersionAndGarbage() {
        val wrongFormat = decodeWorkspaceProfileFile("""{"format":"something-else","version":1,"name":"x","spec":{}}""")
        assertTrue(wrongFormat.exceptionOrNull()!!.message!!.contains("not an Indagium workspace profile"))

        val newer = decodeWorkspaceProfileFile("""{"format":"indagium-workspace-profile","version":99,"name":"x","spec":{}}""")
        assertTrue(newer.exceptionOrNull()!!.message!!.contains("newer version"))

        val garbage = decodeWorkspaceProfileFile("this is { not json")
        assertTrue(garbage.exceptionOrNull()!!.message!!.contains("not valid JSON"))

        val noSpec = decodeWorkspaceProfileFile("""{"format":"indagium-workspace-profile","version":1,"name":"x"}""")
        assertTrue(noSpec.isFailure)

        val notAnObject = decodeWorkspaceProfileFile("[1,2,3]")
        assertTrue(notAnObject.isFailure)
    }

    @Test
    fun unknownOrBrokenSpecValuesFallBackInsteadOfFailing() {
        val decoded = decodeWorkspaceProfileFile(
            """{"format":"indagium-workspace-profile","version":1,"name":"Odd",
               "spec":{"theme":"FROM_THE_FUTURE","fontSize":"big","showMinimap":"yes","filterBarVisible":true}}""",
        ).getOrThrow()

        val classic = WorkspaceProfile.CLASSIC.spec
        assertEquals(classic.theme, decoded.spec.theme)
        assertEquals(classic.fontSize, decoded.spec.fontSize)
        assertEquals(classic.showMinimap, decoded.spec.showMinimap)
        assertTrue(decoded.spec.filterBarVisible)

        val huge = profileSpecFromJson(Json.parseToJsonElement("""{"fontSize":9000}""").let { it as JsonObject })
        assertEquals(24, huge.fontSize)
    }

    @Test
    fun savingSelectsTheNewProfileWithNoDifferences() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        state.updateSettings { it.copy(theme = ThemePreset.DARK_GITHUB, fontSize = 15) }
        state.updateAnnotationVisible(true)

        val saved = state.saveCurrentAsWorkspaceProfile("Mine")

        assertTrue(saved.id.startsWith("custom-"))
        assertEquals(saved.id, state.settings.workspaceProfileId)
        assertEquals(listOf(saved), state.settings.customWorkspaceProfiles)
        assertEquals(saved.id, state.selectedWorkspaceProfile?.id)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
        assertEquals(ThemePreset.DARK_GITHUB, saved.spec.theme)
        assertEquals(15, saved.spec.fontSize)
        assertTrue(saved.spec.annotationVisible)
    }

    @Test
    fun differencesWorkForCustomProfilesAndResetReappliesThem() {
        val state = AppState()
        val saved = state.saveCurrentAsWorkspaceProfile("Mine")
        state.updateSettings { it.copy(theme = ThemePreset.DRACULA) }
        state.updateFilterVisible(!state.filterVisible)

        assertEquals(listOf("Theme", "Filter sidebar"), state.workspaceProfileDifferences())

        state.applyResolvedProfile(saved.resolved())
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun savedNamesAreMadeUniqueAgainstBuiltInsAndOtherProfiles() {
        val state = AppState()
        val first = state.saveCurrentAsWorkspaceProfile("Focused")
        val second = state.saveCurrentAsWorkspaceProfile("focused")

        assertEquals("Focused (2)", first.name)
        assertEquals("focused (3)", second.name)
        assertEquals("Name (2)", uniqueProfileName("Name", listOf("name")))
        assertEquals("My profile", uniqueProfileName("   ", emptyList()))
    }

    @Test
    fun renameAndDelete() {
        val state = AppState()
        val a = state.saveCurrentAsWorkspaceProfile("A")
        val b = state.saveCurrentAsWorkspaceProfile("B")
        assertEquals(b.id, state.settings.workspaceProfileId)

        state.renameWorkspaceProfile(a.id, "Renamed")
        assertEquals(listOf("Renamed", "B"), state.settings.customWorkspaceProfiles.map { it.name })
        // Renaming to its own current name, or to a blank, keeps things sane.
        state.renameWorkspaceProfile(a.id, "Renamed")
        state.renameWorkspaceProfile(a.id, "   ")
        assertEquals("Renamed", state.settings.customWorkspaceProfiles.first { it.id == a.id }.name)
        // A name that another profile already has is de-duplicated.
        state.renameWorkspaceProfile(a.id, "b")
        assertEquals("b (2)", state.settings.customWorkspaceProfiles.first { it.id == a.id }.name)

        state.deleteWorkspaceProfile(a.id)
        assertEquals(b.id, state.settings.workspaceProfileId, "deleting an unselected profile keeps the selection")

        state.deleteWorkspaceProfile(b.id)
        assertNull(state.settings.workspaceProfileId)
        assertEquals(emptyList(), state.settings.customWorkspaceProfiles)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun updateFromCurrentOverwritesTheSpecAndSelectsIt() {
        val state = AppState()
        val saved = state.saveCurrentAsWorkspaceProfile("Mine")
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)

        state.updateWorkspaceProfileFromCurrent(saved.id)

        assertEquals(WorkspaceProfile.MINIMAL.spec, state.settings.customWorkspaceProfiles.single().spec)
        assertEquals(saved.id, state.settings.workspaceProfileId)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun anUnknownSelectedIdIsTreatedAsNoProfile() {
        val state = AppState()
        state.updateSettings { it.copy(workspaceProfileId = "custom-gone") }
        assertNull(state.selectedWorkspaceProfile)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun importingAddsAppliesAndDeDuplicatesTheName() {
        val dir = createTempDirectory("profile-import").toFile()
        try {
            val file = File(dir, "p.json")
            val state = AppState()
            assertTrue(state.writeWorkspaceProfileFile(file, "Focused", spec).isSuccess)

            val first = state.importWorkspaceProfileFrom(file).getOrThrow()
            val second = state.importWorkspaceProfileFrom(file).getOrThrow()

            assertEquals("Focused (2)", first.name) // clashes with the built-in Focused
            assertEquals("Focused (3)", second.name)
            assertNotEquals(first.id, second.id)
            assertEquals(second.id, state.settings.workspaceProfileId)
            assertEquals(spec.theme, state.settings.theme)
            assertEquals(emptyList(), state.workspaceProfileDifferences())
            assertEquals(2, state.settings.customWorkspaceProfiles.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importingABadFileFailsWithoutChangingAnything() {
        val dir = createTempDirectory("profile-import").toFile()
        try {
            val file = File(dir, "bad.json").apply { writeText("{\"format\":\"nope\"}") }
            val state = AppState()
            val before = state.settings

            val result = state.importWorkspaceProfileFrom(file)

            assertTrue(result.isFailure)
            assertEquals(before, state.settings)
            assertTrue(state.importWorkspaceProfileFrom(File(dir, "missing.json")).isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importingAnOversizedProfileFileFailsWithTheSizeMessage() {
        val dir = createTempDirectory("profile-import").toFile()
        try {
            val file = File(dir, "huge.json")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
            val state = AppState()
            val before = state.settings

            val result = state.importWorkspaceProfileFrom(file)

            assertEquals("huge.json is larger than 8 MB.", result.exceptionOrNull()?.message)
            assertEquals(before, state.settings)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun customCardDescriptionListsTheThemeAndPanels() {
        assertEquals("Dark (GitHub) · filter bar", describeProfileSpec(WorkspaceProfile.LOGCAT_QUERY.spec))
        assertEquals("Graphite Dim · log only", describeProfileSpec(WorkspaceProfile.MINIMAL.spec))
        assertEquals(
            "Warm Paper · filter sidebar · notes · video",
            describeProfileSpec(WorkspaceProfile.CLASSIC.spec),
        )
        assertFalse(describeProfileSpec(WorkspaceProfile.COMPARE.spec).contains("sidebar"))
        assertTrue(describeProfileSpec(WorkspaceProfile.COMPARE.spec).contains("original panel"))
        assertEquals(1, Json.parseToJsonElement("[1]").jsonArray.size)
    }
}
