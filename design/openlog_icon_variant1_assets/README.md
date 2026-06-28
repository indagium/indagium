# openLog icon assets — Variant 1

Generated assets for Kotlin Desktop / Compose Multiplatform.

Recommended files:
- Runtime / Compose Desktop: `compose-resources/icons/openlog.png`
- macOS package: `native/openlog.icns`
- Windows package: `native/openlog.ico`
- Linux package: `native/openlog-linux-1024.png` or `openlog.png`

Example Compose runtime usage:

```kotlin
Window(
    onCloseRequest = ::exitApplication,
    title = "openLog",
    icon = painterResource("icons/openlog.png")
) {
    App()
}
```

Example Gradle packaging:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            macOS {
                iconFile.set(project.file("src/main/resources/icons/openlog.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/openlog.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/openlog.png"))
            }
        }
    }
}
```
