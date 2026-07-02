@file:Suppress("DEPRECATION") // painterResource(String) — Compose resources Res class not generated for single-JVM-target projects

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openlog.ui.App
import com.openlog.ui.AppState
import java.awt.Taskbar
import java.awt.Toolkit

fun main() {
    // On Linux, AWT/X11 derives the window's WM_CLASS from the main class name (here "MainKt",
    // since Main.kt has no package). Taskbars/docks match running windows to their .desktop
    // launcher entry by WM_CLASS, not by the Window() title — a mismatch shows the raw class
    // name and a generic icon instead of "openLog" and its icon. Must be set before AWT/Toolkit
    // initializes, so this runs first.
    System.setProperty("sun.java.command", "openLog")

    // Set the macOS Dock icon when running unpackaged (IDE / gradlew desktopRun).
    // The packaged .app uses the .icns via nativeDistributions; this covers dev runs.
    runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val url = Thread.currentThread().contextClassLoader
                    .getResource("icons/openlog.png")
                if (url != null) taskbar.iconImage = Toolkit.getDefaultToolkit().getImage(url)
            }
        }
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appState = remember { AppState(restoreOnCreate = true) }
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
            onCloseRequest = { appState.autosaveNow(); exitApplication() },
            title = "openLog",
            icon = painterResource("icons/openlog.png"),
            state = windowState,
        ) {
            App(appState)
        }
    }
}
