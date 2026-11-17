package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LOAD_TIMEOUT_MS = 15000L
private const val LEFT_TOLERANCE_PX = 1.5f

/** A wrapped profile row hangs its continuation lines at the message column. */
@OptIn(ExperimentalTestApi::class)
class ProfileRowHangingIndentUiTest {
    @get:Rule
    val rule = createComposeRule()
    private var app: AppState? = null
    private var directory: File? = null

    @After
    fun tearDown() {
        app?.close()
        directory?.deleteRecursively()
    }

    private val note = (1..60).joinToString(" ") { "token$it" }

    /** A metadata JSON row (an expandable record, shown as one compact summary) or plain logfmt rows. */
    private fun open(logfmt: Boolean = false): Pair<String, Int> {
        val dir = Files.createTempDirectory("profile-hang-ui-").toFile().also { directory = it }
        val file = if (logfmt) {
            File(dir, "records.log").apply {
                writeText((1..3).joinToString("\n") { "time=2026-11-16T10:00:0$it.000Z level=info msg=\"$note\"" } + "\n")
            }
        } else {
            File(dir, "records.json").apply {
                writeText(
                    """[{"metadata":{"identity":"Svc.Event","source":"Demo","system_time":1790000000000000000},"Summary":{"Note":"$note"}}]""",
                )
            }
        }
        val state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false).also { app = it }
        val id = assertNotNull(state.openFile(file))
        rule.waitUntil(LOAD_TIMEOUT_MS) { state.tab(id)?.contentState == TabContentState.LOADED }
        return id to state.tab(id)!!.logData.first().id
    }

    private fun show(tabId: String) {
        val state = app!!
        state.filterVisible = false
        state.annotationVisible = false
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Box(Modifier.width(1000.dp).fillMaxHeight()) { state.tab(tabId)?.let { FileView(state, it) } }
            }
        }
        rule.waitForIdle()
    }

    private fun rowLayout(tabId: String, entryId: Int): TextLayoutResult {
        val rowTag = hasTestTag("log-row-$tabId:main-$entryId")
        val field = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(rowTag)
        rule.waitUntil(LOAD_TIMEOUT_MS) { rule.onAllNodes(field, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onAllNodes(field, useUnmergedTree = true).fetchSemanticsNodes().first()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun assertHangsAtTheMessage(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        assertTrue(layout.lineCount >= 3, "the long message wraps: lines=${layout.lineCount} text=$text")
        val messageStart = text.indexOf("token1 ")
        assertTrue(messageStart > 0, "the message sits after the timestamp and source columns: $text")
        val messageLeft = layout.getBoundingBox(messageStart).left
        assertTrue(messageLeft > 0f, "messageLeft=$messageLeft lines=${layout.lineCount} text=$text")
        assertEquals(0f, layout.getLineLeft(0), LEFT_TOLERANCE_PX, "the first line starts at the row's left edge")
        for (line in 1 until layout.lineCount) {
            val firstChar = layout.getLineStart(line)
            val left = layout.getBoundingBox(firstChar).left
            assertEquals(messageLeft, left, LEFT_TOLERANCE_PX, "line $line starts at the message column (text=$text)")
        }
    }

    @Test
    fun aSoftWrappedMessageHangsAtTheMessageColumn() {
        val (tabId, entryId) = open()
        app!!.settings = app!!.settings.copy(autoLogRowWrap = true)
        show(tabId)
        assertHangsAtTheMessage(rowLayout(tabId, entryId))
    }

    @Test
    fun aWrapLimitMessageHangsAtTheMessageColumnAndCopiesWithoutBreaks() {
        val (tabId, entryId) = open(logfmt = true)
        assertTrue(app!!.tab(tabId)!!.logData.first().recordMetadata?.usesProfilePresentation == true)
        app!!.settings = app!!.settings.copy(autoLogRowWrap = false, logRowWrapLimitChars = 80)
        show(tabId)
        val layout = rowLayout(tabId, entryId)
        assertHangsAtTheMessage(layout)
        val shown = layout.layoutInput.text.text
        assertTrue('\n' in shown, "wrap-limit mode breaks the line itself")
        assertTrue(shown.replace("\n", "").contains(note), "no padding or other characters were added")
    }
}
