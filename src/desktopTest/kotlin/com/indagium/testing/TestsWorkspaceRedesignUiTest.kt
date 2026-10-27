package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real workspace regression: stable panels, contextual evidence, and actions above a long case list. */
@OptIn(ExperimentalTestApi::class)
class TestsWorkspaceRedesignUiTest {
    private companion object {
        const val WORKSPACE_WIDTH = 1500
        const val WORKSPACE_HEIGHT = 900
    }

    private lateinit var rule: DesktopComposeUiTest

    private val dir = createTempDirectory("tests-redesign-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        restoreOnCreate = false,
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun openWorkspace() {
        rule.setContent {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1500.dp, 900.dp)) {
                TestsWorkspace(state)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun creationActionsStayAboveTheScrollingCaseListAndAllSevenTilesExist() = runDesktopComposeUiTest(WORKSPACE_WIDTH, WORKSPACE_HEIGHT) {
        rule = this
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        repeat(30) { index -> state.createTestCase(suite.id, TestCase("", "Case ${index + 1}")) }
        state.testsView.selectedSuiteId = suite.id
        openWorkspace()

        listOf("suites", "library", "activity", "workspace", "execution", "details", "evidence").forEach { key ->
            rule.onNodeWithTag("tests-tile-card-$key").assertExists()
        }
        val actions = rule.onNodeWithTag("tests-workspace-actions")
        val before = actions.getBoundsInRoot()
        val scroll = rule.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("tests-tile-card-workspace")))[0]
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        rule.waitForIdle()
        assertEquals(before, actions.getBoundsInRoot(), "Creation and run actions must not scroll away with the case table")
        val addCase = rule.onNodeWithText("+ New case").getBoundsInRoot()
        assertTrue(addCase.top >= before.top && addCase.bottom <= before.bottom, "+ New case belongs in the fixed action row")
        saveScreenshot("suite-long-list.png", rule.onNodeWithTag("tests-tiling-host").captureToImage())
    }

    @Test
    fun changingSelectionReplacesDetailsAndClearsPreviousStepEvidence() = runDesktopComposeUiTest(WORKSPACE_WIDTH, WORKSPACE_HEIGHT) {
        rule = this
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        val withEvidence = plainStep("Open settings").copy(expected = "Settings are visible", examples = listOf(
            StepExample.ReferenceLog("ref-ready", "Synthetic ready line", "Ready evidence"),
        ))
        val case = (state.createTestCase(suite.id, TestCase("", "Settings", steps = listOf(withEvidence))) as StoreResult.Ok).value
        state.testsView.selectedSuiteId = suite.id
        state.testsView.selectedCaseId = case.id
        state.testsView.selectedStepId = case.steps.single().id
        openWorkspace()

        rule.onNodeWithTag("tests-tile-card-details").assertExists()
        rule.onNode(hasText("Synthetic ready line", substring = true) and hasAnyAncestor(hasTestTag("tests-tile-card-evidence"))).assertExists()
        saveScreenshot("case-with-evidence.png", rule.onNodeWithTag("tests-tiling-host").captureToImage())
        rule.runOnIdle { state.testsView.nav = TestsNav.Scripts }
        rule.waitForIdle()
        rule.onNodeWithTag("tests-tile-card-evidence").assertExists()
        rule.onNodeWithText("Synthetic ready line", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("tests-tile-card-execution").assertExists()
    }

    @Test
    fun onlyWorkspaceAndEvidenceOfferMaximizeAndEscapeRestoresTheLayout() = runDesktopComposeUiTest(WORKSPACE_WIDTH, WORKSPACE_HEIGHT) {
        rule = this
        state.createTestSuite("Smoke")
        openWorkspace()
        listOf("suites", "library", "activity", "execution", "details").forEach { key ->
            rule.onNodeWithTag("tests-tile-maximize-$key").assertDoesNotExist()
        }
        rule.onNodeWithTag("tests-tile-maximize-evidence").assertExists()
        val before = rule.onNodeWithTag("tests-tile-card-workspace").getBoundsInRoot()
        rule.onNodeWithTag("tests-tile-maximize-workspace").performClick()
        rule.waitForIdle()
        val maximized = rule.onNodeWithTag("tests-tile-card-workspace").getBoundsInRoot()
        assertTrue(maximized.width > before.width, "Maximize should provide the Workspace with the full page width")
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("tests-tile-card-workspace").getBoundsInRoot())
    }

    private fun saveScreenshot(name: String, image: ImageBitmap) {
        val directory = File("build/tests-redesign-qa").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        check(ImageIO.write(rendered, "png", File(directory, name)))
    }
}
