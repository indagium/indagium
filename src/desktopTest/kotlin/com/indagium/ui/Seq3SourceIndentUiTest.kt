package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Dialect
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

class Seq3SourceIndentUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun enterInsideALoopIndentsAndTypingEndOutdentsInPlantUml() = enterThenEnd(Seq3Dialect.PLANTUML)

    @Test fun enterInsideALoopIndentsAndTypingEndOutdentsInMermaid() = enterThenEnd(Seq3Dialect.MERMAID)

    @Test fun tabAndShiftTabMoveTheTouchedLinesAsOneUndoStepInPlantUml() = tabAndShiftTab(Seq3Dialect.PLANTUML)

    @Test fun tabAndShiftTabMoveTheTouchedLinesAsOneUndoStepInMermaid() = tabAndShiftTab(Seq3Dialect.MERMAID)

    @Test fun messageInsideALoopIsIndentedWithItsPlaceholderSelectedInPlantUml() = messageInsideLoop(Seq3Dialect.PLANTUML)

    @Test fun messageInsideALoopIsIndentedWithItsPlaceholderSelectedInMermaid() = messageInsideLoop(Seq3Dialect.MERMAID)

    @Test fun blockInsideALoopIsIndentedAndOneUndoRemovesIt() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        val source = "@startuml\nloop x\n    A -> B: hi\nend\n@enduml"
        editor.performTextReplacement(source)
        editor.performTextInputSelection(TextRange("@startuml\nloop x".length))
        rule.onNodeWithTag("seq3-source-fragment-menu").performClick()
        rule.onNodeWithText("alt", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(
            "@startuml\nloop x\n    alt condition\n        From -> To: message label\n    end\n    A -> B: hi\nend\n@enduml",
            text(),
        )
        assertEquals("condition", selectedText())
        undo()
        assertEquals(source, text())
    }

    @Test fun acceptingAKeywordSuggestionInsideABlockIndentsIt() {
        val editor = openEditor(Seq3Dialect.PLANTUML)
        editor.performTextReplacement("@startuml\nloop x")
        editor.performTextInputSelection(TextRange("@startuml\nloop x".length))
        editor.performKeyInput { pressKey(Key.Enter) }
        editor.performTextInput("alt")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertExists()
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("@startuml\nloop x\n    alt condition\n        From -> To: message label\n    end", text())
    }

    private fun enterThenEnd(dialect: Seq3Dialect) {
        val editor = openEditor(dialect)
        val body = if (dialect == Seq3Dialect.MERMAID) 2 else 1
        val loopLine = if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram\n    loop again" else "@startuml\nloop again"
        editor.performTextReplacement(loopLine)
        editor.performTextInputSelection(TextRange(loopLine.length))
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        val indent = " ".repeat(body * 4)
        assertEquals("$loopLine\n$indent", text(), "Enter after a block opener indents the body")

        editor.performTextInput(if (dialect == Seq3Dialect.MERMAID) "A->>B: hi" else "A -> B: hi")
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        val statement = if (dialect == Seq3Dialect.MERMAID) "A->>B: hi" else "A -> B: hi"
        assertEquals("$loopLine\n$indent$statement\n$indent", text(), "Enter after a statement keeps its indent")

        val before = text()
        editor.performTextInput("end")
        rule.waitForIdle()
        val closer = " ".repeat((body - 1) * 4) + "end"
        assertEquals("$loopLine\n$indent$statement\n$closer", text(), "typing end moves it to the opener's level")
        undo()
        assertEquals(before, text(), "the keystroke and its outdent are one undo step")
        undo()
        assertEquals("$loopLine\n$indent$statement", text(), "the second Enter is its own undo step")
        undo()
        assertEquals("$loopLine\n$indent", text(), "the first Enter is its own undo step")
    }

    private fun tabAndShiftTab(dialect: Seq3Dialect) {
        val editor = openEditor(dialect)
        val pad = if (dialect == Seq3Dialect.MERMAID) "    " else ""
        val head = if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram" else "@startuml"
        val one = if (dialect == Seq3Dialect.MERMAID) "A->>B: one" else "A -> B: one"
        val two = if (dialect == Seq3Dialect.MERMAID) "A->>B: two" else "A -> B: two"
        val tail = if (dialect == Seq3Dialect.MERMAID) "" else "\n@enduml"
        val source = "$head\n$pad$one\n$pad$two$tail"
        editor.performTextReplacement(source)
        val start = source.indexOf(one) + 2
        val end = source.indexOf(two) + 3
        editor.performTextInputSelection(TextRange(start, end))
        val covered = source.substring(start, end)

        editor.performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        val indented = "$head\n$pad    $one\n$pad    $two$tail"
        assertEquals(indented, text())
        // The second line's new indent falls inside the selection; the text it started on is unchanged.
        assertEquals(covered.replace("\n$pad", "\n$pad    "), selectedText(), "the selection covers the same text after Tab")

        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        rule.waitForIdle()
        assertEquals(source, text())
        assertEquals(covered, selectedText(), "the selection covers the same text after Shift+Tab")

        undo()
        assertEquals(indented, text(), "Shift+Tab is one undo step")
        undo()
        assertEquals(source, text(), "Tab is one undo step")
    }

    private fun messageInsideLoop(dialect: Seq3Dialect) {
        val editor = openEditor(dialect)
        val mermaid = dialect == Seq3Dialect.MERMAID
        val source = if (mermaid) "sequenceDiagram\n    loop x\n        A->>B: hi\n    end" else "@startuml\nloop x\n    A -> B: hi\nend\n@enduml"
        editor.performTextReplacement(source)
        val loopEnd = source.indexOf("loop x") + "loop x".length
        editor.performTextInputSelection(TextRange(loopEnd))
        rule.onNodeWithTag("seq3-source-message-menu").performClick()
        rule.onNodeWithText("Call", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val line = if (mermaid) "        From->>To: message label" else "    From -> To: message label"
        assertEquals(source.substring(0, loopEnd) + "\n" + line + source.substring(loopEnd), text())
        assertEquals("From", selectedText(), "the first placeholder stays selected")

        editor.performTextInput("A")
        editor.performKeyInput { pressKey(Key.Tab) }
        assertEquals("To", selectedText(), "Tab still walks the placeholders")
        editor.performTextInput("B")
        editor.performKeyInput { pressKey(Key.Tab) }
        assertEquals("message label", selectedText())
        editor.performTextInput("Hello")
        val expected = if (mermaid) "        A->>B: Hello" else "    A -> B: Hello"
        assertEquals(source.substring(0, loopEnd) + "\n" + expected + source.substring(loopEnd), text())
    }

    private fun openEditor(dialect: Seq3Dialect): SemanticsNodeInteraction {
        root = createTempDirectory("source-indent-ui").toFile()
        val tab = mkTab("indent", "indent.log", emptyList())
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

    private fun text(): String =
        rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun selectedText(): String {
        val range = rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        return text().substring(range.min, range.max)
    }

    private fun undo() {
        rule.onNodeWithTag("seq3-source-editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()
    }
}
