@file:Suppress("DEPRECATION") // painterResource(String) — Compose resources Res class not generated for single-JVM-target projects

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
        Window(
            onCloseRequest = ::exitApplication,
            title = "openLog",
            icon = painterResource("icons/openlog.png"),
            state = windowState,
        ) {
            App(appState)
        }
    }
}
