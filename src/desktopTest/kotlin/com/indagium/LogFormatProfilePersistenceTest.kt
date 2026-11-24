package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.FilterMode
import com.indagium.model.LogCapability
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
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
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFalse
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
    fun distinctProfilesOfOneLogKeepTheirOwnNotesAcrossCloseAndReopen() = runBlocking {
        val directory = createTempDirectory("profile-note-owner").toFile()
        val source = File(directory, "events.jsonl").apply { writeText(sampleRows()) }
        val app = AppState(
            autosaveFile = File(directory, "state.cache"), restoreOnCreate = false, autoExportNotes = true,
            notesDir = File(directory, "notes"),
        )
        try {
            app.updateSettings { it.copy(autoExportNotes = true) }
            val first = assertNotNull(app.openFileWithLogFormatProfile(source, "plain-text"))
            awaitLoaded(app, first)
            assertNotNull(app.addNoteBlock(first, "plain note"))
            val firstName = assertNotNull(app.tab(first)?.noteTargetName)
            val firstFile = File(directory, "notes/$firstName")
            waitUntil("first note exported") { firstFile.isFile && firstFile.readText().contains("plain note") }

            val second = assertNotNull(app.openFileWithLogFormatProfile(source, "json-lines"))
            awaitLoaded(app, second)
            delay(300)
            assertTrue(app.tab(second)!!.annotations.blocks.isEmpty(), "another profile must not adopt the saved note")
            assertNotNull(app.addNoteBlock(second, "json note"))
            val secondName = assertNotNull(app.tab(second)?.noteTargetName)
            assertTrue(firstName != secondName)
            val secondFile = File(directory, "notes/$secondName")
            waitUntil("second note exported") { secondFile.isFile && secondFile.readText().contains("json note") }

            app.closeTab(first)
            app.closeTab(second)
            val reopenedFirst = assertNotNull(app.openFileWithLogFormatProfile(source, "plain-text"))
            val reopenedSecond = assertNotNull(app.openFileWithLogFormatProfile(source, "json-lines"))
            awaitLoaded(app, reopenedFirst)
            awaitLoaded(app, reopenedSecond)
            waitUntil("both notes autoload") {
                app.tab(reopenedFirst)?.annotations?.blocks?.isNotEmpty() == true &&
                    app.tab(reopenedSecond)?.annotations?.blocks?.isNotEmpty() == true
            }
            assertEquals("plain note", (app.tab(reopenedFirst)!!.annotations.blocks.single() as AnnBlock.Note).text)
            assertEquals("json note", (app.tab(reopenedSecond)!!.annotations.blocks.single() as AnnBlock.Note).text)
            assertTrue(firstFile.readText().contains("plain note"))
            assertTrue(secondFile.readText().contains("json note"))
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun archiveEntryNotesStayWithTheirOwningProfile() = runBlocking {
        val directory = createTempDirectory("archive-profile-note-owner").toFile()
        val archive = File(directory, "logs.zip")
        writeZipEntry(archive, "nested/events.jsonl", sampleRows())
        val candidate = listArchiveLogCandidates(archive).single()
        val notes = File(directory, "notes")
        val app = AppState(autosaveFile = File(directory, "state.cache"), notesDir = notes, ioDispatcher = Dispatchers.Unconfined)
        try {
            app.updateSettings { it.copy(autoExportNotes = true) }
            val plain = assertNotNull(app.openArchiveEntryWithLogFormatProfile(archive, candidate, "plain-text"))
            awaitLoaded(app, plain)
            assertNotNull(app.addLogRefBlock(plain, listOf(1), caption = "Plain text evidence"))
            val plainName = assertNotNull(app.tab(plain)?.noteTargetName)
            val plainNote = File(notes, plainName)
            val plainSidecar = File(notes, "${plainNote.nameWithoutExtension}.ann")
            waitUntil("plain text note exported") { plainSidecar.isFile && plainSidecar.readText().annotationsFromToken() != null }
            app.closeTab(plain)

            val json = assertNotNull(app.openArchiveEntryWithLogFormatProfile(archive, candidate, "json-lines"))
            awaitLoaded(app, json)
            assertTrue(app.tab(json)!!.annotations.blocks.isEmpty())
            assertNotNull(app.addNoteBlock(json, "JSON Lines evidence"))
            val jsonName = assertNotNull(app.tab(json)?.noteTargetName)
            assertTrue(plainName != jsonName)
            val jsonNote = File(notes, jsonName)
            val jsonSidecar = File(notes, "${jsonNote.nameWithoutExtension}.ann")
            waitUntil("JSON Lines note exported") { jsonSidecar.isFile && jsonSidecar.readText().annotationsFromToken() != null }
            app.closeTab(json)

            val reopened = assertNotNull(app.openArchiveEntryWithLogFormatProfile(archive, candidate, "plain-text"))
            awaitLoaded(app, reopened)
            waitUntil("plain text note autoloaded") { app.tab(reopened)?.annotations?.blocks?.isNotEmpty() == true }
            val citation = app.tab(reopened)!!.annotations.blocks.single() as AnnBlock.LogRef
            assertEquals("Plain text evidence", citation.caption)
            assertNull(citation.sourceTabId)
            assertEquals(plainName, app.tab(reopened)?.noteTargetName)
            assertTrue(plainNote.readText().contains("Plain text evidence"))
            assertTrue(jsonNote.readText().contains("JSON Lines evidence"))
        } finally {
            app.close()
            directory.deleteRecursively()
        }
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
    fun remergingAMixedProfileTabStillChecksItsClock() = runBlocking {
        val directory = createTempDirectory("log-format-remerge-clock").toFile()
        val app = newState(directory)
        try {
            val first = File(directory, "first.jsonl").apply { writeText(sampleRows()) }
            val second = File(directory, "second.jsonl").apply { writeText(sampleRows()) }
            val firstId = assertNotNull(app.openFileWithLogFormatProfile(first, customJsonProfile("first")))
            val secondId = assertNotNull(app.openFileWithLogFormatProfile(second, customJsonProfile("second")))
            awaitLoaded(app, firstId)
            awaitLoaded(app, secondId)
            app.mergeTabs(listOf(firstId, secondId), "Mixed profile merge")
            waitUntil("the mixed-profile merge should be published") { app.tabs.any { it.filename == "Mixed profile merge" } }
            val merged = app.tabs.single { it.filename == "Mixed profile merge" }
            assertNull(merged.logFormatProfileSnapshot)
            assertEquals("absolute", merged.profileClockSignature)

            app.mergeTabs(listOf(merged.id, firstId), "Compatible remerge")
            waitUntil("the compatible remerge should be published") { app.tabs.any { it.filename == "Compatible remerge" } }
            assertEquals("absolute", app.tabs.single { it.filename == "Compatible remerge" }.profileClockSignature)

            val legacy = mkTab("legacy", "plain.log", listOf(LogEntry(1, "10:15:00.000", LogLevel.I, "Tag", "legacy")))
            app.tabs = app.tabs + legacy
            app.mergeTabs(listOf(merged.id, legacy.id), "Incompatible remerge")
            assertEquals("Cannot merge incompatible clocks", app.openError?.title)
            assertTrue(app.tabs.none { it.filename == "Incompatible remerge" })
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
    fun binaryDltReparsingPinsTheProfileThatActuallyParsedTheFile() = runBlocking {
        val directory = createTempDirectory("log-format-reparse-dlt").toFile()
        val source = File(directory, "capture.dlt").apply { writeBytes(dltStorageHeader() + dltTestFrame("binary DLT")) }
        val app = newState(directory)
        try {
            val sourceId = assertNotNull(app.openFile(source))
            val opened = awaitLoaded(app, sourceId)
            val dltProfile = assertNotNull(app.pinnedLogFormatProfileResult(sourceId).getOrThrow())

            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(sourceId, "json-lines"))
            val reparsed = assertNotNull(app.tab(reparsedId))

            assertEquals(LogFormat.DLT, reparsed.logFormat)
            assertEquals("automotive-dlt", reparsed.logFormatProfileId)
            assertEquals(dltProfile, app.pinnedLogFormatProfileResult(reparsedId).getOrThrow())
            assertEquals("${opened.filename} · ${dltProfile.name}", reparsed.filename)
            assertEquals("binary DLT", reparsed.logData.single().msg)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun reparseOnlyChargesNewRowsAgainstAvailableHeap() = runBlocking {
        val directory = createTempDirectory("log-format-reparse-heap").toFile()
        val source = File(directory, "events.jsonl").apply { writeText(sampleRows()) }
        val app = newState(directory)
        try {
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val profile = assertNotNull(app.logFormatProfile("json-lines"))
            val sourceId = assertNotNull(app.openFileWithLogFormatProfile(source, profile.id))
            val opened = awaitLoaded(app, sourceId)
            val newRowsBytes = app.estimatedHeapBytesForProfile(profile, source.length())

            app.heapFreeBytesProvider = { newRowsBytes - 1 }
            assertNull(app.reparseTabWithLogFormatProfileAwait(sourceId, profile.id))
            assertTrue(app.logFormatProfileError.orEmpty().contains("available"))

            app.heapFreeBytesProvider = { newRowsBytes }
            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(sourceId, profile.id))
            assertEquals(opened.logData, app.tab(sourceId)?.logData)
            assertEquals(opened.logData, app.tab(reparsedId)?.logData)
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

    // The saved copy of an update takes max(imported revision, stored revision + 1); that can leave the valid
    // range even though the imported profile was valid, and it has to fail like any other invalid profile.
    @Test
    fun anUpdateImportWhoseBumpedRevisionIsInvalidFailsAndStoresNothing() {
        val directory = createTempDirectory("log-format-import-revision").toFile()
        val app = newState(directory)
        try {
            val stored = customJsonProfile("revision-limit").copy(revision = 100_000)
            val bystander = customJsonProfile("revision-bystander").copy(name = "Bystander")
            app.settings = app.settings.copy(customLogFormatProfiles = listOf(stored, bystander))
            val before = app.settings.customLogFormatProfiles.toList()

            val failed = app.applyImportedLogFormatProfiles(
                listOf(customJsonProfile("revision-bystander").copy(name = "Renamed bystander"), stored.copy(name = "Renamed")),
                updateConflicts = true,
            )

            assertTrue(failed.isFailure)
            assertEquals("Invalid profile revision.", failed.exceptionOrNull()?.message)
            assertEquals(before, app.settings.customLogFormatProfiles, "nothing of a failed import is stored, not even the earlier items")
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    // saveLogFormatProfile refuses a name another custom format already uses; an update import follows the same rule.
    @Test
    fun anUpdateImportCannotTakeAnotherCustomFormatsName() {
        val directory = createTempDirectory("log-format-import-name").toFile()
        val app = newState(directory)
        try {
            val first = customJsonProfile("name-first").copy(name = "First format")
            val second = customJsonProfile("name-second").copy(name = "Second format")
            app.settings = app.settings.copy(customLogFormatProfiles = listOf(first, second))
            val before = app.settings.customLogFormatProfiles.toList()

            val clash = app.applyImportedLogFormatProfiles(listOf(first.copy(name = "SECOND FORMAT")), updateConflicts = true)
            assertTrue(clash.isFailure)
            assertEquals("Another custom format already uses that name.", clash.exceptionOrNull()?.message)
            assertEquals(before, app.settings.customLogFormatProfiles)

            // Keeping its own name, even in another case, is not a clash with itself.
            val own = app.applyImportedLogFormatProfiles(listOf(first.copy(name = "first FORMAT")), updateConflicts = true).getOrThrow().single()
            assertEquals("first FORMAT", own.name)
            assertEquals(2, own.revision)
            assertEquals(listOf("first FORMAT", "Second format"), app.settings.customLogFormatProfiles.map { it.name })
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    // The merge works on the settings updateSettings hands over, and the size limit is checked there too.
    @Test
    fun anImportThatOverflowsTheLibraryFailsAndLeavesTheSettingsAlone() {
        val directory = createTempDirectory("log-format-import-limit").toFile()
        val app = newState(directory)
        try {
            val full = (1..200).map { customJsonProfile("full-$it").copy(name = "Full $it") }
            app.settings = app.settings.copy(customLogFormatProfiles = full)
            val failed = app.applyImportedLogFormatProfiles(listOf(customJsonProfile("one-too-many")), updateConflicts = false)
            assertTrue(failed.isFailure)
            assertEquals(full, app.settings.customLogFormatProfiles)

            // An update of a stored profile does not grow the library, so it is still allowed.
            val updated = app.applyImportedLogFormatProfiles(
                listOf(full.first().copy(description = "Changed")), updateConflicts = true,
            ).getOrThrow().single()
            assertEquals("Changed", updated.description)
            assertEquals(200, app.settings.customLogFormatProfiles.size)
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

    @Test
    fun deletingACustomProfileRemovesOnlyThatOneAndReportsWhetherAnythingWentAway() {
        val directory = createTempDirectory("log-format-delete").toFile()
        val app = newState(directory)
        try {
            val keep = customJsonProfile("keep-profile").copy(name = "Keep me")
            val drop = customJsonProfile("drop-profile").copy(name = "Drop me")
            assertTrue(app.saveLogFormatProfile(keep).isSuccess)
            assertTrue(app.saveLogFormatProfile(drop).isSuccess)
            val before = app.settings

            assertFalse(app.deleteLogFormatProfile("missing-profile"))
            assertFalse(app.deleteLogFormatProfile("plain-text"), "built-in formats cannot be deleted")
            assertSame(before, app.settings, "a refused delete changes no settings")

            assertTrue(app.deleteLogFormatProfile(drop.id))
            assertEquals(listOf("keep-profile"), app.settings.customLogFormatProfiles.map { it.id })
            assertFalse(app.deleteLogFormatProfile(drop.id), "already gone")
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

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
