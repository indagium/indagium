// Window sizes of the layouts under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidaySettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.ui.holiday.*
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue

private const val PICTURE_TAB_ID = "holiday-picture-ui"

private val SHORTENED_FILTER_COLUMN = listOf(
    LogViewSectionId.HIGHLIGHTERS,
    LogViewSectionId.ISSUES,
    LogViewSectionId.SAVED_FILTERS,
    LogViewSectionId.SEQUENCES,
)

/** The real FileView in the default Classic layout under a seasonal pack: the picture under the filters and the cat in Notes. */
@OptIn(ExperimentalTestApi::class)
class HolidayPictureUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val voiceUi: VoiceInputUi by lazy {
        val controller = VoiceInputController(
            hasInstalledModel = { false },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                    override fun cancel() = Unit
                })
            },
            transcriber = { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("")) },
            scope = voiceScope,
        )
        VoiceInputUi(
            VoiceInputRuntime(controller, VoiceModelInstaller(createTempDirectory("holiday-picture-voice").toFile()), VoiceRecognitionEngine.WHISPER),
            VoiceInputSessionCoordinator(controller, voiceScope),
            VoiceInputSettings(),
        ) {}
    }

    @After
    fun tearDown() {
        voiceScope.cancel()
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun lookWith(edit: (HolidaySettings) -> HolidaySettings): HolidayLook? =
        resolveHolidayLook(AppSettings().withHoliday { edit(it.copy(mode = HolidayMode.ALWAYS)) }, LocalDate.of(2026, 10, 31))

    private val exclusions = HolidayExclusions()

    private fun setup(look: HolidayLook?, width: Int = 1300, height: Int = 760, shortenFilters: Boolean = true) {
        directory = createTempDirectory("holiday-picture-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        val rows = (1..20).map { LogEntry(it, "10:00:00.$it", LogLevel.I, "Tag", "row $it", pid = 100, tid = 1) }
        state.tabs = listOf(mkTab(PICTURE_TAB_ID, "holiday.log", rows))
        state.activeTabId = PICTURE_TAB_ID
        // Unless a test says otherwise, the filter column is shortened so it leaves free room under it.
        if (shortenFilters) SHORTENED_FILTER_COLUMN.forEach(state::hideLogViewTile)
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides (look?.colors ?: LIGHT_THEME),
                LocalHoliday provides look,
                LocalHolidayExclusions provides exclusions,
                LocalVoiceInputUi provides voiceUi,
            ) {
                Box(Modifier.size(width.dp, height.dp)) { state.tab(PICTURE_TAB_ID)?.let { FileView(state, it) } }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theLogViewItselfDrawsNoArtAtAllTheAppLevelLayersDo() {
        setup(lookWith { it })
        assertTrue(!exists(HOLIDAY_FILTER_PICTURE_TAG) && !exists(HOLIDAY_NOTES_COMPANION_TAG), "no tile or surface carries an art any more")
    }

    @Test
    fun calmKeepsTheLogAndOriginalAreasClearOfTheEffects() {
        setup(lookWith { it.copy(intensity = HolidayIntensity.CALM) })
        val path = exclusions.path()
        assertTrue(path != null, "the log tile reports its area under Calm")
        val log = rule.onNodeWithTag("tile-card-log").getBoundsInRoot()
        val density = rule.density.density
        assertTrue(path.getBounds().contains(androidx.compose.ui.geometry.Offset((log.left.value + 20f) * density, (log.top.value + 60f) * density)))
    }

    @Test
    fun fullIntensityReportsNoClearAreas() {
        setup(lookWith { it })
        assertTrue(exclusions.path() == null, "Full draws over the log, so nothing is excluded")
    }
}
