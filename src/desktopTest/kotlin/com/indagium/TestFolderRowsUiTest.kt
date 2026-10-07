package com.indagium

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.ui.AppState
import com.indagium.ui.SaveFolderKind
import com.indagium.ui.SaveFolderRow
import com.indagium.ui.SaveFolderSetting
import com.indagium.ui.truncatePathForDisplay
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** The three AI test folder rows as Settings → General shows them: label, the "(default)" path under the save folder, Browse and Reset. */
@OptIn(ExperimentalTestApi::class)
class TestFolderRowsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun install(state: AppState) {
        rule.setContent {
            Column {
                listOf(SaveFolderKind.TEST_SUITES, SaveFolderKind.TEST_RUNS, SaveFolderKind.TEST_ISSUES).forEach { SaveFolderSetting(state, it) }
            }
        }
    }

    @Test
    fun eachRowShowsItsDefaultSubfolderAndBrowseButNoReset() {
        val dir = createTempDirectory("test-folder-rows").toFile()
        val root = File(dir, "Indagium")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = root)
        install(state)

        rule.onNodeWithText("Test suites folder").assertExists()
        rule.onNodeWithText("Test runs folder").assertExists()
        rule.onNodeWithText("Issues folder").assertExists()
        listOf("test-suites", "test-runs", "test-issues").forEach { sub ->
            rule.onNodeWithText("${truncatePathForDisplay(File(root, sub).absolutePath)}  (default)").assertExists()
        }
        rule.onNodeWithText("Reset").assertDoesNotExist()
        dir.deleteRecursively()
    }

    @Test
    fun anExplicitFolderLosesTheDefaultSuffixAndGainsReset() {
        val dir = createTempDirectory("test-folder-rows-explicit").toFile()
        val mine = File(dir, "my-issues")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = File(dir, "Indagium"))
        state.updateSettings { it.copy(testIssuesDir = mine.absolutePath) }
        install(state)

        rule.onNodeWithText(truncatePathForDisplay(mine.absolutePath)).assertExists()
        rule.onNodeWithText("Reset").assertExists()
        dir.deleteRecursively()
    }

    @Test
    fun aLockedRowIgnoresBrowseAndResetClicks() {
        var clicks = 0
        rule.setContent {
            SaveFolderRow(
                label = "Test runs folder", tooltip = "t", explicitValue = "/x", effectivePath = "/x",
                onBrowse = { clicks++ }, onReset = { clicks++ }, enabled = false, disabledHint = "locked",
            )
        }

        rule.onNodeWithText("Browse").performClick()
        rule.onNodeWithText("Reset").performClick()

        assertEquals(0, clicks)
    }
}
