import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openlog.ui.App
import com.openlog.ui.AppState
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
    val appState = remember { AppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "openLog",
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.dropTarget = DropTarget(window, object : DropTargetAdapter() {
                override fun dragOver(ev: DropTargetDragEvent) {
                    if (ev.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
                        ev.acceptDrag(DnDConstants.ACTION_COPY)
                    else
                        ev.rejectDrag()
                }
                override fun drop(ev: DropTargetDropEvent) {
                    ev.acceptDrop(DnDConstants.ACTION_COPY)
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val files = ev.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        files.filter { it.extension.lowercase() in listOf("log", "txt") }
                            .forEach { appState.openFile(it) }
                        ev.dropComplete(true)
                    }.onFailure { ev.dropComplete(false) }
                }
            })
        }
        App(appState)
    }
}
