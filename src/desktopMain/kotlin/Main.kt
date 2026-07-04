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
import com.openlog.ui.DesktopStorage
import java.awt.Taskbar
import java.awt.Toolkit

fun main() {
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

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appState = remember { AppState(restoreOnCreate = true, filterBackupsDir = DesktopStorage.filterBackupsDir()) }
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
