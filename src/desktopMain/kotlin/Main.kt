@file:Suppress("DEPRECATION") // painterResource(String) — Compose resources Res class not generated for single-JVM-target projects

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openlog.singleinstance.SingleInstance
import com.openlog.singleinstance.SingleInstanceHandle
import com.openlog.ui.App
import com.openlog.ui.AppState
import com.openlog.ui.DesktopStorage
import com.openlog.ui.horizontalScrollDelta
import com.openlog.ui.isLinuxOs
import com.openlog.ui.isMacOs
import java.awt.AWTEvent
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.io.File

fun main(args: Array<String>) {
    // On Linux, AWT/X11 derives the window's WM_CLASS from the *bottom stack frame's class name*
    // ("MainKt" here) at toolkit construction — sun.awt.X11.XToolkit reads the main class off a
    // Throwable stack trace, so no system property can change it (v1.0.5 shipped a
    // sun.java.command-based attempt; Ubuntu still showed "MainKt" + a gear icon). The only
    // reliable override is writing XToolkit.awtAppClassName by reflection after the toolkit
    // exists and before the first window is created. Requires
    // --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED (added for Linux builds in
    // build.gradle.kts); if that's missing the set fails and behavior just stays as before.
    // The value must match the StartupWMClass that CI writes into the .deb's .desktop entry
    // (.github/workflows/build.yml), which is how GNOME maps the window back to the launcher.
    if (System.getProperty("os.name").orEmpty().lowercase().contains("linux")) {
        runCatching {
            Toolkit.getDefaultToolkit()
            val toolkitClass = Class.forName("sun.awt.X11.XToolkit")
            val field = toolkitClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(null, "openLog")
        }
    }

    // Set the macOS Dock icon when running unpackaged (IDE / gradlew desktopRun) — and ONLY
    // then: jpackage-launched apps define jpackage.app-path, and for those the bundle's .icns
    // must stay the single source of truth. Overriding the Dock icon at runtime in packaged
    // builds meant a stale bundled PNG could silently replace the (correct) .icns artwork.
    val isPackaged = System.getProperty("jpackage.app-path") != null
    if (!isPackaged) runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val url = Thread.currentThread().contextClassLoader
                    .getResource("icons/openlog.png")
                if (url != null) taskbar.iconImage = Toolkit.getDefaultToolkit().getImage(url)
            }
        }
    }

    // OPENLOG_DEBUG_INPUT=1 diagnostic: prints every AWT mouse event's id/button/modifiersEx.
    // Exists to answer one open question empirically — which Java button number(s) X11 delivers
    // horizontal-scroll (core buttons 6/7) as, since XToolkit skips wheel buttons 4/5 when
    // numbering extra buttons and there's no X11 machine to measure this from directly. Run with
    // OPENLOG_DEBUG_INPUT=1 ./gradlew desktopRun on a real Linux desktop, two-finger swipe
    // horizontally, and read off `button` — that's the constant to set in
    // ui/LinuxHorizontalScroll.kt's HSCROLL_BUTTONS. Left OS-unconditional (not Linux-gated) since
    // it's opt-in via env var and harmless noise anywhere else.
    if (System.getenv("OPENLOG_DEBUG_INPUT") == "1") {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event ->
                if (event is MouseEvent) {
                    println("[OPENLOG_DEBUG_INPUT] id=${event.id} button=${event.button} modifiersEx=${event.modifiersEx}")
                }
            },
            AWTEvent.MOUSE_EVENT_MASK,
        )
    }

    // Non-null on Linux/Windows: either this process is primary (boundPort != null, real socket
    // bound) or a failed forward degraded it to boundPort == null (still runs normally, just
    // without single-instance behavior this launch). A null return means the file args were handed
    // to an already-running instance, so this process exits here — before application{} builds a
    // composition, hence no window flash.
    //
    // This MUST stay outside application{}: its content lambda is @Composable, so an acquire()
    // there would re-bind the socket and re-take the lock on every recomposition. Worse, the
    // second tryLock() in a JVM that already holds the lock throws OverlappingFileLockException,
    // which acquire() (correctly, for the cross-process case) reads as "someone else is primary" —
    // it would then forward the args to this process's own accept loop, get OK, and exit the app.
    //
    // macOS stays null: LaunchServices already makes the .app single-instance and delivers reopen
    // files through Desktop.setOpenFileHandler below, so there is nothing for this to do there.
    val singleInstance: SingleInstanceHandle? =
        if (isMacOs) null else (SingleInstance.acquire(DesktopStorage.appDataDir(), args) ?: return)

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appState = remember { AppState(restoreOnCreate = true, filterBackupsDir = DesktopStorage.filterBackupsDir()) }
        DisposableEffect(appState) {
            val desktop = runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null }.getOrNull()
            if (desktop?.isSupported(Desktop.Action.APP_OPEN_FILE) == true) {
                runCatching {
                    desktop.setOpenFileHandler { event ->
                        appState.openPaths(event.files.filterIsInstance<File>())
                    }
                }
            }
            onDispose {
                if (desktop?.isSupported(Desktop.Action.APP_OPEN_FILE) == true) {
                    runCatching { desktop.setOpenFileHandler(null) }
                }
            }
        }
        DisposableEffect(Unit) {
            val startupFiles = args.map(::File).filter { it.exists() }
            if (startupFiles.isNotEmpty()) appState.openPaths(startupFiles)
            onDispose {}
        }
        // Localhost-only automation control server for MCP/dev use (see debug/ControlServer.kt).
        // AppState owns the actual server instance (see setMcpControlEnabled /
        // startControlServerForThisSessionOnly) — this effect only decides the starting state:
        // OPENLOG_DEBUG_CONTROL / -Dopenlog.debugControl, if set, wins and force-enables for this
        // run only, without persisting; otherwise the restored/default Settings toggle applies.
        // Packaged builds (packageDmg/packageDeb/packageMsi) set neither and default the setting
        // off, so end users never have this listener running unless they explicitly enable it.
        DisposableEffect(Unit) {
            val envPort = System.getenv("OPENLOG_DEBUG_CONTROL")?.toIntOrNull()
                ?: System.getProperty("openlog.debugControl")?.toIntOrNull()
            when {
                envPort != null -> appState.startControlServerForThisSessionOnly(envPort)
                appState.settings.mcpControlEnabled -> appState.setMcpControlEnabled(true, appState.settings.mcpControlPort)
            }
            onDispose { appState.stopControlServerForShutdown() }
        }
        Window(
            // Autosave is debounced 400ms behind tab/filter/settings changes (see App.kt) — a
            // change made shortly before closing the window would otherwise never get written,
            // since exitApplication() tears down the whole composition (and that pending
            // LaunchedEffect delay along with it) immediately. Flushing synchronously here first
            // guarantees the latest state — including whatever filter was just set — survives.
            onCloseRequest = {
                appState.autosaveNow()
                appState.close()
                exitApplication()
            },
            title = "openLog",
            icon = painterResource("icons/openlog.png"),
            state = windowState,
        ) {
            // No-ops on a degraded handle (boundPort == null / macOS's null) — safe to call
            // unconditionally. AWT window calls (raiseWindow) are NOT snapshot-safe off-thread the
            // way appState.openPaths is, hence the EventQueue.invokeLater hop for onRaise only.
            if (singleInstance != null) {
                DisposableEffect(Unit) {
                    singleInstance.startAccepting(
                        onOpenFiles = { appState.openPaths(it) },
                        onRaise = { EventQueue.invokeLater { raiseWindow(window) } },
                    )
                    onDispose { singleInstance.close() }
                }
            }
            if (isLinuxOs) {
                DisposableEffect(Unit) {
                    val listener = installLinuxHorizontalScrollBridge(appState)
                    onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
                }
            }
            App(appState)
        }
    }
}

