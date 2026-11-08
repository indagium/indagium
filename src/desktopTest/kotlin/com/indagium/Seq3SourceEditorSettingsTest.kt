package com.indagium

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.toSource
import com.indagium.model.AppSettings
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.seq3IndentSource
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.toggleSeq3SourceAutoIndent
import com.indagium.ui.toggleSeq3SourceAutocomplete
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The source editor's Autocomplete and Auto-indent toggles are app-wide JSON settings. */
class Seq3SourceEditorSettingsTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun newState(
        root: File = createTempDirectory("seq3-editor-settings").toFile().also { roots += it },
        restore: Boolean = false,
    ): AppState =
        AppState(
            File(root, "state.cache"),
            restoreOnCreate = restore,
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(mkTab("log", "sample.log", emptyList()))
            it.activateTab("log")
        }

    @Test
    fun bothTogglesAreOnByDefaultAndRoundTripThroughTheSettingsJson() {
        assertTrue(AppSettings().diagramSourceAutocomplete)
        assertTrue(AppSettings().diagramSourceAutoIndent)

        val restored = settingsFromJson(AppSettings(diagramSourceAutocomplete = false, diagramSourceAutoIndent = true).settingsJson())
        assertEquals(false, restored?.diagramSourceAutocomplete)
        assertEquals(true, restored?.diagramSourceAutoIndent)
        val other = settingsFromJson(AppSettings(diagramSourceAutocomplete = true, diagramSourceAutoIndent = false).settingsJson())
        assertEquals(true, other?.diagramSourceAutocomplete)
        assertEquals(false, other?.diagramSourceAutoIndent)
    }

    @Test
    fun missingKeysKeepTheDefaultOn() {
        assertEquals(true, settingsFromJson("{}")?.diagramSourceAutocomplete)
        assertEquals(true, settingsFromJson("{}")?.diagramSourceAutoIndent)
        assertEquals(true, settingsFromJson("{\"diagramSourceAutoIndent\":\"nonsense\"}")?.diagramSourceAutoIndent)
    }

    @Test
    fun theTogglesSurviveARestart() {
        val root = createTempDirectory("seq3-editor-restart").toFile().also { roots += it }
        val first = newState(root)
        toggleSeq3SourceAutocomplete(first)
        toggleSeq3SourceAutoIndent(first)
        assertFalse(first.settings.diagramSourceAutocomplete)
        assertFalse(first.settings.diagramSourceAutoIndent)
        first.autosaveNow()

        val second = newState(root, restore = true)
        assertFalse(second.settings.diagramSourceAutocomplete, "Autocomplete stays off after a restart")
        assertFalse(second.settings.diagramSourceAutoIndent, "Auto-indent stays off after a restart")
        toggleSeq3SourceAutoIndent(second)
        assertTrue(second.settings.diagramSourceAutoIndent)
    }

    @Test
    fun aNewDiagramAndANewSessionFollowTheSetting() {
        val state = newState()
        toggleSeq3SourceAutocomplete(state)
        val first = requireNotNull(state.seq3Sessions.beginEmpty("log"))
        val second = requireNotNull(state.seq3Sessions.beginEmpty("log"))
        // Nothing is per view any more: both sessions read the one app-wide setting.
        assertFalse(state.settings.diagramSourceAutocomplete)
        assertNotEquals(first, second)
        state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
    }

    @Test
    fun generatedTextIsIndentedOnlyWhileAutoIndentIsOn() {
        val state = newState()
        val id = requireNotNull(state.seq3Sessions.beginEmpty("log"))
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(seq3ActivationTestDocument())))
        val view = requireNotNull(state.seq3Sessions.viewState(id))
        val document = state.seq3Sessions.sessions.single { it.id == id }.document

        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        assertEquals(seq3IndentSource(document.toSource(Seq3Dialect.PLANTUML), Seq3Dialect.PLANTUML), view.sourceDraft)
        assertTrue(view.sourceDraft.lines().any { it.startsWith("    ") })

        // Switching it off re-shows the same diagram without indentation, as one editor edit; the
        // draft is neither dirty nor stale, because the diagram did not change.
        val revision = view.sourceExternalRevision
        val generation = view.sourceEditorGeneration
        state.seq3Sessions.setSourceAutoIndent(false)
        assertEquals(document.toSource(Seq3Dialect.PLANTUML), view.sourceDraft)
        assertFalse(view.sourceDraft.lines().any { it.startsWith(" ") })
        assertEquals(revision + 1, view.sourceExternalRevision, "the mounted editor takes it as one undoable edit")
        assertEquals(generation, view.sourceEditorGeneration, "the editor is not rebuilt")
        assertFalse(view.sourceDraftDirty)
        assertFalse(view.sourceDraftStale)
        assertFalse(state.settings.diagramSourceAutoIndent)

        // And a session opened afterwards seeds its editor flat too.
        val later = requireNotNull(state.seq3Sessions.beginEmpty("log"))
        assertTrue(state.seq3Sessions.applyCommand(later, Seq3Command.ReplaceDocument(seq3ActivationTestDocument())))
        state.seq3Sessions.setDialect(later, Seq3Dialect.PLANTUML)
        assertTrue(state.seq3Sessions.prepareSourceDraft(later))
        val laterView = requireNotNull(state.seq3Sessions.viewState(later))
        assertEquals(laterDocument(state, later).toSource(Seq3Dialect.PLANTUML), laterView.sourceDraft)

        state.seq3Sessions.setSourceAutoIndent(true)
        assertEquals(seq3IndentSource(document.toSource(Seq3Dialect.PLANTUML), Seq3Dialect.PLANTUML), view.sourceDraft)
        state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
    }

    @Test
    fun aDraftWithTypedEditsKeepsItsTextWhenAutoIndentChanges() {
        val state = newState()
        val id = requireNotNull(state.seq3Sessions.beginEmpty("log"))
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(seq3ActivationTestDocument())))
        val view = requireNotNull(state.seq3Sessions.viewState(id))
        assertTrue(state.seq3Sessions.prepareSourceDraft(id))
        val edited = view.sourceDraft.replace("one", "uno")
        state.seq3Sessions.updateSourceDraftText(id, edited)
        assertTrue(view.sourceDraftDirty)

        state.seq3Sessions.setSourceAutoIndent(false)

        assertEquals(edited, view.sourceDraft, "typed text is not touched")
        assertFalse(view.sourceDraftStale, "a formatting-only change is not a diagram change")
        assertTrue(view.sourceDraftDirty)
        state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
    }

    private fun laterDocument(state: AppState, id: String) = state.seq3Sessions.sessions.single { it.id == id }.document
}
