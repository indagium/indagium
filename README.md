# openLog

A desktop log viewer for Android logcat files, built with Kotlin and Compose Multiplatform.

![Version](https://img.shields.io/badge/version-1.0.7-blue)
![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey)

## Features

- **Multi-tab** — open multiple log files side by side
- **Log level filtering** — toggle V / D / I / W / E / F / S individually
- **Tag filters** — include or exclude tags with exact match or regex; package-level grouping
- **Message rules** — filter lines by message content (substring or regex)
- **Sequences** — auto-detect and collapse recurring tag patterns into collapsible groups
- **Highlighters** — color-code lines by message pattern
- **Annotations** — annotate log selections with notes, exported as Markdown
- **Compare view** — diff two open tabs line by line
- **Themes** — 20 built-in themes (light, dark, and paper variants)
- **Autosave** — session is fully restored on next launch
- **Filter presets** — save and load filter configurations

### Supported logcat formats

`threadtime`, `time`, `brief`, `bare` — unrecognised lines are shown with tag `RAW`.

## Installation

Download the latest release for your platform from the [Releases](../../releases) page:

| Platform | File |
|---|---|
| Linux | `openLog_x.y.z_amd64.deb` |
| Windows | `openLog-x.y.z.msi` |
| macOS | `openLog-x.y.z.dmg` |

### macOS: "could not verify... free of malware"

The macOS build isn't signed with an Apple Developer ID or notarized, so Gatekeeper blocks it
once the `.dmg` has been downloaded through a browser (locally built copies aren't affected —
they never get the quarantine flag a browser download adds). To open it anyway, either:

- Terminal: `xattr -cr /Applications/openLog.app`, or
- System Settings → Privacy & Security → scroll to the "openLog was blocked" notice → **Open Anyway**

## Building from source

**Requirements:** JDK 17+

```bash
# Run
./gradlew desktopRun

# Test
./gradlew desktopTest

# Package (run on the target OS)
./gradlew packageDmg   # macOS
./gradlew packageDeb   # Linux
./gradlew packageMsi   # Windows
```

## Releasing

Push a version tag to trigger the GitHub Actions build, which produces Linux, Windows, and macOS packages and creates a GitHub Release automatically:

```bash
git tag v1.0.8 && git push --tags
```

The macOS build is unsigned (no Apple Developer certificate in CI) — see the Installation section above for the Gatekeeper workaround.

## License

Copyright © 2026 Roman Arnaut
