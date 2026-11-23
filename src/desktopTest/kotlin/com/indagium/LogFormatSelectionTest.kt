package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFormatDetection
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.utils.decodeLogFormatProfiles
import com.indagium.utils.logFormatProfileJson
import com.indagium.utils.logFormatProfilesJson
import com.indagium.utils.parseLogContent
import com.indagium.utils.validateLogFormatProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LOAD_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 25L

@OptIn(ExperimentalTestApi::class)
class LogFormatSelectionTest {
    @get:Rule
    val rule = createComposeRule()

    private var tempDir: File? = null
    private var appState: AppState? = null

    @After
    fun tearDown() {
        appState?.close()
        tempDir?.deleteRecursively()
    }

    private fun profile(
        id: String,
        extensions: List<String> = emptyList(),
        header: List<String> = emptyList(),
        always: Boolean = false,
    ) = LogFormatProfile(
        id = id,
        name = "Format $id",
        engine = LogFormatEngine.PLAIN_TEXT,
        detection = LogFormatDetection(extensions = extensions, headerContains = header, alwaysUseForExtensions = always),
    )

    private fun parse(text: String, fileName: String?, profiles: List<LogFormatProfile>, requested: LogFormatProfile? = null) =
        parseLogContent(ByteArrayInputStream(text.toByteArray()), fileName = fileName, profile = requested, profiles = profiles)

    private val logcatLine = "05-04 12:30:00.000  1000  1000 I Tag: hello\n"

    @Test
    fun associatedProfileWinsForItsExtensionOnly() {
        val assoc = profile("assoc", extensions = listOf(".APP"), always = true)
        assertEquals("assoc", parse(logcatLine, "run.app", listOf(assoc)).profile?.id)
        assertEquals("assoc", parse(logcatLine, "RUN.App", listOf(assoc)).profile?.id)
        assertEquals("android-logcat", parse(logcatLine, "run.log", listOf(assoc)).profile?.id)
        assertEquals(emptyList(), parse(logcatLine, "run.app", listOf(assoc)).candidateProfileIds)
    }

    @Test
    fun extensionWithoutTheFlagIsNotAnAssociation() {
        val hint = profile("hint", extensions = listOf("app"))
        assertEquals("android-logcat", parse(logcatLine, "run.app", listOf(hint)).profile?.id)
    }

    @Test
    fun associationNeverOverridesAnExplicitProfile() {
        val assoc = profile("assoc", extensions = listOf("app"), always = true)
        val explicit = profile("explicit")
        val parsed = parse(logcatLine, "run.app", listOf(assoc, explicit), requested = explicit)
        assertEquals("explicit", parsed.profile?.id)
        assertTrue(parsed.candidateProfileIds.isEmpty())
    }

    @Test
    fun twoAssociationsForOneExtensionFallBackToPlainTextWithBothCandidates() {
        val a = profile("assoc-a", extensions = listOf("app"), always = true)
        val b = profile("assoc-b", extensions = listOf("app"), always = true)
        val parsed = parse(logcatLine, "run.app", listOf(a, b))
        assertEquals(LogFormatEngine.PLAIN_TEXT, parsed.profile?.engine)
        assertEquals(listOf("assoc-a", "assoc-b"), parsed.candidateProfileIds)
        assertTrue(parsed.diagnostics.isNotEmpty())
    }

    @Test
    fun codecRoundTripsTheFlagAndValidationNeedsAnExtension() {
        val assoc = profile("assoc", extensions = listOf("app"), always = true)
        assertTrue(decodeLogFormatProfiles(logFormatProfileJson(assoc)).single().detection.alwaysUseForExtensions)
        assertTrue(decodeLogFormatProfiles(logFormatProfilesJson(listOf(assoc))).single().detection.alwaysUseForExtensions)
        val plain = profile("plain", extensions = listOf("app"))
        assertFalse(decodeLogFormatProfiles(logFormatProfileJson(plain)).single().detection.alwaysUseForExtensions)
        assertFailsWith<IllegalArgumentException> { validateLogFormatProfile(profile("empty", always = true)) }
    }

    @Test
    fun builtInsNeverSetTheFlag() {
        assertTrue(BUILTIN_LOG_FORMAT_PROFILES.none { it.detection.alwaysUseForExtensions })
    }

    @Test
    fun tiedConfiguredProfilesAreReportedAsCandidates() {
        val a = profile("tie-a", header = listOf("HDR"))
        val b = profile("tie-b", header = listOf("HDR"))
        val parsed = parse("HDR first\nsecond\n", "x.txt", listOf(a, b))
        assertEquals(LogFormatEngine.PLAIN_TEXT, parsed.profile?.engine)
        assertEquals(listOf("tie-a", "tie-b"), parsed.candidateProfileIds)
        val single = parse("HDR first\n", "x.txt", listOf(a))
        assertEquals("tie-a", single.profile?.id)
        assertTrue(single.candidateProfileIds.isEmpty())
    }

    @Test
    fun aPlainLogcatFileHasNoCandidates() {
        assertTrue(parse(logcatLine, "x.log", emptyList()).candidateProfileIds.isEmpty())
    }

    private fun newState(): AppState {
        val directory = Files.createTempDirectory("log-format-selection-").toFile()
        tempDir = directory
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { appState = it }
    }

