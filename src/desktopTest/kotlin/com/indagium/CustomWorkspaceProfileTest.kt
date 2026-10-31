package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.CustomWorkspaceProfile
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewSectionId
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.ThemePreset
import com.indagium.ui.AppState
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.ProfilePanels
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.activeLogViewLayoutRef
import com.indagium.ui.applyLogViewPreset
import com.indagium.ui.classicLogViewLayout
import com.indagium.ui.customProfilesFromJson
import com.indagium.ui.decodeWorkspaceProfileFile
import com.indagium.ui.describeProfileSpec
import com.indagium.ui.encodeWorkspaceProfileFile
import com.indagium.ui.hideLogViewTile
import com.indagium.ui.matchesCustomLogViewLayout
import com.indagium.ui.profilePanelsOf
import com.indagium.ui.profileSpecFromJson
import com.indagium.ui.profileSpecToJson
import com.indagium.ui.resolved
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.uniqueProfileName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
        assertNull(decoded.spec.layout, "the old panel flags are ignored: the layout is whatever is on screen")

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
        assertNull(saved.spec.layout, "Notes was switched on after Minimal's Log focus, so the preset reads as modified and none is stored")

        assertTrue(saved.id.startsWith("custom-"))
        assertEquals(saved.id, state.settings.workspaceProfileId)
        assertEquals(listOf(saved), state.settings.customWorkspaceProfiles)
        assertEquals(saved.id, state.selectedWorkspaceProfile?.id)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
        assertEquals(ThemePreset.DARK_GITHUB, saved.spec.theme)
        assertEquals(15, saved.spec.fontSize)
    }

    @Test
    fun differencesWorkForCustomProfilesAndResetReappliesThem() {
        val state = AppState()
        val saved = state.saveCurrentAsWorkspaceProfile("Mine")
        state.updateSettings { it.copy(theme = ThemePreset.DRACULA) }
        state.updateFilterVisible(!state.filterVisible)

        assertEquals(listOf("Theme"), state.workspaceProfileDifferences(), "a panel toggle is not a profile difference")

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
    fun cardDescriptionListsThemeSizeAndLayout() {
        assertEquals("Dark (GitHub) · 12px · Layout: Filter bar", describeProfileSpec(WorkspaceProfile.LOGCAT_QUERY.spec))
        assertEquals("Graphite Dim · 11px · Layout: Log focus", describeProfileSpec(WorkspaceProfile.MINIMAL.spec))
        assertEquals("Warm Paper · 12px · Layout: Classic", describeProfileSpec(WorkspaceProfile.CLASSIC.spec))
        assertEquals("Warm Paper · 12px · Layout: Keep current layout", describeProfileSpec(WorkspaceProfile.CLASSIC.spec.copy(layout = null)))
        assertEquals(describeProfileSpec(WorkspaceProfile.FOCUSED.spec), WorkspaceProfile.FOCUSED.resolved().description)

        val saved = layout("custom-log-view-a", "Triage")
        val pointing = WorkspaceProfile.CLASSIC.spec.copy(layout = ProfileLayoutRef.Saved(saved.id))
        assertEquals("Warm Paper · 12px · Layout: Triage", describeProfileSpec(pointing, listOf(saved)))
        assertEquals("Warm Paper · 12px · Layout: Keep current layout", describeProfileSpec(pointing), "a missing saved layout reads as no layout")
        assertEquals(1, Json.parseToJsonElement("[1]").jsonArray.size)
    }

    // ---- layout reference ------------------------------------------------------------------------------------------

    @Test
    fun theLayoutReferenceRoundTripsThroughTheProfileFileAndSettings() {
        val saved = ProfileLayoutRef.Saved("custom-log-view-xyz")
        listOf(ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR), ProfileLayoutRef.Preset(LogViewPresetId.CLASSIC), saved, null).forEach { ref ->
            val withLayout = spec.copy(layout = ref)
            assertEquals(withLayout, decodeWorkspaceProfileFile(encodeWorkspaceProfileFile("N", withLayout)).getOrThrow().spec, "$ref")
            val profile = CustomWorkspaceProfile("custom-a", "A", withLayout)
            val decoded = settingsFromJson(AppSettings(customWorkspaceProfiles = listOf(profile)).settingsJson())!!
            assertEquals(listOf(profile), decoded.customWorkspaceProfiles, "$ref")
        }
        val written = profileSpecToJson(spec.copy(layout = ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR)))
        assertEquals("preset:FILTER_BAR", written["layout"]!!.jsonPrimitive.content)
        assertEquals("saved:custom-log-view-xyz", profileSpecToJson(spec.copy(layout = saved))["layout"]!!.jsonPrimitive.content)
        assertNull(profileSpecToJson(spec.copy(layout = null))["layout"], "no layout writes no key")
    }

    @Test
    fun theFivePanelFlagsAreNoLongerWritten() {
        val written = profileSpecToJson(WorkspaceProfile.CLASSIC.spec)
        listOf("filterVisible", "filterBarVisible", "annotationVisible", "videoPanelVisible", "aiPanelVisible").forEach {
            assertFalse(it in written, "$it must not be written")
        }
    }

    @Test
    fun oldJsonWithThePanelFlagsDecodesWithNoLayoutAndKeepsTheLook() {
        val old = Json.parseToJsonElement(
            """{"theme":"DRACULA","fontSize":15,"fontMono":false,"logFontFamily":"Menlo","showMinimap":false,
               "toolbarIconOnlyButtons":false,"openNewFilesWithUnfiltered":true,
               "filterVisible":true,"filterBarVisible":true,"annotationVisible":true,"videoPanelVisible":true,"aiPanelVisible":true}""",
        ) as JsonObject

        val decoded = profileSpecFromJson(old)

        assertNull(decoded.layout)
        assertEquals(ThemePreset.DRACULA, decoded.theme)
        assertEquals(15, decoded.fontSize)
        assertFalse(decoded.fontMono)
        assertEquals("Menlo", decoded.logFontFamily)
        assertFalse(decoded.showMinimap)
        assertFalse(decoded.toolbarIconOnlyButtons)
        assertTrue(decoded.openNewFilesWithUnfiltered)

        val file = decodeWorkspaceProfileFile("""{"format":"indagium-workspace-profile","version":1,"name":"Old","spec":$old}""").getOrThrow()
        assertNull(file.spec.layout)
        assertEquals(ThemePreset.DRACULA, file.spec.theme)
    }

    @Test
    fun anUnknownOrMalformedLayoutReferenceDecodesToNull() {
        listOf("preset:FROM_THE_FUTURE", "preset:COMPARE_GRID", "preset:", "saved:", "saved:   ", "nonsense", "").forEach { text ->
            val o = Json.parseToJsonElement("""{"layout":${Json.encodeToString(String.serializer(), text)}}""") as JsonObject
            assertNull(profileSpecFromJson(o).layout, "'$text'")
        }
        assertNull(profileSpecFromJson(Json.parseToJsonElement("""{"layout":7}""") as JsonObject).layout)
        assertEquals(
            ProfileLayoutRef.Preset(LogViewPresetId.REVIEW),
            profileSpecFromJson(Json.parseToJsonElement("""{"layout":"preset:REVIEW"}""") as JsonObject).layout,
            "a legacy single-view preset is still a valid reference",
        )
    }

    private fun layout(id: String, name: String) = CustomLogViewLayout(
        id = id, name = name, tree = classicLogViewLayout(LogViewLegacySizes()),
        filterPanelWidth = 220f, annotationPanelWidth = 360f, rightSidebarSplit = 0.5f,
        hidden = emptyList(), collapsed = emptyList(), tilesLocked = false,
        filterVisible = true, annotationVisible = true, aiPanelVisible = false, videoPanelVisible = true,
    )

    @Test
    fun applyingAProfileWithoutALayoutLeavesTheLayoutAndSavedSelectionAlone() {
        val state = AppState()
        state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)
        val saved = state.saveCurrentAsLogViewLayout("Mine")
        val before = state.settings

        state.applyResolvedProfile(WorkspaceProfile.MINIMAL.resolved().copy(spec = WorkspaceProfile.MINIMAL.spec.copy(layout = null)))

        assertEquals(before.logViewLayout, state.settings.logViewLayout)
        assertEquals(before.logViewHidden, state.settings.logViewHidden)
        assertEquals(saved.id, state.settings.lastCustomLogViewLayoutId)
        assertTrue(state.matchesCustomLogViewLayout(saved), "the saved layout is still exactly what is on screen")
    }

    @Test
    fun applyingAPresetProfileDoesNotMarkAnUnrelatedSavedLayoutModified() {
        val state = AppState()
        val saved = state.saveCurrentAsLogViewLayout("Mine")
        state.applyLogViewPreset(LogViewLayoutPreset.CLASSIC)
        assertNull(state.settings.lastCustomLogViewLayoutId, "choosing a preset leaves the saved layout unselected")

        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)

        assertNull(state.settings.lastCustomLogViewLayoutId, "no saved layout is selected, so none can read as Modified")
        assertEquals(listOf(saved), state.settings.customLogViewLayouts, "the saved layout itself is untouched")
    }

    @Test
    fun applyingAProfileThatPointsAtASavedLayoutAppliesThatLayout() {
        val state = AppState()
        state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)
        val saved = state.saveCurrentAsLogViewLayout("Triage")
        state.applyLogViewPreset(LogViewLayoutPreset.LOG_FOCUS)
        val profile = state.saveCurrentAsWorkspaceProfile("Pointing")
        state.updateSettings { s ->
            s.copy(customWorkspaceProfiles = s.customWorkspaceProfiles.map { it.copy(spec = it.spec.copy(layout = ProfileLayoutRef.Saved(saved.id))) })
        }

        state.applyResolvedProfile(state.resolveWorkspaceProfile(profile.id)!!)

        assertEquals(saved.id, state.settings.lastCustomLogViewLayoutId)
        assertTrue(state.matchesCustomLogViewLayout(saved))
        assertEquals(ProfileLayoutRef.Saved(saved.id), state.activeLogViewLayoutRef())
    }

    @Test
    fun aMissingSavedLayoutBehavesLikeKeepCurrentLayoutSilently() {
        val state = AppState()
        state.applyLogViewPreset(LogViewLayoutPreset.FILTERING)
        val before = state.settings.logViewLayout
        val profile = CustomWorkspaceProfile("custom-x", "X", spec.copy(layout = ProfileLayoutRef.Saved("custom-log-view-gone")))

        state.applyResolvedProfile(profile.resolved())

        assertEquals(before, state.settings.logViewLayout)
        assertEquals(LogViewPresetId.FILTERING, state.settings.lastLogViewPreset)
        assertEquals(spec.theme, state.settings.theme, "the look is still applied")
    }

    @Test
    fun saveAsNewProfileStoresTheActiveSavedLayoutElseThePresetElseNull() {
        val state = AppState()
        state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)
        assertEquals(ProfileLayoutRef.Preset(LogViewPresetId.ANALYSIS), state.saveCurrentAsWorkspaceProfile("P").spec.layout)

        val saved = state.saveCurrentAsLogViewLayout("Mine")
        assertEquals(ProfileLayoutRef.Saved(saved.id), state.saveCurrentAsWorkspaceProfile("S").spec.layout)

        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertNull(state.activeLogViewLayoutRef(), "a hand-edited layout matches nothing")
        assertNull(state.saveCurrentAsWorkspaceProfile("N").spec.layout)
    }

    @Test
    fun aCustomProfilesLayoutCanBeChanged() {
        val state = AppState()
        val saved = state.saveCurrentAsWorkspaceProfile("Mine")

        state.setWorkspaceProfileLayout(saved.id, ProfileLayoutRef.Preset(LogViewPresetId.ANALYSIS))
        assertEquals(ProfileLayoutRef.Preset(LogViewPresetId.ANALYSIS), state.settings.customWorkspaceProfiles.single().spec.layout)
        state.setWorkspaceProfileLayout(saved.id, null)
        assertNull(state.settings.customWorkspaceProfiles.single().spec.layout)
    }

    @Test
    fun profilePanelsFollowTheReferencedLayout() {
        assertEquals(ProfilePanels(filter = true, notes = true, video = true), profilePanelsOf(ProfileLayoutRef.Preset(LogViewPresetId.CLASSIC), emptyList()))
        assertEquals(ProfilePanels(filterBar = true, notes = true), profilePanelsOf(ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR), emptyList()))
        assertEquals(ProfilePanels(), profilePanelsOf(ProfileLayoutRef.Preset(LogViewPresetId.LOG_FOCUS), emptyList()))
        val saved = layout("custom-log-view-a", "Triage").copy(filterVisible = false, filterBarVisible = true)
        assertEquals(ProfilePanels(filterBar = true, notes = true, video = true), profilePanelsOf(ProfileLayoutRef.Saved(saved.id), listOf(saved)))
        assertNull(profilePanelsOf(null, emptyList()))
        assertNull(profilePanelsOf(ProfileLayoutRef.Saved("gone"), listOf(saved)))
    }
}
