package com.indagium.testing

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.indagium.ui.AppState
import com.indagium.ui.InlineField
import com.indagium.ui.LocalTestsUi
import com.indagium.ui.LocalTextInputFocusTracker
import com.indagium.ui.TestsKeyTargetEffect
import com.indagium.ui.TestsUi
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The workspace's bare-key routing: a screen's key target gets keys, unless a text input has focus. */
@OptIn(ExperimentalTestApi::class)
class TestsKeyRoutingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-key-routing").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )
    private val received = mutableListOf<Key>()
    private lateinit var ui: TestsUi

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun setHarnessContent(registerTarget: Boolean = true) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val root = remember { FocusRequester() }
            ui = remember { TestsUi(state, state.testsView, root, scope) }
            var text by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { root.requestFocus() }
            CompositionLocalProvider(LocalTestsUi provides ui, LocalTextInputFocusTracker provides ui.textFocus) {
                Column(Modifier.onPreviewKeyEvent { ui.routeKey(it) }.focusRequester(root).focusable()) {
                    if (registerTarget) {
                        TestsKeyTargetEffect { event ->
                            received += event.key
                            true
                        }
                    }
                    InlineField(text, { text = it }, placeholder = "Type here")
                    Text("Elsewhere")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theKeyTargetReceivesKeysWhileNoTextInputHasFocus() {
        setHarnessContent()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()

        assertEquals(listOf(Key.DirectionDown), received, "only the key-down is routed")
    }

    @Test
    fun aFocusedTextInputKeepsItsKeysAndGivesThemBackWhenFocusLeaves() {
        setHarnessContent()

        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        assertTrue(ui.textFocus.anyFocused, "the shared InlineField reports its focus")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertTrue(received.isEmpty(), "arrows belong to the field while it is focused, got $received")

        ui.reclaimFocus()
        rule.waitForIdle()
        assertTrue(!ui.textFocus.anyFocused)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertEquals(listOf(Key.DirectionUp), received)
    }

    @Test
    fun withoutAKeyTargetNothingIsConsumed() {
        setHarnessContent(registerTarget = false)

        rule.onNodeWithText("Elsewhere").assertExists()
        assertTrue(received.isEmpty())
        assertEquals(null, ui.keyTarget)
    }
}