// GNOME/Mutter routinely ignores a bare toFront() under focus-stealing prevention; the brief
// isAlwaysOnTop toggle forces the compositor to actually raise the window. Harmless no-op-ish on
// Windows/KDE, which honor toFront() directly.
private fun raiseWindow(window: ComposeWindow) {
    if (window.extendedState and Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }
    window.toFront()
    window.requestFocus()
    window.isAlwaysOnTop = true
    window.isAlwaysOnTop = false
}

// Scroll step per detected button-6/7 press — a reasonable guess at "one wheel-notch equivalent,"
// not measured against a real X11 session. Tune once OPENLOG_DEBUG_INPUT confirms the actual
// button numbers and event cadence (a single press vs. a press-per-tick repeat while swiping).
private const val HSCROLL_STEP_PX = 60f

// Linux-only bridge for X11 button-6/7 (touchpad horizontal scroll), which XToolkit surfaces as
// ordinary extra-button MOUSE_PRESSED events rather than a wheel event Compose already handles.
// Gated on !autoLogRowWrap the same way the manual-mode horizontal scrollbar itself is (auto-wrap
// has no horizontal scroll position to move), and on hoveredLogPanelKey being non-null so a
// touchpad swipe over, say, the filter panel doesn't scroll whichever log panel was last hovered.
// horizontalScrollDelta (ui/LinuxHorizontalScroll.kt) returning null for every other button makes
// this a safe no-op if HSCROLL_BUTTONS never matches what this machine's X11 actually sends.
private fun installLinuxHorizontalScrollBridge(appState: AppState): AWTEventListener {
    val listener = AWTEventListener { event ->
        if (event !is MouseEvent || event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        if (appState.settings.autoLogRowWrap) return@AWTEventListener
        val panelKey = appState.hoveredLogPanelKey ?: return@AWTEventListener
        val delta = horizontalScrollDelta(event.button, HSCROLL_STEP_PX) ?: return@AWTEventListener
        // Fires on the EDT (Compose Desktop's main thread), so calling the non-suspend
        // dispatchRawDelta directly is safe — same reasoning as LogViewer's own wheel handling.
        appState.logViewerScrollStateStore.scrollState(panelKey).dispatchRawDelta(delta)
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    return listener
}
