# openLog app icon — Variant 1, native-size tile

This pack fixes the previous problem where the transparent document/magnifier looked too small in the dock.

The new icon uses a native app-icon style:
- full rounded-square tile occupying most of the canvas
- transparent outside the rounded corners
- document + magnifier centered inside the tile
- intended to appear similar in visual size to other desktop app icons such as Claude

Recommended files:

```kotlin
// Compose Desktop runtime window icon
Window(
    onCloseRequest = ::exitApplication,
    title = "openLog",
    icon = painterResource("icons/openlog.png")
) {
    App()
}
```

Native distributions:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            macOS { iconFile.set(project.file("src/main/resources/native/openlog.icns")) }
            windows { iconFile.set(project.file("src/main/resources/native/openlog.ico")) }
            linux { iconFile.set(project.file("src/main/resources/native/openlog-linux-1024.png")) }
        }
    }
}
```

Files:
- `openlog.png` — master 1024x1024 PNG
- `compose-resources/icons/openlog.png` — runtime icon
- `native/openlog.icns` — macOS packaged app icon
- `native/openlog.ico` — Windows packaged app icon
- `native/openlog-linux-1024.png` — Linux packaged app icon
- `png/openlog_*.png` — PNG sizes
