package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntSize
import com.indagium.model.AnnBlock
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.ProcessNameMode
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.LocalTheme
import com.indagium.ui.WARM_PAPER
import com.indagium.ui.isMacOs
import com.indagium.ui.mkTab
import com.indagium.utils.captureAnnotationLogRef
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NARROW_DIALOG_WINDOW_SIZE = IntSize(400, 640)
private val MEDIUM_DIALOG_WINDOW_SIZE = IntSize(600, 700)

class AnnotationMetadataOptionsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val rows = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first evidence", pid = 100, tid = 7),
        LogEntry(2, "10:00:00.750", LogLevel.E, "App", "second evidence", pid = 100, tid = 8),
    )

    private val presentation = AnnBlock.LogRefPresentation(
        options = AnnBlock.LogRefPresentationOptions(
            timeDelta = true,
            rowNumber = true,
            pidTid = true,
            processName = true,
        ),
        rows = listOf(
            AnnBlock.LogRefRowPresentation(1, "1", "+0.000", "PickedApp", "PickedApp"),
            AnnBlock.LogRefRowPresentation(2, "2", "+0.750", "PickedApp", "PickedApp"),
        ),
    )

    private fun freshCapturePresentation(): AnnBlock.LogRefPresentation {
        val source = mkTab("source", "source.log", rows).copy(
            showTimeDelta = true,
            processNameMode = ProcessNameMode.ALL,
            analysis = mkTab("source", "source.log", rows).analysis.copy(
                processNames = mapOf(100 to "PickedApp"),
            ),
        )
        return captureAnnotationLogRef(
            tab = source,
            targetIds = listOf(1, 2),
            selection = setOf(1),
            displayedEntries = rows,
            settings = AppSettings(
                showRowNumbers = true,
                copyRowNumber = true,
                copyTimeDelta = true,
                copyPidTid = true,
                copyPidAsName = true,
            ),
        ).presentation
    }

    @Test
    fun metadataOptionsRenderInLightDarkAndNarrowDialogScreenshots() {
        screenshot(WARM_PAPER, "light-narrow")
        screenshot(DARK_GITHUB, "dark-narrow")
        screenshot(WARM_PAPER, "light-medium", MEDIUM_DIALOG_WINDOW_SIZE)
        screenshot(WARM_PAPER, "light-medium-on", MEDIUM_DIALOG_WINDOW_SIZE, presentation)
        screenshot(DARK_GITHUB, "dark-medium-on", MEDIUM_DIALOG_WINDOW_SIZE, presentation)
    }

    @Test
    fun chipChangesAreSavedTogetherWithCaption() {
        var savedCaption: String? = null
        var savedOptions: AnnBlock.LogRefPresentationOptions? = null
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "New note",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = NARROW_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = presentation,
                onPresentationConfirm = { caption, options ->
                    savedCaption = caption
                    savedOptions = options
                },
                onConfirm = { savedCaption = it },
                onDismiss = {},
            )
        }
        rule.onNodeWithTag("annotation-metadata-time-delta").assertIsDisplayed().performClick()
        rule.onNodeWithTag("annotation-metadata-process-name").performClick()
        rule.onNodeWithTag("annotation-markdown-editor").performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) }
        }

        assertEquals("", savedCaption)
        assertEquals(false, savedOptions?.timeDelta)
        assertEquals(false, savedOptions?.processName)
    }

    @Test
    fun newChoicesStartOffAndEnablingRendersCapturedEvidence() {
        val captured = freshCapturePresentation()
        assertEquals(AnnBlock.LogRefPresentationOptions(), captured.options)
        assertEquals("0.000", captured.row(1)?.timeDeltaText)
        assertEquals("+0.750", captured.row(2)?.timeDeltaText)
        assertEquals("PickedApp", captured.row(1)?.processNameCandidate)

        var savedOptions: AnnBlock.LogRefPresentationOptions? = null
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "New note",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = MEDIUM_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = captured,
                onPresentationConfirm = { _, options -> savedOptions = options },
                onConfirm = {},
                onDismiss = {},
            )
        }

        val delta = rule.onNodeWithTag("annotation-metadata-time-delta")
        val rowNumber = rule.onNodeWithTag("annotation-metadata-row-number")
        val pidTid = rule.onNodeWithTag("annotation-metadata-pid-tid")
        val processName = rule.onNodeWithTag("annotation-metadata-process-name")
        delta.assertIsOff().assertIsEnabled()
        rowNumber.assertIsOff().assertIsEnabled()
        pidTid.assertIsOff().assertIsEnabled()
        processName.assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Preview ·", substring = true).assertDoesNotExist()

        delta.performClick()
        rowNumber.performClick()
        pidTid.performClick()
        processName.assertIsEnabled().performClick()
        rule.onNodeWithTag("annotation-evidence-toggle").performClick()
        rule.onNodeWithText("1  0.000  10:00:00.000  PickedApp 7  I/App  first evidence").assertIsDisplayed()
        rule.onNodeWithText("2  +0.750  10:00:00.750  PickedApp 8  E/App  second evidence").assertIsDisplayed()

        rule.onNodeWithTag("annotation-markdown-editor").performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) }
        }
        assertEquals(
            AnnBlock.LogRefPresentationOptions(
                timeDelta = true,
                rowNumber = true,
                pidTid = true,
                processName = true,
                allProcessNameCandidates = true,
            ),
            savedOptions,
        )
    }

    @Test
    fun clickingAChipDoesNotToggleTheExcerpt() {
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "New note",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = MEDIUM_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = presentation,
                onPresentationConfirm = { _, _ -> },
                onConfirm = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithTag("annotation-metadata-row-number").performClick()
        rule.onNodeWithTag("annotation-metadata-time-delta").performClick()
        rule.onNodeWithTag("annotation-metadata-row-number").assertIsOff()
        rule.onNodeWithTag("annotation-metadata-time-delta").assertIsOff()
        rule.onNodeWithText("first evidence", substring = true).assertDoesNotExist()

        rule.onNodeWithTag("annotation-evidence-toggle").performClick()
        rule.onNodeWithText("first evidence", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("annotation-metadata-row-number").performClick()
        rule.onNodeWithTag("annotation-metadata-row-number").assertIsOn()
        rule.onNodeWithText("first evidence", substring = true).assertIsDisplayed()
    }

    @Test
    fun processChipNeedsPidTidFirst() {
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "New note",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = MEDIUM_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = freshCapturePresentation(),
                onPresentationConfirm = { _, _ -> },
                onConfirm = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-pid-tid").performClick()
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsEnabled()
        rule.onNodeWithTag("annotation-metadata-pid-tid").performClick()
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsNotEnabled()
    }

    @Test
    fun savedEditChoicesAreRestoredWhenUntouched() {
        var savedOptions: AnnBlock.LogRefPresentationOptions? = null
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "existing caption",
                confirmLabel = "Save note",
                windowSize = MEDIUM_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = presentation,
                onPresentationConfirm = { _, options -> savedOptions = options },
                onConfirm = {},
                onDismiss = {},
            )
        }

        rule.onNodeWithTag("annotation-metadata-time-delta").assertIsOn()
        rule.onNodeWithTag("annotation-metadata-row-number").assertIsOn()
        rule.onNodeWithTag("annotation-metadata-pid-tid").assertIsOn()
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsOn()
        rule.onNodeWithTag("annotation-markdown-editor").performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) }
        }
        assertEquals(presentation.options, savedOptions)
    }

    @Test
    fun cancelDiscardsPendingMetadataChanges() {
        var saveCount = 0
        var dismissed = false
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "existing caption",
                confirmLabel = "Save note",
                windowSize = MEDIUM_DIALOG_WINDOW_SIZE,
                rows = rows,
                initialPresentation = presentation,
                onPresentationConfirm = { _, _ -> saveCount++ },
                onConfirm = { saveCount++ },
                onDismiss = { dismissed = true },
            )
        }

        rule.onNodeWithTag("annotation-metadata-row-number").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, saveCount)
        assertTrue(dismissed)
    }

    @Test
    fun missingCapturedDataDisablesOnlyUnavailableMetadata() {
        val noOptionalData = listOf(LogEntry(1, "", LogLevel.I, "Raw", "unknown", pid = 0, tid = 0))
        val unknownPresentation = AnnBlock.LogRefPresentation(
            options = AnnBlock.LogRefPresentationOptions(),
            rows = listOf(AnnBlock.LogRefRowPresentation(1, "1", "—")),
            deltaAvailable = false,
        )
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Note without optional data",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = NARROW_DIALOG_WINDOW_SIZE,
                rows = noOptionalData,
                initialPresentation = unknownPresentation,
                onPresentationConfirm = { _, _ -> },
                onConfirm = {},
                onDismiss = {},
            )
        }

        rule.onNodeWithTag("annotation-metadata-time-delta").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-pid-tid").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-row-number").assertIsDisplayed()
    }

    @Test
    fun editLockDisablesMetadataControlsAndSave() {
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Locked note",
                initialText = "",
                confirmLabel = "Save note",
                windowSize = NARROW_DIALOG_WINDOW_SIZE,
                rows = rows,
                locked = true,
                initialPresentation = presentation,
                onPresentationConfirm = { _, _ -> error("locked note must not save") },
                onConfirm = { error("locked note must not save") },
                onDismiss = {},
            )
        }

        rule.onNodeWithTag("annotation-metadata-time-delta").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-row-number").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-pid-tid").assertIsNotEnabled()
        rule.onNodeWithTag("annotation-metadata-process-name").assertIsNotEnabled()
        rule.onNodeWithText("Save note").performMouseInput { click() }
    }

    private fun screenshot(
        theme: com.indagium.ui.ThemeColors,
        name: String,
        windowSize: IntSize = NARROW_DIALOG_WINDOW_SIZE,
        captured: AnnBlock.LogRefPresentation = freshCapturePresentation(),
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides theme) {
                AnnotationMarkdownEditorDialog(
                    title = "New note",
                    initialText = "",
                    confirmLabel = "Save note",
                    windowSize = windowSize,
                    rows = rows,
                    initialPresentation = captured,
                    onPresentationConfirm = { _, _ -> },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        val chipTags = listOf(
            "annotation-metadata-time-delta",
            "annotation-metadata-row-number",
            "annotation-metadata-pid-tid",
            "annotation-metadata-process-name",
        )
        val chipTops = chipTags.map { tag ->
            rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(1, chipTops.distinct().size, "metadata chips should occupy one row")
        rule.onNodeWithText("Preview ·", substring = true).assertDoesNotExist()
        val node = rule.onNodeWithTag("annotation-metadata-options")
        node.assertIsDisplayed()
        val image = node.captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/qa/annotation-metadata").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))

        val dialogImage = rule.onNodeWithTag("annotation-editor-dialog").captureToImage()
        val dialogPixels = dialogImage.toPixelMap()
        val fullDialog = BufferedImage(dialogImage.width, dialogImage.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until dialogImage.height) for (x in 0 until dialogImage.width) {
            fullDialog.setRGB(x, y, dialogPixels[x, y].toArgb())
        }
        assertTrue(ImageIO.write(fullDialog, "png", File(directory, "$name-dialog.png")))
    }
}
