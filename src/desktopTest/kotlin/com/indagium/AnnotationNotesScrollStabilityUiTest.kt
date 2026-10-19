@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.ProofreadingSettings
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.ui.AnnotationPanel
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalProofreadingUi
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.LogViewerScrollStateStore
import com.indagium.ui.ProofreadingUi
import com.indagium.ui.VoiceInputUi
import com.indagium.ui.mkTab
import com.indagium.voice.VoiceAudio
import com.indagium.voice.VoiceCapture
import com.indagium.voice.VoiceCaptureResult
import com.indagium.voice.VoiceCaptureSession
import com.indagium.voice.VoiceCaptureStartResult
import com.indagium.voice.VoiceInputController
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceTranscript
import com.indagium.voice.VoiceTranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focusing a field must never change what the notes panel is showing. An empty inline note used to
 * grow on focus (a 52dp minimum height), which changed the block heights the panel's stick-to-bottom
 * effect watches, so the whole panel scrolled by the growth and scrolled back on blur. The dictation
 * microphone and proofreading marks must not bring that back.
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationNotesScrollStabilityUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("notes-scroll-stability").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var coordinator: VoiceInputSessionCoordinator? = null
    private var proofreading: EnglishProofreadingService? = null

    @After
    fun tearDown() {
        coordinator?.close()
        proofreading?.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun focusingAnEmptyVisibleInlineNoteDoesNotMoveThePanelScroll() {
        assertFocusLeavesScrollAlone(withAssistance = false)
    }

    @Test
    fun focusingWithDictationAndProofreadingAvailableDoesNotMoveThePanelScroll() {
        assertFocusLeavesScrollAlone(withAssistance = true)
    }

    private fun assertFocusLeavesScrollAlone(withAssistance: Boolean) {
        val store = LogViewerScrollStateStore()
        val blocks = (1..10).map { AnnBlock.Note("filled-$it", "Filled note number $it with a short body.") } +
            AnnBlock.Note("empty-last", "")
        val tab = mkTab("scroll-stability", "notes.log", emptyList()).copy(annotations = Annotations(blocks = blocks))
        rule.setContent {
            Assistance(withAssistance) {
                Box(Modifier.width(360.dp).height(520.dp)) {
                    AnnotationPanel(
                        tab = tab,
                        settings = AppSettings(renderAnnotationMarkdownInline = true),
                        onToggleMd = {},
                        onCopyImage = {},
                        onExportFrames = {},
                        onSave = {},
                        onNewAnalysis = {},
                        onToggleRecentNotes = {},
                        onOpenNote = {},
                        onUpdatePrefix = {},
                        onUpdateSuffix = {},
                        onUpdateIssueDescription = {},
                        onUpdateBlock = { _, _ -> },
                        onRemoveBlock = {},
                        onMoveBlock = { _, _ -> },
                        onReorderBlock = { _, _ -> },
                        onAddNoteAfter = {},
                        onAddImage = { _, _, _ -> null },
                        onUnhandledFileDrop = {},
                        onNavigateLogRef = {},
                        onNavigateVideoFrame = {},
                        width = 360f,
                        scrollStateStore = store,
                        modifier = Modifier.height(520.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        val scroll = store.scrollState("${tab.id}:notes")
        rule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        rule.waitForIdle()
        assertTrue(scroll.maxValue > 0, "the panel must overflow for this scenario to mean anything")

        val emptyField = rule.onAllNodesWithTag("annotation-note-field")[10]
        emptyField.assertIsDisplayed()
        val before = scroll.value
        emptyField.performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        assertEquals(before, scroll.value, "focusing a fully visible empty field must not scroll the panel")
    }

    @Composable
    private fun Assistance(enabled: Boolean, content: @Composable () -> Unit) {
        if (!enabled) {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { content() }
            return
        }
        val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                    override fun cancel() = Unit
                })
            },
            transcriber = { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("new words")) },
            scope = scope,
        )
        val shared = VoiceInputSessionCoordinator(controller, scope).also { coordinator = it }
        val runtime = VoiceInputRuntime(controller, VoiceModelInstaller(directory), VoiceRecognitionEngine.WHISPER)
        val service = EnglishProofreadingService { EnglishProofreader { _, _ -> emptyList() } }.also { proofreading = it }
        CompositionLocalProvider(
            LocalTheme provides LIGHT_THEME,
            LocalVoiceInputUi provides VoiceInputUi(runtime, shared, VoiceInputSettings()) {},
            LocalProofreadingUi provides ProofreadingUi(service, ProofreadingSettings(), mutableSetOf()),
        ) { content() }
    }
}
