# openLog transparent icon assets

This pack uses a transparent canvas: there is no opaque square/tile background, so the ugly visible white corners are removed.

Recommended files:

- Compose runtime/window icon: `compose-resources/icons/openlog.png`
- macOS native distribution: `native/openlog.icns`
- Windows native distribution: `native/openlog.ico`
- Linux native distribution: `native/openlog-linux-1024.png`

Compose Desktop example:

```kotlin
Window(
    onCloseRequest = ::exitApplication,
    title = "openLog",
    icon = painterResource("icons/openlog.png")
) {
    App()
}
```

Gradle native distributions example:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            macOS { iconFile.set(project.file("src/main/resources/icons/openlog.icns")) }
            windows { iconFile.set(project.file("src/main/resources/icons/openlog.ico")) }
            linux { iconFile.set(project.file("src/main/resources/icons/openlog-linux-1024.png")) }
        }
    }
}
```
