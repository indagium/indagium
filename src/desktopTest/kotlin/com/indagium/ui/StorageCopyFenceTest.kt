package com.indagium.ui

import com.indagium.model.Filter
import com.indagium.model.LogFormat
import com.indagium.storage.SourceId
import com.indagium.storage.StackFileSession
import com.indagium.storage.EntryId
import com.indagium.storage.SelectionOverride
import com.indagium.storage.SelectionBase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StorageCopyFenceTest {
    @Test
    fun changedSelectionRejectsAQueuedCopy(): Unit = runBlocking {
        val home = Files.createTempDirectory("storage-copy-fence-")
        val path = home.resolve("generated.log")
        Files.writeString(path, "01-01 12:00:00.000 1 1 I App: first\n01-01 12:00:00.001 1 1 I App: second\n")
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val state = AppState(
            autosaveFile = home.resolve("autosave").toFile(), notesDir = home.resolve("notes").toFile(),
            archiveCacheDir = home.resolve("archives").toFile(), customCommandsDir = home.resolve("commands").toFile(),
            controlTokenFile = home.resolve("token").toFile(), sourceIndexFile = home.resolve("index").toFile(),
            diagramLibraryStore = DiagramLibraryStore(home.resolve("diagrams").toFile()),
            autoExportNotes = false, ioDispatcher = dispatcher,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val tabId = state.openStorageFileForTesting(path.toFile())
            val session = StackFileSession.open(SourceId(tabId), path, Filter(), LogFormat.LOGCAT, false, pageRows = 2)
            assertTrue(state.attachExperimentalStorageSession(tabId, session))
            state.selRow(tabId, 1, false, false)
            val snapshot = assertNotNull(state.tab(tabId))
            val published = AtomicInteger()
            state.experimentalStorageCopyPublisher = { published.incrementAndGet() }
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val job = assertNotNull(state.copyExperimentalStorageSelection(tabId, session, snapshot))
            state.selRow(tabId, 2, false, false)
            release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(0, published.get())
            state.selectAllExperimentalStorageRows(tabId)
            state.selectExperimentalStorageRow(tabId, 0, 1, multi = true, range = false, header = false)
            assertEquals(SelectionOverride.EXCLUDED, state.experimentalStorageSelection(tabId)?.overrideFor(EntryId(1)))
            val text = AtomicReference<String>()
            state.experimentalStorageCopyPublisher = { text.set(it) }
            val selected = assertNotNull(state.tab(tabId))
            val copy = assertNotNull(state.copyExperimentalStorageSelection(tabId, session, selected))
            withTimeout(10_000) { copy.join() }
            assertTrue(text.get()?.contains("second") == true)
            assertTrue(text.get()?.contains("first") == false)
            state.selectExperimentalStorageRow(tabId, 0, 1, multi = false, range = false, header = false)
            state.selectExperimentalStorageRow(tabId, 1, 2, multi = false, range = true, header = false)
            withTimeout(10_000) {
                while (state.experimentalStorageSelection(tabId)?.base !is SelectionBase.DisplayRange) delay(10)
            }
            text.set(null)
            val rangeCopy = assertNotNull(state.copyExperimentalStorageSelection(tabId, session, assertNotNull(state.tab(tabId))))
            withTimeout(10_000) { rangeCopy.join() }
            assertTrue(text.get()?.let { "first" in it && "second" in it } == true)
            val output = home.resolve("output.txt")
            Files.writeString(output, "original")
            val export = assertNotNull(state.exportExperimentalStorageTo(tabId, session, output.toFile(), csv = false))
            withTimeout(10_000) { export.join() }
            assertTrue(Files.readString(output).let { "first" in it && "second" in it })
            Files.writeString(output, "must remain")
            val exportEntered = CountDownLatch(1)
            val exportRelease = CountDownLatch(1)
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { exportEntered.countDown(); exportRelease.await(5, TimeUnit.SECONDS) })
            assertTrue(exportEntered.await(5, TimeUnit.SECONDS))
            val staleExport = assertNotNull(state.exportExperimentalStorageTo(tabId, session, output.toFile(), csv = false))
            state.setKw(tabId, "changed")
            exportRelease.countDown()
            withTimeout(10_000) { staleExport.join() }
            assertEquals("must remain", Files.readString(output))
        } finally {
            release.countDown()
            state.close()
            dispatcher.close()
            home.toFile().deleteRecursively()
        }
    }
}
