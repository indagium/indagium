# openLog icon pack — white rounded, scaled inner content

This pack fixes the previous issues:

- white app-icon tile
- real transparent rounded corners outside the tile
- inner document + magnifier scaled up to look natural near apps like Claude
- ready assets for Compose Desktop runtime and native packaging

Use the top-level files first. They are copied from `recommended_112`.

Main files:

```text
openlog.png
compose-resources/icons/openlog.png
native/openlog.icns
native/openlog.ico
native/openlog-linux-1024.png
```

Alternatives are included in case the dock size still feels slightly off:

```text
recommended_112/  # recommended
larger_118/       # inner image bigger
safer_106/        # inner image slightly smaller
```

Compose runtime example:

```kotlin
Window(
    onCloseRequest = ::exitApplication,
    title = "openLog",
    icon = painterResource("icons/openlog.png")
) {
    App()
}
```

Native distribution example:

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
