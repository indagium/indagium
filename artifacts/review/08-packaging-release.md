# 08 Packaging and Release Readiness

Reviewed:
- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `icons/`
- `src/desktopMain/resources/icons/`
- Gradle task discovery

Packaging is configured through Compose Desktop native distributions:
- `TargetFormat.Dmg`
- `TargetFormat.Msi`
- `TargetFormat.Deb`
- `packageName = "openLog"`
- `packageVersion = "1.0.0"`
- macOS bundle id: `com.romanarnaut.openlog`
- native icons exist for macOS, Windows, and Linux.

## Findings

### OL2-012

Severity: low

Area: Packaging and release readiness

File path: `build.gradle.kts`

Symbol/function/class if known: `compose.desktop.application.nativeDistributions`

Problem: Native packaging metadata is present, but release-hardening metadata and flows are incomplete: no signing/notarization configuration is present, no vendor/copyright/license metadata is set, and installer upgrade behavior is not documented or tested.

Why it matters: This may be acceptable for internal builds, but public macOS distribution needs signing/notarization, Windows users may see trust prompts, and installer metadata affects upgrade/uninstall behavior.

Suggested fix: Add explicit vendor/copyright/license metadata, document release commands per OS, configure signing/notarization when shipping outside local development, and test upgrade/uninstall behavior on each target OS.

Suggested test: Add a release checklist or CI job that builds the current-OS package and verifies app launch/resources. For public release, add manual OS-specific install/upgrade checks.

Confidence: medium

## No Serious Issue Found

- The package version starts with a nonzero major (`1.0.0`), which avoids known macOS `jpackage` rejection of versions like `0.1.0`.
- Runtime window icon and native distribution icons exist.
- Compose Desktop packaging tasks are discoverable through Gradle.
