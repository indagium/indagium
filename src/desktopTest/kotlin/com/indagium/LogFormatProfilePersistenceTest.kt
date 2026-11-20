package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.FilterMode
import com.indagium.model.LogCapability
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogProfileValueType
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ParsedTime
import com.indagium.model.TabContentState
import com.indagium.model.deriveLogCapabilities
import com.indagium.ui.AppState
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.utils.MAX_PROFILE_NAME_CHARS
import com.indagium.utils.listArchiveLogCandidates
import com.indagium.utils.logFormatProfileJson
import com.indagium.utils.logFormatProfilesJson
import com.indagium.utils.validateLogFormatProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogFormatProfilePersistenceTest {
    private fun newState(
        directory: File,
        restore: Boolean = false,
        monotonicNowNanos: () -> Long = System::nanoTime,
    ): AppState = AppState(
        autosaveFile = File(directory, "state.cache"),
        restoreOnCreate = restore,
        autoExportNotes = false,
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archive-cache"),
        monotonicNowNanos = monotonicNowNanos,
    )

    private fun sampleRows(): String = listOf(
        """{"timestamp":"2026-05-04T12:30:00Z","level":"INFO","message":"first","trace":"trace-a"}""",
        """{"timestamp":"2026-05-04T12:30:01Z","level":"ERROR","message":"second","trace":"trace-b"}""",
    ).joinToString("\n", postfix = "\n")

    private fun customJsonProfile(id: String = "profile-persistence"): LogFormatProfile = LogFormatProfile(
        id = id,
        name = "Persistence JSON",
        description = "A test profile with source fields and an extra trace attribute.",
        engine = LogFormatEngine.JSON_LINES,
        attributes = listOf(LogFormatAttribute("traceId", "Trace ID", "trace", LogProfileValueType.TEXT)),
    )

    private suspend fun awaitLoaded(state: AppState, tabId: String) = withTimeout(TIMEOUT_MS) {
        while (state.tab(tabId) == null && state.isLoadInFlight(tabId)) delay(POLL_MS)
        state.awaitTabLoaded(tabId)
            ?: error("Tab $tabId failed to load: ${state.tab(tabId)?.contentLoadError ?: "it was not published or loading completed without a tab"}")
    }

    private suspend fun waitUntil(message: String, predicate: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!predicate()) delay(POLL_MS)
        assertTrue(predicate(), message)
    }

    @Test
    fun profileSnapshotSurvivesRegistryEditsDeletionUnloadReloadAndAutosaveRestore() = runBlocking {
        val directory = createTempDirectory("log-format-pinned-restore").toFile()
        val source = File(directory, "service.jsonl").apply { writeText(sampleRows()) }
        var now = 0L
        val app = newState(directory, monotonicNowNanos = { now })
        try {
            val profile = customJsonProfile()
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val tabId = assertNotNull(app.openFileWithLogFormatProfile(source, profile.id))
            val opened = awaitLoaded(app, tabId)
            assertEquals(profile.id, opened.logFormatProfileId)
            assertEquals(profile, app.pinnedLogFormatProfileResult(tabId).getOrThrow())

            val changed = app.saveLogFormatProfile(profile.copy(name = "Edited JSON profile"))
            assertTrue(changed.isSuccess)
            assertTrue(app.deleteLogFormatProfile(profile.id))
            assertNull(app.logFormatProfile(profile.id), "the global library no longer contains the custom profile")
            assertEquals(profile, app.pinnedLogFormatProfileResult(tabId).getOrThrow(), "the open tab stays pinned to its original snapshot")

            app.tabs = app.tabs + mkTab("visible", "visible.log", listOf(LogEntry(1, "", LogLevel.I, "", "visible")))
            app.activateTab(tabId)
            app.activateTab("visible")
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 1)
            now = TimeUnit.MINUTES.toNanos(2)
            app.sweepInactiveTabs()
            waitUntil("the inactive profile tab should release its rows") {
                app.sweepInactiveTabs()
                app.tab(tabId)?.contentState == TabContentState.UNLOADED
            }
            val reloaded = awaitLoaded(app, tabId)
            assertEquals(profile.id, reloaded.logFormatProfileId)
            assertEquals(profile, app.pinnedLogFormatProfileResult(tabId).getOrThrow())
            assertEquals(listOf("trace-a", "trace-b"), reloaded.logData.map { it.profileFields.getValue("traceId") })
            app.autosaveNow()
        } finally {
            app.close()
        }

        val restored = newState(directory, restore = true)
        try {
            assertTrue(restored.settings.customLogFormatProfiles.none { it.id == "profile-persistence" })
            restored.startPendingRestoredTabLoads()
            val restoredTab = restored.tabs.single()
            val rows = awaitLoaded(restored, restoredTab.id)
            assertEquals("profile-persistence", rows.logFormatProfileId)
            assertEquals("Persistence JSON", restored.pinnedLogFormatProfileResult(rows.id).getOrThrow()?.name)
            assertEquals(listOf("trace-a", "trace-b"), rows.logData.map { it.profileFields.getValue("traceId") })
            assertTrue(rows.logCapabilities[LogCapability.TEXT_SEARCH]?.availability?.name == "AVAILABLE")
        } finally {
            restored.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun pinnedProfileSnapshotIsDecodedOncePerSnapshotNotOncePerAsk() = runBlocking {
        val directory = createTempDirectory("log-format-pinned-memo").toFile()
        val source = File(directory, "service.jsonl").apply { writeText(sampleRows()) }
        val app = newState(directory)
        try {
            val profile = customJsonProfile()
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val tabId = assertNotNull(app.openFileWithLogFormatProfile(source, profile.id))
            awaitLoaded(app, tabId)
            val first = assertNotNull(app.pinnedLogFormatProfile(tabId))
            assertEquals(profile, first)
            // The log view asks on every recomposition: the same decoded profile comes back, not a new parse.
            assertSame(first, app.pinnedLogFormatProfile(tabId))
            assertSame(first, app.pinnedLogFormatProfileResult(tabId).getOrThrow())
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun mergingTabsOfOneProfileCarriesCapabilitiesAndFieldNamesDerivedFromTheMergedRows() = runBlocking {
        val directory = createTempDirectory("log-format-merge-derived").toFile()
        val first = File(directory, "first.jsonl").apply { writeText(sampleRows()) }
        val second = File(directory, "second.jsonl").apply {
            writeText(sampleRows().replace("12:30:0", "12:31:0").replace("trace-", "other-"))
        }
        val app = newState(directory)
        try {
            val profile = customJsonProfile()
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val firstId = assertNotNull(app.openFileWithLogFormatProfile(first, profile.id))
            val secondId = assertNotNull(app.openFileWithLogFormatProfile(second, profile.id))
            awaitLoaded(app, firstId)
            awaitLoaded(app, secondId)

            app.mergeTabs(listOf(firstId, secondId), "Merged JSON")
            waitUntil("the merged tab should be published") { app.tabs.size == 3 && !app.isLoading }
            val merged = app.tabs.last()
            assertEquals(4, merged.logData.size)
            assertEquals(profile.id, merged.logFormatProfileId)
            val expected = deriveLogCapabilities(profile, merged.logData, sourceLookupSupported = false)
            assertEquals(expected.mapValues { it.value.availability }, merged.logCapabilities.mapValues { it.value.availability })
            assertEquals(merged.logData.flatMap { it.profileFields.keys }.toSet(), merged.profileFieldNames)
            assertTrue("traceId" in merged.profileFieldNames || merged.profileFieldNames.isNotEmpty())
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun profileLibraryLimitRejectsNewAndDuplicateButAllowsEditsAndAutosave() {
        val directory = createTempDirectory("log-format-library-limit").toFile()
        val autosave = File(directory, "state.cache")
        val app = newState(directory)
        val profiles = (1..200).map { index ->
            customJsonProfile("limit-profile-$index").copy(name = "Limit profile $index")
        }
        try {
            app.settings = app.settings.copy(customLogFormatProfiles = profiles)
            app.autosaveNow()
            assertNull(app.autosaveError)

            val edited = app.saveLogFormatProfile(profiles.first().copy(name = "Edited at limit")).getOrThrow()
            assertEquals("Edited at limit", edited.name)
            assertEquals(200, app.settings.customLogFormatProfiles.size)
            app.autosaveNow()
            val savedAutosave = autosave.readText()

            val rejectedSave = app.saveLogFormatProfile(customJsonProfile("limit-profile-new"))
            assertTrue(rejectedSave.isFailure)
            assertTrue(rejectedSave.exceptionOrNull()?.message.orEmpty().contains("limited to 200"))
            assertEquals(200, app.settings.customLogFormatProfiles.size)
            assertEquals(savedAutosave, autosave.readText(), "a rejected save must leave the last valid autosave intact")

            assertNull(app.duplicateLogFormatProfile(profiles.last().id))
            assertTrue(app.logFormatProfileError.orEmpty().contains("limited to 200"))
            assertEquals(200, app.settings.customLogFormatProfiles.size)
            assertEquals(savedAutosave, autosave.readText(), "a rejected duplicate must leave the last valid autosave intact")
            assertNull(app.autosaveError)
        } finally {
            app.close()
        }

        val restored = newState(directory, restore = true)
        try {
            assertEquals(200, restored.settings.customLogFormatProfiles.size)
            assertEquals("Edited at limit", restored.logFormatProfile(profiles.first().id)?.name)
            assertNull(restored.autosaveError)
        } finally {
            restored.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedOrMismatchedPinnedSnapshotFailsWithoutRegistryFallback() = runBlocking {
        val directory = createTempDirectory("log-format-invalid-snapshot").toFile()
        val app = newState(directory)
        try {
            val profile = customJsonProfile("snapshot-check")
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val unsupportedSchema = logFormatProfileJson(profile).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
            assertTrue(unsupportedSchema != logFormatProfileJson(profile))
            val badSnapshots = listOf(
                Triple("malformed", "{not-json", "snapshot"),
                Triple("mismatched", logFormatProfileJson(profile.copy(id = "different-snapshot-id")), "ID does not match"),
                Triple("unsupported-schema", unsupportedSchema, "snapshot"),
            )
            for ((label, snapshot, expectedError) in badSnapshots) {
                val source = File(directory, "$label.jsonl").apply { writeText(sampleRows()) }
                val tabId = assertNotNull(app.openFileWithLogFormatProfile(source, profile.id))
                val loaded = awaitLoaded(app, tabId)
                val corrupt = loaded.copy(logFormatProfileSnapshot = snapshot)
                val shell = assertNotNull(corrupt.tabToken().tabShellFromToken()).tab
                app.tabs = listOf(shell)

                val pinned = app.pinnedLogFormatProfileResult(tabId)
                assertTrue(pinned.isFailure, label)
                assertTrue(pinned.exceptionOrNull()?.message.orEmpty().contains(expectedError, ignoreCase = true), label)
                assertNotNull(app.logFormatProfile(profile.id), "a matching global definition must not replace the $label pin")

                val failed = app.awaitTabLoaded(tabId)
                assertNull(failed, label)
                assertEquals(TabContentState.FAILED, app.tab(tabId)?.contentState, label)
                assertTrue(app.tab(tabId)?.contentLoadError.orEmpty().contains(expectedError, ignoreCase = true), label)
            }
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun annotationLogExcerptRoundTripsProfileClockDisplayAndLegacyNullMetadata() {
        val rich = LogEntry(
            id = 7,
            ts = "2026-05-04T12:30:00",
            level = LogLevel.V,
            tag = "api",
            msg = "first",
            dayOfYear = 123,
            recordMetadata = LogRecordMetadata(
                profileFields = mapOf("traceId" to "trace-a"),
                displayFields = mapOf("traceId" to "trace-a"),
                displayColumns = listOf(LogDisplayField("traceId", "Trace ID", "trace-a")),
                originalSeverity = "NOTICE",
                severityKnown = false,
                parsedTime = ParsedTime(
                    original = "2026-05-04T12:30:00",
                    localTime = "12:30:00",
                    clockIdentity = "source-a",
                    localWallMillis = 45_000_000,
                ),
                rawRecord = "source record",
                sourceStartLine = 20,
                sourceEndLine = 21,
                processIdentity = "worker-1",
                threadIdentity = "thread-A",
            ),
        )
        val legacy = LogEntry(8, "12:31:00", LogLevel.I, "old", "legacy", dayOfYear = 124)
        val annotations = Annotations(
            prefix = "Excerpt",
            blocks = listOf(
                AnnBlock.LogRef("rich", listOf(rich.id), "Profile row", sourceEntries = listOf(rich)),
                AnnBlock.LogRef("legacy", listOf(legacy.id), "Legacy row", sourceEntries = listOf(legacy)),
            ),
        )

        val restored = assertNotNull(annotations.annotationsToken().annotationsFromToken())
        val richRoundTrip = assertNotNull((restored.blocks[0] as AnnBlock.LogRef).sourceEntries?.single())
        assertEquals(rich, richRoundTrip)
        assertEquals(45_000_000L, richRoundTrip.parsedTime?.localWallMillis)
        assertEquals(listOf(LogDisplayField("traceId", "Trace ID", "trace-a")), richRoundTrip.recordMetadata?.displayColumns)
        val legacyRoundTrip = assertNotNull((restored.blocks[1] as AnnBlock.LogRef).sourceEntries?.single())
        assertEquals(124.toShort(), legacyRoundTrip.dayOfYear)
        assertNull(legacyRoundTrip.recordMetadata, "legacy excerpt rows keep the no-metadata representation")
    }

    @Test
    fun profileImportPreservesIdsResolvesConflictsAndRejectsInvalidBundlesAtomically() {
        val directory = createTempDirectory("log-format-import-atomic").toFile()
        val app = newState(directory)
        try {
            val imported = customJsonProfile("stable-import-id")
            val first = importProfiles(app, directory, listOf(imported), updateConflicts = false).single()
            assertEquals(imported.id, first.id)

            val renamed = importProfiles(app, directory, listOf(imported.copy(name = "Second copy")), updateConflicts = false).single()
            assertTrue(renamed.id != imported.id)
            assertEquals("Second copy", renamed.name)

            val updated = importProfiles(app, directory, listOf(imported.copy(name = "Updated")), updateConflicts = true).single()
            assertEquals(imported.id, updated.id)
            assertEquals("Updated", updated.name)
            assertTrue(updated.revision > first.revision)

            val builtIn = app.logFormatProfile("plain-text")
            val importedBuiltInId = importProfiles(
                app,
                directory,
                listOf(imported.copy(id = "plain-text", name = "Imported Plain Text")),
                updateConflicts = true,
            ).single()
            assertTrue(importedBuiltInId.id != "plain-text")
            assertEquals(builtIn, app.logFormatProfile("plain-text"), "imports never replace a reserved built-in ID")

            val beforeFailure = app.settings.customLogFormatProfiles.toList()
            // The encoder refuses an invalid profile, so the bundle is broken after encoding.
            val soonInvalid = LogFormatProfile(
                id = "invalid-regex",
                name = "Invalid regex",
                description = "This item makes the bundle invalid.",
                engine = LogFormatEngine.REGEX,
                regexPattern = "placeholder-pattern",
            )
            val invalidBundle = logFormatProfilesJson(listOf(customJsonProfile("bundle-valid"), soonInvalid))
                .replace("placeholder-pattern", "(")
            assertTrue("\"regexPattern\":\"(\"" in invalidBundle)
            val failed = importProfileJson(
                app,
                directory,
                invalidBundle,
                updateConflicts = false,
            )
            assertTrue(failed.isFailure)
            assertEquals(beforeFailure, app.settings.customLogFormatProfiles, "validation happens before any bundle mutation")
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun compressedAndArchiveProfilesSurviveReparseAndRestartAfterGlobalDeletion() = runBlocking {
        val directory = createTempDirectory("log-format-compressed-archive-restore").toFile()
        val compressed = File(directory, "service.jsonl.gz")
        GzipCompressorOutputStream(compressed.outputStream()).bufferedWriter(Charsets.UTF_8).use { it.write(sampleRows()) }
        val archive = File(directory, "logs.zip")
        writeZipEntry(archive, "nested/service.jsonl", sampleRows())
        val app = newState(directory)
        try {
            val profile = customJsonProfile("compressed-archive-profile")
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)

            val compressedId = assertNotNull(app.openFileWithLogFormatProfile(compressed, profile.id))
            val compressedTab = awaitLoaded(app, compressedId)
            assertEquals(profile.id, compressedTab.logFormatProfileId)
            assertEquals("trace-b", compressedTab.logData.last().profileFields["traceId"])

            val candidate = listArchiveLogCandidates(archive).single()
            val archiveId = assertNotNull(app.openArchiveEntryWithLogFormatProfile(archive, candidate, profile.id))
            val archiveTab = awaitLoaded(app, archiveId)
            assertEquals(profile.id, archiveTab.logFormatProfileId)
            assertEquals("trace-a", archiveTab.logData.first().profileFields["traceId"])

            app.upTab(archiveId) { tab ->
                tab.copy(
                    selected = setOf(tab.logData.first().id),
                    manualBlocks = listOf(
                        com.indagium.model.ManualCollapseBlock(
                            "old-fold",
                            tab.logData.first().id,
                            com.indagium.model.ManualCollapseDirection.TO_START,
                        ),
                    ),
                )
            }
            app.upFlt(archiveId) { it.copy(mode = FilterMode.KEYWORD, kwText = "stale filter") }
            app.upAnn(archiveId) { tab ->
                tab.copy(annotations = tab.annotations.copy(blocks = listOf(AnnBlock.Note("old-note", "Old tab note"))))
            }
            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(archiveId, profile.id))
            val reparsed = assertNotNull(app.tab(reparsedId))
            assertTrue(reparsedId != archiveId)
            assertEquals(profile.id, reparsed.logFormatProfileId)
            assertTrue(reparsed.selected.isEmpty())
            assertTrue(reparsed.manualBlocks.isEmpty())
            assertTrue(reparsed.annotations.blocks.isEmpty())
            assertEquals("", reparsed.filter.kwText)
            assertEquals("stale filter", app.tab(archiveId)?.filter?.kwText)

            assertTrue(app.deleteLogFormatProfile(profile.id))
            app.autosaveNow()
        } finally {
            app.close()
        }

        val restored = newState(directory, restore = true)
        try {
            assertNull(restored.logFormatProfile("compressed-archive-profile"))
            restored.startPendingRestoredTabLoads()
            val restoredTabs = restored.tabs.toList()
            assertEquals(3, restoredTabs.size)
            restoredTabs.forEach { shell ->
                val loaded = awaitLoaded(restored, shell.id)
                assertEquals("compressed-archive-profile", loaded.logFormatProfileId)
                assertEquals("trace-a", loaded.logData.first().profileFields["traceId"])
                assertEquals("trace-b", loaded.logData.last().profileFields["traceId"])
                assertEquals("Persistence JSON", restored.pinnedLogFormatProfileResult(shell.id).getOrThrow()?.name)
            }
        } finally {
            restored.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun oneInvalidProfileInSettingsKeepsTheValidOnesAroundIt() {
        val settings = AppSettings(
            customLogFormatProfiles = listOf(
                customJsonProfile("keep-first").copy(name = "Keep first"),
                customJsonProfile("bad-one").copy(name = "Bad one"),
                customJsonProfile("keep-last").copy(name = "Keep last"),
            ),
        )
        val json = settings.settingsJson()
        val broken = json.replace("\"id\":\"bad-one\"", "\"id\":\"bad one!\"")
        assertTrue(broken != json, "the fixture must actually break the middle profile")
        val restored = assertNotNull(settingsFromJson(broken))
        assertEquals(listOf("keep-first", "keep-last"), restored.customLogFormatProfiles.map { it.id })

        val duplicated = json.replace("\"id\":\"keep-last\"", "\"id\":\"keep-first\"")
        assertEquals(
            listOf("keep-first", "bad-one"),
            assertNotNull(settingsFromJson(duplicated)).customLogFormatProfiles.map { it.id },
            "a repeated id keeps the first profile and drops the later one",
        )
    }

    @Test
    fun duplicatingALongNamedProfileKeepsEveryCopyValidAndWithinTheNameLimit() {
        val directory = createTempDirectory("log-format-long-name").toFile()
        val app = newState(directory)
        try {
            val longName = "N".repeat(MAX_PROFILE_NAME_CHARS)
            app.settings = app.settings.copy(
                customLogFormatProfiles = listOf(customJsonProfile("long-name").copy(name = longName)),
            )
            val first = assertNotNull(app.duplicateLogFormatProfile("long-name"))
            val second = assertNotNull(app.duplicateLogFormatProfile("long-name"))
            assertTrue(first.name.length <= MAX_PROFILE_NAME_CHARS && first.name.endsWith(" copy"), first.name)
            assertTrue(second.name.length <= MAX_PROFILE_NAME_CHARS && second.name.endsWith(" copy 2"), second.name)
            assertTrue(first.name != second.name && first.name != longName)
            validateLogFormatProfile(first)
            validateLogFormatProfile(second)
            assertNull(app.logFormatProfileError)
            assertEquals(3, app.settings.customLogFormatProfiles.size)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun importRenameOfALongNamedProfileStaysWithinTheNameLimit() {
        val directory = createTempDirectory("log-format-long-import").toFile()
        val app = newState(directory)
        try {
            val longName = "M".repeat(MAX_PROFILE_NAME_CHARS)
            app.settings = app.settings.copy(
                customLogFormatProfiles = listOf(customJsonProfile("long-import").copy(name = longName)),
            )
            val applied = importProfiles(
                app, directory, listOf(customJsonProfile("long-import-other").copy(name = longName)), updateConflicts = false,
            ).single()
            assertTrue(applied.name.length <= MAX_PROFILE_NAME_CHARS && applied.name != longName, applied.name)
            validateLogFormatProfile(applied)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    private fun importProfiles(
        state: AppState,
        directory: File,
        profiles: List<LogFormatProfile>,
        updateConflicts: Boolean,
    ): List<LogFormatProfile> = importProfileJson(
        state,
        directory,
        logFormatProfilesJson(profiles),
        updateConflicts,
    ).getOrThrow()

    private fun importProfileJson(
        state: AppState,
        directory: File,
        json: String,
        updateConflicts: Boolean,
    ): Result<List<LogFormatProfile>> {
        val file = File(directory, "profiles-${System.nanoTime()}.json")
        file.writeText(json)
        return try {
            state.importLogFormatProfilesFrom(file, updateConflicts)
        } finally {
            file.delete()
        }
    }

    private fun writeZipEntry(file: File, entryPath: String, text: String) {
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(entryPath))
            output.write(text.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 10L
    }
}
