@file:Suppress("MagicNumber") // Fixture timestamps, not tunable constants.

package com.indagium.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.EVENT_SCRIPT_PLACEHOLDER_COMMAND
import com.indagium.testing.authoring.EnvironmentEvent
import com.indagium.testing.authoring.InputSource
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.touchCommand
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recording review of a detected external event, composed against a real AppState: the chooser, the new-script trip and the fallback. */
@OptIn(ExperimentalTestApi::class)
class ExternalEventRowUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("external-row-ui").toFile()
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
    private lateinit var session: TestStepRecordingSession
    private lateinit var suiteId: String
    private lateinit var caseId: String

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    /** A tap, then a USB stick appearing on its own: a stopped recording of the case, shown in the Tests workspace. */
    private fun openReview() {
        suiteId = (state.createTestSuite("Hub") as StoreResult.Ok).value.id
        caseId = (state.createTestCase(suiteId, TestCase("", "Plug")) as StoreResult.Ok).value.id
        session = TestStepRecordingSession("fixture-device")
        session.accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, 960, InputSource.DEVICE)
        session.accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, 1_000, InputSource.DEVICE)
        session.environmentSink.environmentEvents(listOf(EnvironmentEvent("usb.devices", "", "0781:5581 Ultra", atMs = 9_000, sinceMs = 8_000)))
        runBlocking { session.stopAndDrain() }
        check(state.registerTestStepRecording(session, suiteId, caseId) is StoreResult.Ok)
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = caseId
        rule.waitForIdle()
    }

    private fun external() = session.snapshot.value.steps[1]

    @Test
    fun anExternalEventShowsTheChooserAndStaysAManualStepThatWaitsForATester() {
        openReview()

        rule.onNodeWithText("Detected on the device").assertExists()
        rule.onNodeWithText("Instructions for the tester").assertExists()
        rule.onNodeWithText("Wait for tester").assertExists()
        rule.onNodeWithText("Run script ▾").assertExists()
        rule.onNodeWithText("+ New script").assertExists()
        rule.onNodeWithText("Device-state checks (1)").assertExists()
        assertEquals(StepKind.MANUAL, external().stepKind)
        assertNull(external().script)
    }

    @Test
    fun aNewScriptIsNamedAfterTheEventHasACommandThatFailsUntilWrittenAndBecomesTheRowsScript() {
        openReview()

        rule.onNodeWithText("+ New script").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        val script = state.testLibrary.scripts.single()
        assertEquals("connect_usb_device", script.toolName)
        assertEquals(EVENT_SCRIPT_PLACEHOLDER_COMMAND, script.commandTemplate, "no command is invented")
        assertTrue(script.commandTemplate.contains("exit 1"), "a step cannot pass before someone wrote the command")
        assertTrue("Connect the USB device 'Ultra' (0781:5581) to the phone" in script.description, script.description)
        assertEquals(ScriptPermission.ASK, script.permission)
        assertEquals(StepKind.SCRIPT, external().stepKind)
        assertEquals(script.id, external().script?.scriptId)
        assertEquals(TestsNav.Scripts, state.testsView.nav)
        assertEquals(script.id, state.testsView.selectedScriptId)
        assertNotNull(state.testsView.scriptReturn)
        rule.onNodeWithText("Back to the step").assertExists()
    }

    @Test
    fun aScriptRowCanKeepTheTesterAsAFallbackAndGoBackToWaiting() {
        val hub = (state.createTestScript(TestScript("", "hub_on", commandTemplate = "true", permission = ScriptPermission.AUTO)) as StoreResult.Ok).value
        openReview()
        val row = external()
        val scripted = session.snapshot.value.steps.map { if (it.id == row.id) it.copy(stepKind = StepKind.SCRIPT, script = ScriptCall(hub.id)) else it }
        assertTrue(session.replaceSteps(scripted))
        rule.waitForIdle()
        rule.onNodeWithText("Keep manual fallback: wait for a tester when the script does not do it").assertExists()

        rule.onNodeWithText("Keep manual fallback: wait for a tester when the script does not do it").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertEquals(StepKind.MANUAL, external().stepKind)
        assertEquals(hub.id, external().script?.scriptId, "the script is now the automation alternative")

        rule.onNodeWithText("Wait for tester").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertEquals(StepKind.MANUAL, external().stepKind)
        assertNull(external().script)
    }

    @Test
    fun whatFollowedARecordedInputIsShownWithAShortcutToUseItAsTheExpectedResult() {
        suiteId = (state.createTestSuite("Hub") as StoreResult.Ok).value.id
        caseId = (state.createTestCase(suiteId, TestCase("", "Wifi")) as StoreResult.Ok).value.id
        session = TestStepRecordingSession("fixture-device")
        session.accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, 960, InputSource.DEVICE)
        session.accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, 1_000, InputSource.DEVICE)
        session.environmentSink.environmentEvents(listOf(EnvironmentEvent("wifi.enabled", "true", "false", atMs = 1_900, sinceMs = 1_500)))
        runBlocking { session.stopAndDrain() }
        check(state.registerTestStepRecording(session, suiteId, caseId) is StoreResult.Ok)
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = caseId
        rule.waitForIdle()

        rule.onNodeWithText("On the device after this step: Wi-Fi turned off.").assertExists()
        rule.onNodeWithText("Use as the expected result").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertEquals("Wi-Fi turned off", session.snapshot.value.steps.single().expected)
    }
}
