import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openlog.ui.App
import com.openlog.ui.AppState

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
    val appState = remember { AppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "openLog",
        state = windowState,
    ) {
        App(appState)
    }
}
