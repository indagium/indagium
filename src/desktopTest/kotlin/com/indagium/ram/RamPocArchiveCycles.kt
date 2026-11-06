// The fixed cycle schedule and optional forced GC are diagnostic measurements.
@file:Suppress("MagicNumber", "ExplicitGarbageCollectionCall", "CyclomaticComplexMethod", "LongMethod")

package com.indagium.ram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.indagium.model.LogTab
import com.indagium.model.TabContentState
import com.indagium.ui.App
import com.indagium.ui.AppState
import com.indagium.ui.DesktopStorage
import kotlinx.coroutines.delay
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Repeated real archive open/close in a separate synthetic Compose process. */
object RamPocArchiveCycles {
    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // A failed diagnostic must terminate its isolated child with a nonzero status.
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected temporary home and synthetic ZIP" }
        val home = Path.of(args[0]).toAbsolutePath().normalize()
        val tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        require(home.startsWith(tmp) && home != tmp && home.fileName.toString().startsWith("indagium-ram-poc-"))
        require(Files.isDirectory(home) && !Files.isSymbolicLink(home) && home.toRealPath() == home)
        val root = Path.of("build/ram-poc/fixtures").toAbsolutePath().normalize()
        val archive = Path.of(args[1]).toAbsolutePath().normalize()
        require(
            archive.parent == root && archive.fileName.toString() in
                setOf("logcat-100k.zip", "logcat-1m.zip", "logcat-1m-grouped.zip", "logcat-1m-video.zip", "logcat-10m.zip"),
        )
        val expectedRows = mapOf(
            "logcat-100k.zip" to 100_000,
            "logcat-1m.zip" to 1_000_000,
            "logcat-1m-grouped.zip" to 1_000_000,
            "logcat-1m-video.zip" to 1_000_000,
            "logcat-10m.zip" to 10_000_000,
        )
            .getValue(archive.fileName.toString())
        var part = root.root
        for (segment in archive) {
            part = part.resolve(segment)
            require(!Files.isSymbolicLink(part))
        }
        require(Files.isRegularFile(archive) && archive.toRealPath().parent == root.toRealPath())
        DesktopStorage.installDebugAppDataDirOverride(home.resolve("appdata").toString(), true)
        val diagnostic = System.getProperty("indagium.ram.forceGc") == "true"
        val measureFootprint = System.getProperty("indagium.ram.archive.footprint") == "true"
        val measureHistogram = System.getProperty("indagium.ram.archive.histogram") == "true"
        require(!measureHistogram || diagnostic) { "class histogram requires forced-GC diagnostic mode" }
        val grouped = archive.fileName.toString() == "logcat-1m-grouped.zip"
        val video = System.getProperty("indagium.ram.archive.video") == "true"
        require(video == (archive.fileName.toString() == "logcat-1m-video.zip"))
        val holdSnapshots = System.getProperty("indagium.ram.archive.holdSnapshots") == "true"
        val cycles = System.getProperty("indagium.ram.archive.cycles")?.toIntOrNull() ?: 6
        require(cycles in 1..20)
        val computeCache = runCatching {
            Class.forName("com.indagium.utils.FilterKt").getDeclaredField("computeCacheByTab").let {
                it.isAccessible = true
                it.get(null) as Map<*, *>
            }
        }.getOrNull()
        val launchMs = System.getProperty("indagium.ram.launchEpochMs")?.toLongOrNull() ?: System.currentTimeMillis()
        application {
            val state = remember {
                AppState(
                    autosaveFile = home.resolve("autosave.cache").toFile(),
                    notesDir = home.resolve("notes").toFile(),
                    archiveCacheDir = home.resolve("archives").toFile(),
                    customCommandsDir = home.resolve("commands").toFile(),
                    filterBackupsDir = home.resolve("filters").toFile(),
                    platformDefaultSaveRootDir = home.resolve("saved").toFile(),
                ).also { it.acceptLicenseAgreement() }
            }
            val drawn = remember { AtomicBoolean() }
            val drawCount = remember { AtomicInteger() }
            var drawRequest by remember { mutableIntStateOf(0) }
            val snapshots = remember { mutableListOf<LogTab>() }
            val backgroundPins = remember { mutableListOf<AutoCloseable>() }
            val backgroundIds = remember { mutableListOf<String>() }
            Window(
                onCloseRequest = { state.close(); exitApplication() },
                title = "Synthetic archive cycles",
                state = rememberWindowState(width = 1440.dp, height = 900.dp),
            ) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawn.set(true)
                    if (drawRequest >= 0) drawCount.incrementAndGet()
                }) { App(state = state, window = window) }
                LaunchedEffect(Unit) {
                    fun archiveTabs() = state.tabs.filter { it.sourcePath?.startsWith(archive.toString() + "!") == true }

                    fun sample(phase: String, cycle: Int, closedTabId: String? = null, videoReady: Boolean = false) {
                        if (diagnostic) {
                            System.gc()
                            Thread.sleep(200)
                        }
                        val rt = Runtime.getRuntime()
                        val gc = ManagementFactory.getGarbageCollectorMXBeans()
                        val count = gc.sumOf { it.collectionCount.coerceAtLeast(0) }
                        val heldRows = snapshots.sumOf { it.logData.size.toLong() }
                        val groupCount = archiveTabs().sumOf { it.analysis.stackTraceGroups.size }
                        val archiveTabIds = archiveTabs().map { it.id } + listOfNotNull(closedTabId)
                        val archiveCacheCount = computeCache?.keys?.count { key ->
                            key is String && archiveTabIds.any { key.startsWith("$it#") }
                        } ?: -1
                        println(
                            "RAM_POC_ARCHIVE phase=$phase cycle=$cycle launch_elapsed_ms=${System.currentTimeMillis() - launchMs} " +
                                "heap_used=${rt.totalMemory() - rt.freeMemory()} heap_committed=${rt.totalMemory()} " +
                                "gc_count=$count archive_tabs=${archiveTabs().size} held_snapshots=${snapshots.size} " +
                                "held_rows=$heldRows group_count=$groupCount cache_entries=${computeCache?.size ?: -1} " +
                                "archive_cache_entries=$archiveCacheCount background_tabs=${backgroundIds.size} " +
                                "video_attached=${archiveTabs().count { it.attachedVideo != null }} " +
                                "video_ready=$videoReady diagnostic=$diagnostic",
                        )
                        System.out.flush()
                    }

                    fun footprint(phase: String, cycle: Int) {
                        if (!measureFootprint || !System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
                        val output = home.resolve("footprint-$phase-$cycle.txt").toFile()
                        val result = runCatching {
                            val process = ProcessBuilder("/usr/bin/footprint", ProcessHandle.current().pid().toString())
                                .redirectErrorStream(true).redirectOutput(output).start()
                            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                process.waitFor()
                                "timeout"
                            } else {
                                "exit=${process.exitValue()}"
                            }
                        }.getOrElse { "error=${it.javaClass.simpleName}" }
                        println("RAM_POC_FOOTPRINT_BEGIN phase=$phase cycle=$cycle $result")
                        if (output.isFile) print(output.readText())
                        println("RAM_POC_FOOTPRINT_END phase=$phase cycle=$cycle")
                        System.out.flush()
                    }

                    fun histogram(cycle: Int) {
                        if (!measureHistogram) return
                        val output = home.resolve("histogram-closed-$cycle.txt").toFile()
                        val result = runCatching {
                            val jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd")
                            val process = ProcessBuilder(jcmd.toString(), ProcessHandle.current().pid().toString(), "GC.class_histogram")
                                .redirectErrorStream(true).redirectOutput(output).start()
                            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                process.waitFor()
                                "timeout"
                            } else {
                                "exit=${process.exitValue()}"
                            }
                        }.getOrElse { "error=${it.javaClass.simpleName}" }
                        println("RAM_POC_HISTOGRAM_BEGIN phase=closed cycle=$cycle forced_gc=true $result")
                        if (output.isFile) print(output.readText())
                        println("RAM_POC_HISTOGRAM_END phase=closed cycle=$cycle")
                        System.out.flush()
                    }
                    try {
                        while (!drawn.get()) delay(20)
                        repeat(5) { withFrameNanos { } }
                        if (video) {
                            for (name in listOf("background-a.log", "background-b.log")) {
                                val file = root.resolve(name)
                                require(!Files.isSymbolicLink(file) && Files.isRegularFile(file) && file.toRealPath().parent == root.toRealPath())
                                val id = checkNotNull(state.openPath(file.toFile()))
                                val deadline = System.nanoTime() + 600_000_000_000L
                                while (state.tabs.none { it.id == id && it.contentState == TabContentState.LOADED && !it.analysis.pending }) {
                                    check(System.nanoTime() < deadline) { "background load timed out" }
                                    delay(100)
                                }
                                val tab = state.tabs.single { it.id == id }
                                check(tab.logData.size == 100_000)
                                backgroundIds += id
                                backgroundPins += state.pinRows(tab)
                            }
                        }
                        delay(6000)
                        check(archiveTabs().isEmpty())
                        sample("control", 0)
                        footprint("control", 0)
                        delay(1500)
                        repeat(cycles) { index ->
                            check(archiveTabs().isEmpty())
                            state.openPath(archive.toFile())
                            if (video) {
                                val picker = checkNotNull(state.pendingZipPicker) { "expected archive picker" }
                                check(picker.candidates.size == 1 && picker.videoCandidates.size == 1)
                                state.openZipEntries(picker.zipFile, picker.candidates, picker.videoCandidates.single())
                            }
                            val deadline = System.nanoTime() + 600_000_000_000L
                            while (archiveTabs().none { it.contentState == TabContentState.LOADED && it.logData.isNotEmpty() && !it.analysis.pending }) {
                                check(System.nanoTime() < deadline) { "archive open timed out on cycle ${index + 1}" }
                                delay(100)
                            }
                            check(archiveTabs().size == 1) { "expected exactly one archive tab" }
                            val tabId = archiveTabs().single().id
                            check(state.activeTabId == tabId) { "opened archive is not active" }
                            check(archiveTabs().single().logData.size == expectedRows) { "unexpected archive row count" }
                            check(!grouped || archiveTabs().single().analysis.stackTraceGroups.isNotEmpty()) {
                                "grouped fixture did not produce a stack trace group"
                            }
                            val previousDrawCount = drawCount.get()
                            drawRequest++
                            while (drawCount.get() <= previousDrawCount || state.isLoadInFlight(tabId)) {
                                check(System.nanoTime() < deadline) { "archive render or load timed out" }
                                withFrameNanos { }
                            }
                            repeat(5) { withFrameNanos { } }
                            if (video) {
                                while (archiveTabs().single().attachedVideo == null) {
                                    check(System.nanoTime() < deadline) { "archive video attachment timed out" }
                                    delay(100)
                                }
                                val controller = checkNotNull(state.videoController(tabId))
                                val videoDeadline = System.nanoTime() + 60_000_000_000L
                                while (controller.currentFrame == null) {
                                    check(controller.error == null) { "archive video decoder failed: ${controller.error}" }
                                    check(System.nanoTime() < videoDeadline) { "archive video decoder did not produce a frame within 60 seconds" }
                                    delay(100)
                                }
                                delay(2000)
                            }
                            check(backgroundIds.all { id ->
                                state.tabs.any { it.id == id && it.contentState == TabContentState.LOADED && it.logData.size == 100_000 }
                            })
                            sample("open", index + 1, videoReady = video)
                            if (holdSnapshots) snapshots.add(archiveTabs().single())
                            state.closeTab(tabId)
                            while (state.tabs.any { it.id == tabId }) {
                                check(System.nanoTime() < deadline) { "archive close timed out on cycle ${index + 1}" }
                                delay(100)
                            }
                            val closeDrawCount = drawCount.get()
                            drawRequest++
                            while (drawCount.get() <= closeDrawCount) {
                                check(System.nanoTime() < deadline) { "archive close render timed out" }
                                withFrameNanos { }
                            }
                            repeat(5) { withFrameNanos { } }
                            // 1.5s release, 3s trim, plus at least 1s disposal grace.
                            delay(6000)
                            check(archiveTabs().isEmpty()) { "archive tab survived close" }
                            check(backgroundIds.all { id ->
                                state.tabs.any { it.id == id && it.contentState == TabContentState.LOADED && it.logData.size == 100_000 }
                            })
                            sample("closed", index + 1, tabId)
                            footprint("closed", index + 1)
                            if (index + 1 == cycles) histogram(index + 1)
                            delay(1500)
                        }
                        snapshots.clear()
                        backgroundPins.forEach { it.close() }
                        state.close()
                        exitApplication()
                    } catch (error: Throwable) {
                        error.printStackTrace()
                        backgroundPins.forEach { it.close() }
                        state.close()
                        exitProcess(2)
                    }
                }
            }
        }
    }
}