    /** Saves two tying profiles and opens a file both match; returns the opened tab id. */
    private fun openTiedFile(state: AppState): String {
        assertTrue(state.saveLogFormatProfile(profile("tie-a", header = listOf("HDR"))).isSuccess)
        assertTrue(state.saveLogFormatProfile(profile("tie-b", header = listOf("HDR"))).isSuccess)
        val source = File(requireNotNull(tempDir), "tied.txt").apply { writeText("HDR first\nsecond\n") }
        val tabId = assertNotNull(state.openFile(source))
        rule.waitUntil(LOAD_TIMEOUT_MS) { state.tab(tabId)?.contentState == TabContentState.LOADED }
        return tabId
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) = runBlocking {
        withTimeout(LOAD_TIMEOUT_MS) { while (!predicate()) delay(POLL_INTERVAL_MS) }
        assertTrue(predicate(), message)
    }

    @Test
    fun duplicatingAnAssociatedProfileResetsTheAssociation() {
        val state = newState()
        val assoc = profile("assoc", extensions = listOf("app"), always = true)
        assertTrue(state.saveLogFormatProfile(assoc).isSuccess)
        val copy = assertNotNull(state.duplicateLogFormatProfile("assoc"))
        assertFalse(copy.detection.alwaysUseForExtensions)
        assertEquals(listOf("app"), copy.detection.extensions)
        val builtinCopy = assertNotNull(state.duplicateLogFormatProfile("android-logcat"))
        assertFalse(builtinCopy.detection.alwaysUseForExtensions)
    }

    @Test
    fun openedTabKeepsCandidatesUntilTheUserDecidesAndKeepPlainTextClearsThem() {
        val state = newState()
        val tabId = openTiedFile(state)
        val tab = assertNotNull(state.tab(tabId))
        assertEquals(listOf("tie-a", "tie-b"), tab.logFormatCandidateIds)
        assertEquals(LogFormatEngine.PLAIN_TEXT, state.pinnedLogFormatProfile(tabId)?.engine)
        state.resolveLogFormatCandidate(tabId, null)
        assertTrue(assertNotNull(state.tab(tabId)).logFormatCandidateIds.isEmpty())
    }

    @Test
    fun choosingACandidateOpensAPinnedTabAndClosesTheUntouchedSource() {
        val state = newState()
        val tabId = openTiedFile(state)
        var result: Result<String?>? = null
        state.resolveLogFormatCandidate(tabId, "tie-b") { result = it }
        waitUntil("the reparse should finish") { result != null && state.tab(tabId) == null }
        val newId = assertNotNull(result?.getOrNull())
        assertEquals("tie-b", state.tab(newId)?.logFormatProfileId)
        assertTrue(state.tab(newId)?.logFormatCandidateIds.orEmpty().isEmpty())
    }

    @Test
    fun choosingACandidateKeepsASourceTabThatHasNotes() {
        val state = newState()
        val tabId = openTiedFile(state)
        state.upAnn(tabId) { it.copy(annotations = it.annotations.copy(blocks = listOf(AnnBlock.Note("n1", "my note")))) }
        var result: Result<String?>? = null
        state.resolveLogFormatCandidate(tabId, "tie-a") { result = it }
        waitUntil("the reparse should finish") { result != null }
        val newId = assertNotNull(result?.getOrNull())
        assertEquals("tie-a", state.tab(newId)?.logFormatProfileId)
        val source = assertNotNull(state.tab(tabId))
        assertTrue(source.logFormatCandidateIds.isEmpty())
        assertFalse(source.logFormatProfileId == "tie-a", "the source tab stays on its plain-text profile")
    }

    @Test
    fun choosingACandidateKeepsAuthoredNoteSectionsWithoutBlocks() {
        val state = newState()
        val edits: List<(Annotations) -> Annotations> = listOf(
            { it.copy(prefix = "Investigation notes") },
            { it.copy(suffix = "Follow up on the failure") },
            { it.copy(issueDescription = "Private issue details") },
        )
        for (edit in edits) {
            val tabId = openTiedFile(state)
            state.upAnn(tabId) { it.copy(annotations = edit(it.annotations)) }
            val authored = assertNotNull(state.tab(tabId)).annotations
            var result: Result<String?>? = null
            state.resolveLogFormatCandidate(tabId, "tie-a") { result = it }

            waitUntil("the reparse should finish") { result != null }
            val newId = assertNotNull(result?.getOrNull())
            assertEquals("tie-a", state.tab(newId)?.logFormatProfileId)
            assertEquals(authored, assertNotNull(state.tab(tabId)).annotations)
            assertTrue(state.tab(tabId)!!.logFormatCandidateIds.isEmpty())
            state.closeTab(newId)
            state.closeTab(tabId)
        }
    }

    @Test
    fun chooserStripListsCandidatesAndKeepPlainTextRemovesIt() {
        val state = newState()
        val tabId = openTiedFile(state)
        rule.setContent {
            Box(Modifier.size(1180.dp, 760.dp)) {
                state.tab(tabId)?.let { FileView(state, it) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab-log-format-chooser").assertIsDisplayed()
        rule.onNodeWithTag("tab-log-format-candidate-tie-a").assertIsDisplayed()
        rule.onNodeWithTag("tab-log-format-candidate-tie-b").assertIsDisplayed()
        rule.onNodeWithTag("tab-log-format-candidate-keep").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-log-format-chooser").assertDoesNotExist()
        assertTrue(assertNotNull(state.tab(tabId)).logFormatCandidateIds.isEmpty())
    }
}
