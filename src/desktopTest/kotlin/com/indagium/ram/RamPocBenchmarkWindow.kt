// Diagnostic-only explicit GC and fixed measurement parameters are intentional.
@file:Suppress("ExplicitGarbageCollectionCall", "MagicNumber", "CyclomaticComplexMethod", "MaxLineLength")

package com.indagium.ram

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.indagium.model.FilterMode
import com.indagium.model.TabContentState
import com.indagium.ui.App
import com.indagium.ui.AppState
import com.indagium.ui.DesktopStorage
import kotlinx.coroutines.delay
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Separate harness window, not production Main startup. Each invocation uses one synthetic home. */
object RamPocBenchmarkWindow {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "home fixture clean|restore|prepare" }
        val home = Path.of(args[0]).toAbsolutePath().normalize()
        val tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        require(home.startsWith(tmp) && home != tmp && home.fileName.toString().startsWith("indagium-ram-poc-"))
        require(Files.isDirectory(home) && !Files.isSymbolicLink(home) && home.toRealPath() == home)
        val fixture = Path.of(args[1]).toAbsolutePath().normalize()
        val root = Path.of("build/ram-poc/fixtures").toAbsolutePath().normalize()
        require(fixture.parent == root && fixture.fileName.toString() in setOf("logcat-100k.log", "logcat-1m.log", "logcat-10m.log"))
        var p = root.root
        for (part in fixture) { p = p.resolve(part); require(!Files.isSymbolicLink(p)) }
        require(Files.isRegularFile(fixture) && fixture.toRealPath().parent == root.toRealPath())
        val mode = args[2]
        require(mode in setOf("clean", "restore", "prepare"))
        require(mode != "clean" || !Files.exists(home.resolve("autosave.cache")))
        require(mode != "restore" || Files.isRegularFile(home.resolve("autosave.cache")))
        // Route every DesktopStorage fallback to a fresh synthetic directory, including code
        // that does not consume the explicit AppState paths below.
        DesktopStorage.installDebugAppDataDirOverride(home.resolve("appdata-$mode").toString(), true)
        val start = System.nanoTime()

        fun sample(label: String) {
            if (System.getProperty("indagium.ram.forceGc") == "true") {
                System.gc()
                Thread.sleep(200)
            }
            val rt = Runtime.getRuntime()
            val gc = ManagementFactory.getGarbageCollectorMXBeans()
            val thread = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            val allocated = thread?.takeIf { it.isThreadAllocatedMemorySupported }?.let {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it.getThreadAllocatedBytes(Thread.currentThread().id)
            } ?: -1L
            val launchEpoch = System.getProperty("indagium.ram.launchEpochMs")?.toLongOrNull() ?: System.currentTimeMillis()
            val gcCount = gc.sumOf { it.collectionCount.coerceAtLeast(0) }
            val gcMs = gc.sumOf { it.collectionTime.coerceAtLeast(0) }
            println("RAM_POC_UI phase=$label elapsed_ms=${(System.nanoTime() - start) / 1_000_000} " +
                "launch_elapsed_ms=${System.currentTimeMillis() - launchEpoch} " +
                "heap_used=${rt.totalMemory() - rt.freeMemory()} heap_committed=${rt.totalMemory()} " +
                "thread_allocated=$allocated gc_count=$gcCount gc_ms=$gcMs")
            System.out.flush()
        }
        application {
            val state = remember {
                AppState(autosaveFile = home.resolve("autosave.cache").toFile(), restoreOnCreate = mode == "restore",
                    notesDir = home.resolve("notes").toFile(), archiveCacheDir = home.resolve("archives").toFile(),
                    customCommandsDir = home.resolve("commands").toFile(), filterBackupsDir = home.resolve("filters").toFile(),
                    platformDefaultSaveRootDir = home.resolve("saved").toFile()).also {
                    if (mode != "restore") it.acceptLicenseAgreement()
                }
            }
            val drawn = remember { AtomicBoolean() }
            val pendingDraw = remember { AtomicReference<Pair<String, Long>?>(null) }
            Window(
                onCloseRequest = { state.close(); exitApplication() },
                title = "RAM POC harness",
                state = rememberWindowState(width = 1440.dp, height = 900.dp),
            ) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    if (drawn.compareAndSet(false, true)) sample("first_draw")
                    pendingDraw.getAndSet(null)?.let { (name, since) ->
                        println("RAM_POC_UI_DRAW phase=$name next_draw_ms=${(System.nanoTime() - since) / 1_000_000}")
                    }
                }) { App(state = state, window = window) }
                LaunchedEffect(Unit) {
                    fun loaded(path: Path) = state.tabs.any {
                        it.filename == path.fileName.toString() && it.contentState == TabContentState.LOADED &&
                            it.logData.isNotEmpty() && !it.analysis.pending
                    }

                    while (!drawn.get()) delay(20)
                    if (mode != "prepare") {
                        val until10 = start + 10_000_000_000L
                        delay(((until10 - System.nanoTime()) / 1_000_000).coerceAtLeast(0)); sample("10s")
                        val until60 = start + 60_000_000_000L
                        delay(((until60 - System.nanoTime()) / 1_000_000).coerceAtLeast(0)); sample("60s")
                    }
                    if (mode != "restore") {
                        val openStart = System.nanoTime()
                        state.openPaths(listOf(fixture.toFile()), Long.MAX_VALUE)
                        val deadline = System.nanoTime() + 600_000_000_000L
                        while (!loaded(fixture)) {
                            check(System.nanoTime() < deadline) { "open timed out" }; delay(100)
                        }
                        println("RAM_POC_UI_TIMING phase=open_rows_loaded duration_ms=${(System.nanoTime() - openStart) / 1_000_000}")
                        sample("open_rows_loaded")
                    }
                    if (mode == "prepare") { state.autosaveNow(); state.close(); exitApplication(); return@LaunchedEffect }
                    val restoreDeadline = System.nanoTime() + 600_000_000_000L
                    while (!loaded(fixture)) {
                        check(System.nanoTime() < restoreDeadline) { "restore timed out" }; delay(100)
                    }
                    check(!state.needsLicenseAcceptance) { "synthetic license acceptance did not persist" }
                    val tab = state.tabs.first { it.filename == fixture.fileName.toString() }
                    state.setFilterMode(tab.id, FilterMode.KEYWORD); state.setKw(tab.id, "denied")
                    delay(1000); sample("filter_after_1s")
                    // Programmatic scroll targets the actual log list and never sends OS input.
                    val scroll = state.logViewerScrollStateStore.lazyState("${tab.id}:main")
                    val scrollStart = System.nanoTime()
                    scroll.requestScrollToItem(1000)
                    val frames = ArrayList<Long>()
                    var previous = withFrameNanos { it }
                    repeat(60) {
                        val next = withFrameNanos { it }; frames.add(next - previous); previous = next
                    }
                    check(scroll.firstVisibleItemIndex >= 1000) { "scroll did not reach requested row" }
                    frames.sort()
                    val medianFrameUs = frames[frames.size / 2] / 1000
                    val p95FrameUs = frames[(frames.size * 95 / 100).coerceAtMost(frames.lastIndex)] / 1000
                    println("RAM_POC_UI_TIMING phase=scroll duration_ms=${(System.nanoTime() - scrollStart) / 1_000_000} " +
                        "frame_median_us=$medianFrameUs frame_p95_us=$p95FrameUs " +
                        "first_visible=${scroll.firstVisibleItemIndex} " +
                        "window_focus=${window.isFocused} window_active=${window.isActive} window_showing=${window.isShowing}")
                    sample("scroll_after_60_frames")
                    val switchFile = root.resolve("logcat-switch.log")
                    require(!Files.isSymbolicLink(switchFile) && Files.isRegularFile(switchFile) && switchFile.toRealPath().parent == root.toRealPath())
                    state.openPaths(listOf(switchFile.toFile()), Long.MAX_VALUE)
                    val deadline = System.nanoTime() + 600_000_000_000L
                    while (!loaded(switchFile)) {
                        check(System.nanoTime() < deadline) { "switch fixture timed out" }; delay(100)
                    }
                    pendingDraw.set("switch" to System.nanoTime())
                    state.activateTab(tab.id); delay(1000); sample("switch_after_1s")
                    state.closeTab(tab.id); delay(1000); sample("close_after_1s")
                    state.close(); exitApplication()
                }
            }
        }
    }
}
