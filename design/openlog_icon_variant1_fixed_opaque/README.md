# openLog icon assets — Variant 1 fixed opaque

This is the corrected Variant 1 asset pack. The previous version used a dark/black concept background. This version uses a light opaque app-icon tile.

## Main files

- macOS packaged app: `native/openlog.icns`
- Windows packaged app: `native/openlog.ico`
- Linux packaged app: `native/openlog-linux-1024.png`
- Compose runtime/window icon: `compose-resources/icons/openlog.png`

## Compose Desktop runtime icon example

```kotlin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "openLog",
        icon = painterResource("icons/openlog.png")
    ) {
        App()
    }
}
```

## Compose Desktop native distribution example

```kotlin
compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            packageName = "openLog"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/main/resources/native/openlog.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/native/openlog.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/native/openlog-linux-1024.png"))
            }
        }
    }
}
```

## Notes for Codex

Place files under `src/main/resources/` preserving these relative paths, or adjust the Gradle paths accordingly.

ICNS status: created
