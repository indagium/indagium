# openLog Release Readiness

This app is packaged with Compose Desktop native distributions:

- macOS: `./gradlew packageDmg`
- Windows: `./gradlew packageMsi`
- Linux: `./gradlew packageDeb`
- Current host smoke package: `./gradlew packageDistributionForCurrentOS`

Before a public release:

1. Run `./gradlew build` from a clean checkout.
2. Use a release JDK distribution such as Amazon Corretto or Eclipse Temurin for production packages.
3. Build the package on each target OS.
4. Install the package on a clean user account.
5. Launch the installed app and verify the runtime icon, file-open dialog, drag/drop, autosave restore, note save/open, and filter import/export.
6. Upgrade over the previous released version and verify existing app data is still readable.
7. macOS public distribution: sign and notarize the `.dmg` with the release certificate.
8. Windows public distribution: sign the `.msi` with the release certificate.
9. Linux public distribution: verify package metadata and uninstall behavior for the supported distributions.

Local app data is stored in OS-specific app data/state directories via `DesktopStorage`. Automatic note export can be disabled in Settings for private logs.
