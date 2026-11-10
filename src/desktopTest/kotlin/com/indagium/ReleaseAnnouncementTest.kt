package com.indagium

import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.ui.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseAnnouncementTest {
    private val metadata = """
        # One announcement belongs to the release that actually introduces it.
        after 1.9.0 PUMPKIN_PATCH HAUNTED_MANOR TRICK_OR_TREAT
        version 1.10.0 HAUNTED_MANOR INVALID_PACK
    """.trimIndent()

    @Test fun pagesUseTheReleaseSectionsAndKeepMarkdown() {
        val pages = whatsNewPages("# Indagium 2.0\n\nIntroduction.\n\n## One\n- **First**\n\n## Two\nSecond")
        assertEquals(listOf("Highlights", "One", "Two"), pages.map { it.title })
        assertEquals("- **First**", pages[1].markdown)
        assertTrue(whatsNewPages("# Indagium 2.0\n").isEmpty())
    }

    @Test fun themesBelongOnlyToTheActualIntroducingRelease() {
        val versions = listOf("1.9.0", "1.9.1", "1.9.2", "1.10.0")
        assertTrue(releasedHolidayThemes("1.9.0", versions, metadata).isEmpty())
        assertEquals(
            listOf(HolidayPack.PUMPKIN_PATCH, HolidayPack.HAUNTED_MANOR, HolidayPack.TRICK_OR_TREAT),
            releasedHolidayThemes("1.9.1", versions, metadata),
        )
        assertTrue(releasedHolidayThemes("1.9.2", versions, metadata).isEmpty())
        assertEquals(listOf(HolidayPack.HAUNTED_MANOR), releasedHolidayThemes("1.10.0", versions, metadata))
        // The as-yet-unwritten next release resolves correctly without assuming its version number.
        assertEquals(3, releasedHolidayThemes("2.0.0", listOf("1.9.0"), metadata).size)
    }

    @Test fun olderNotesStayHistoricalAndCurrentReleaseNotesLoadOffline() {
        assertNull(ReleaseAnnouncement::class.java.getResource("/1.9.0.md"), "historical release notes are not bundled")
        val release = loadReleaseAnnouncement("1.9.0")
        assertTrue(release.pages.isEmpty(), "already-released notes are never presented as new")
        assertTrue(release.themes.isEmpty(), "the Halloween packs belong to the next release")
        assertNull(initialReleaseStep(release, "", ""), "a fresh install does not show historical announcements")
        val next = loadReleaseAnnouncement()
        assertEquals("1.9.2", next.version)
        assertTrue(next.pages.isNotEmpty(), "the new release bundles its real notes")
        assertTrue(next.themes.isEmpty(), "the special themes were announced in 1.9.1")
        val current = loadReleaseAnnouncement("1.9.1", { name ->
            when (name) {
                "whats-new-start.txt" -> "1.9.0"
                "1.9.1.md" -> "# Indagium 1.9.1\n\n## A new feature\nDescription."
                else -> null
            }
        }, listOf("1.9.0", "1.9.1"))
        assertEquals(listOf("A new feature"), current.pages.map { it.title })
        assertEquals("Description.", current.pages.single().markdown)
        val later = loadReleaseAnnouncement("1.9.2", releaseVersions = listOf("1.9.0", "1.9.1", "1.9.2"))
        assertTrue(later.themes.isEmpty())
        var read = false
        val invalid = loadReleaseAnnouncement("../secret", { read = true; null }, emptyList())
        assertFalse(read)
        assertTrue(invalid.pages.isEmpty() && invalid.themes.isEmpty())
    }

    @Test fun acknowledgementPersistsAndTheTwoDialogsAreIndependent() {
        val dir = createTempDirectory("release-announcement").toFile()
        val state = AppState(
            autosaveFile = File(dir, "cache"), notesDir = File(dir, "notes"), archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"), autoExportNotes = false,
        )
        try {
            val release = ReleaseAnnouncement("1.9.1", listOf(WhatsNewPage("One", "First"), WhatsNewPage("Two", "Second")), listOf(HolidayPack.TRICK_OR_TREAT))
            state.maybeShowReleaseAnnouncementOnStartup(release)
            assertEquals(ReleaseAnnouncementStep.WHATS_NEW, state.releaseAnnouncementStep)
            state.releaseAnnouncementNext()
            assertEquals(1, state.releaseAnnouncementPage)
            state.releaseAnnouncementPrevious()
            assertEquals(0, state.releaseAnnouncementPage)
            state.finishWhatsNew()
            assertEquals(ReleaseAnnouncementStep.HOLIDAY_THEMES, state.releaseAnnouncementStep)
            assertEquals("1.9.1", state.settings.lastSeenWhatsNewVersion)
            assertEquals("", state.settings.lastSeenHolidayAnnouncementVersion)
            val persisted = settingsFromJson(state.settings.settingsJson())!!
            assertEquals(
                ReleaseAnnouncementStep.HOLIDAY_THEMES,
                initialReleaseStep(release, persisted.lastSeenWhatsNewVersion, persisted.lastSeenHolidayAnnouncementVersion),
            )
            state.previewAnnouncedTheme(HolidayPack.TRICK_OR_TREAT, dark = true)
            assertEquals(HolidayLightness.DARK, state.settingsForReleaseThemePreview().holiday.lightness)
            assertEquals(HolidayLightness.FOLLOW_PRESET, state.settings.holiday.lightness, "a preview never writes the settings")
            state.finishHolidayAnnouncement(HolidayPack.TRICK_OR_TREAT, HolidayLightness.DARK)
            assertEquals(HolidayPack.TRICK_OR_TREAT, state.settings.holiday.pack)
            assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
            assertEquals(HolidayLightness.DARK, state.settings.holiday.lightness)
            assertEquals("1.9.1", state.settings.lastSeenHolidayAnnouncementVersion)
            assertNull(state.releaseAnnouncementStep)
            state.maybeShowReleaseAnnouncementOnStartup(release)
            assertNull(state.releaseAnnouncementStep)
            val newer = release.copy(version = "1.9.2", themes = emptyList())
            state.maybeShowReleaseAnnouncementOnStartup(newer)
            assertEquals(ReleaseAnnouncementStep.WHATS_NEW, state.releaseAnnouncementStep)
            state.finishWhatsNew()
            assertNull(state.releaseAnnouncementStep, "a release without new themes offers no theme dialog")
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test fun previewUsesTheBundledReleaseNotesAndThemes() {
        val release = loadReleaseAnnouncement()
        val preview = releaseAnnouncementPreview()
        assertTrue(preview.preview)
        assertEquals(release.version, preview.version)
        assertEquals(release.pages, preview.pages)
        assertTrue(preview.themes.isNotEmpty(), "preview offers theme examples when this release has none")
        assertFalse(preview.pages.any { "This is a preview" in it.markdown })
    }

    @Test fun previewFallsBackToExamplesWhenNoReleaseNotesExist() {
        val preview = releaseAnnouncementPreview(ReleaseAnnouncement("2.0.0", emptyList(), emptyList()))
        assertEquals(listOf("Preview", "Browsing changes"), preview.pages.map { it.title })
        assertTrue(preview.preview)
        assertTrue(preview.themes.isNotEmpty())
    }

    @Test fun skippingThemesKeepsTheUsersCurrentSelectionAndDowngradesDoNotRepeat() {
        val dir = createTempDirectory("release-skip").toFile()
        val state = AppState(
            autosaveFile = File(dir, "cache"), notesDir = File(dir, "notes"), archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"), autoExportNotes = false,
        )
        try {
            val before = state.settings.holiday.copy(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.HAUNTED_MANOR)
            state.updateSettings { it.copy(holiday = before) }
            val release = ReleaseAnnouncement("1.9.1", emptyList(), listOf(HolidayPack.TRICK_OR_TREAT))
            state.maybeShowReleaseAnnouncementOnStartup(release)
            assertNotNull(state.releaseAnnouncementStep)
            state.finishHolidayAnnouncement()
            assertEquals(before, state.settings.holiday)
            assertNull(initialReleaseStep(release, "2.0.0", "2.0.0"))
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test fun restartBetweenTheTwoDialogsResumesOnlyTheThemeAnnouncement() {
        val dir = createTempDirectory("release-restart").toFile()
        val cache = File(dir, "cache")
        val release = ReleaseAnnouncement("1.9.1", listOf(WhatsNewPage("New", "Changes")), listOf(HolidayPack.TRICK_OR_TREAT))

        fun app(restore: Boolean) = AppState(
            autosaveFile = cache, restoreOnCreate = restore, notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"), customCommandsDir = File(dir, "commands"), autoExportNotes = false,
        )
        try {
            val first = app(false)
            first.maybeShowReleaseAnnouncementOnStartup(release)
            first.finishWhatsNew()
            first.autosaveNow()
            first.close()
            val second = app(true)
            try {
                second.maybeShowReleaseAnnouncementOnStartup(release)
                assertEquals(ReleaseAnnouncementStep.HOLIDAY_THEMES, second.releaseAnnouncementStep)
                second.finishHolidayAnnouncement()
            } finally {
                second.close()
            }
            val third = app(true)
            try {
                third.maybeShowReleaseAnnouncementOnStartup(release)
                assertNull(third.releaseAnnouncementStep)
            } finally {
                third.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun developmentPreviewNeverAcknowledgesARealRelease() {
        val dir = createTempDirectory("release-preview").toFile()
        val state = AppState(
            autosaveFile = File(dir, "cache"), notesDir = File(dir, "notes"), archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"), autoExportNotes = false,
        )
        try {
            state.updateSettings { it.copy(lastSeenWhatsNewVersion = "2.0.0", lastSeenHolidayAnnouncementVersion = "2.0.0") }
            state.previewReleaseAnnouncement()
            assertEquals(ReleaseAnnouncementStep.WHATS_NEW, state.releaseAnnouncementStep)
            assertTrue(state.releaseAnnouncement!!.preview)
            state.releaseAnnouncementNext()
            assertEquals(1, state.releaseAnnouncementPage)
            state.finishWhatsNew()
            assertEquals(ReleaseAnnouncementStep.HOLIDAY_THEMES, state.releaseAnnouncementStep)
            state.finishHolidayAnnouncement(HolidayPack.TRICK_OR_TREAT)
            assertEquals("2.0.0", state.settings.lastSeenWhatsNewVersion)
            assertEquals("2.0.0", state.settings.lastSeenHolidayAnnouncementVersion)
            assertEquals(HolidayPack.TRICK_OR_TREAT, state.settings.holiday.pack)
            state.previewReleaseAnnouncement()
            state.finishWhatsNew()
            state.finishHolidayAnnouncement()
            assertEquals("2.0.0", state.settings.lastSeenHolidayAnnouncementVersion)
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }
}
