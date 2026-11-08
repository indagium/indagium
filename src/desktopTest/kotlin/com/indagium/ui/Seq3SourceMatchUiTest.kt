package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Dialect
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** The opener / closer highlight of the source editor follows the caret and names what it marks. */
class Seq3SourceMatchUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private val optSource = "@startuml\nloop outer\n    opt condition\n        A -> B: hi\n    end\nend\n@enduml"

    private val acrossSource = "sequenceDiagram\n    activate Alias_2\n        Alias->>Alias_2: m\n" +
        "        opt condition\n            deactivate Alias_2\n        end"

    @Test fun theCaretOnOptMarksOptAndItsEndAndTheCaretOnEndMarksThemBack() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        editor.performTextReplacement(optSource)
        val opt = optSource.indexOf("opt")
        val innerEnd = optSource.indexOf("    end\n") + 4
        editor.performTextInputSelection(TextRange(opt + 1))
        assertEquals(listOf(shown(optSource, opt, "opt"), shown(optSource, innerEnd, "end")), marks())
        editor.performTextInputSelection(TextRange(innerEnd + 3))
        assertEquals(listOf(shown(optSource, opt, "opt"), shown(optSource, innerEnd, "end")), marks(), "the caret at the end edge of end")
    }

    @Test fun theMarksMoveWithTheCaretAndGoWhenItLeavesAKeyword() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        editor.performTextReplacement(optSource)
        val loop = optSource.indexOf("loop")
        val outerEnd = optSource.indexOf("\nend\n@enduml") + 1
        editor.performTextInputSelection(TextRange(loop))
        assertEquals(listOf(shown(optSource, loop, "loop"), shown(optSource, outerEnd, "end")), marks())
        editor.performTextInputSelection(TextRange(optSource.indexOf("A -> B") + 2))
        assertEquals(emptyList(), marks(), "on a message line")
        editor.performTextInputSelection(TextRange(optSource.indexOf("opt") + 1))
        assertEquals(2, marks().size)
        editor.performTextInputSelection(TextRange(optSource.indexOf("opt"), optSource.indexOf("opt") + 3))
        assertEquals(emptyList(), marks(), "a selection shows nothing")
    }

    @Test fun anActivateAndItsDeactivateAcrossAnOptAreMarkedInBothDialects() {
        val editor = openEditor(Seq3Dialect.MERMAID)
        editor.performTextReplacement(acrossSource)
        val activate = acrossSource.indexOf("activate Alias_2")
        val deactivate = acrossSource.indexOf("deactivate Alias_2")
        val expected = listOf(shown(acrossSource, activate, "activate Alias_2"), shown(acrossSource, deactivate, "deactivate Alias_2"))
        editor.performTextInputSelection(TextRange(activate + 6))
        assertEquals(expected, marks())
        editor.performTextInputSelection(TextRange(deactivate + 14))
        assertEquals(expected, marks())
    }

    @Test fun anUnmatchedConstructIsMarkedAsUnbalanced() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        val source = "@startuml\nloop x\n    A -> B: hi\ndeactivate B\n@enduml"
        editor.performTextReplacement(source)
        val loop = source.indexOf("loop")
        editor.performTextInputSelection(TextRange(loop + 2))
        assertEquals(listOf(shown(source, loop, "loop") + ":unbalanced"), marks(), "a loop that never ends")
        val deactivate = source.indexOf("deactivate")
        editor.performTextInputSelection(TextRange(deactivate + 2))
        assertEquals(listOf(shown(source, deactivate, "deactivate B") + ":unbalanced"), marks(), "a deactivate with nothing activated")
    }

    @Test fun theMarksAreAlsoFoundWhileIdentityMarkerLinesAreHidden() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        val source = "@startuml\n' indagium-seq3:v1 abc123\nloop x\n' indagium-seq3:v1 def456\n    A -> B: hi\nend\n@enduml"
        editor.performTextReplacement(source)
        val loop = source.indexOf("loop")
        editor.performTextInputSelection(TextRange(loop + 1))
        assertEquals(listOf(shown(source, loop, "loop"), shown(source, source.indexOf("end\n@"), "end")), marks())
    }

    /** "start-endExclusive" of [word] at [at] in [source]. */
    private fun shown(source: String, at: Int, word: String): String {
        assertEquals(word, source.substring(at, at + word.length))
        return "$at-${at + word.length}"
    }

    private fun marks(): List<String> {
        rule.waitForIdle()
        return rule.onAllNodesWithTag("seq3-source-construct-marks", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty().split(';').filter { it.isNotEmpty() } }
    }

    private fun openEditor(dialect: Seq3Dialect): SemanticsNodeInteraction {
        root = createTempDirectory("source-match-ui").toFile()
        val tab = mkTab("match", "match.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        val id = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        state.seq3Sessions.setDialect(id, Seq3Dialect.PLANTUML)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(1280.dp, 900.dp)) { Seq3Workspace(state, id) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-edit").performClick()
        // Live import would only add diagnostics noise for these half-typed sources.
        if (rule.onAllNodesWithTag("seq3-source-live-toggle", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("seq3-source-settings").performClick()
        }
        rule.onNodeWithTag("seq3-source-live-toggle", useUnmergedTree = true).performClick()
        if (dialect == Seq3Dialect.MERMAID) {
            rule.onNodeWithTag("seq3-source-dialect").performClick()
            rule.onAllNodesWithText("Mermaid", useUnmergedTree = true)[1].performClick()
        }
        rule.waitForIdle()
        return rule.onNodeWithTag("seq3-source-editor")
    }
}
