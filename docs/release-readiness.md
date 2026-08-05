# Indagium Release Readiness

This app is packaged with Compose Desktop native distributions:

- macOS: `./gradlew packageDmg`
- Windows: `./gradlew packageMsi`
- Linux: `./gradlew packageDeb`
- Linux AppImage: `./gradlew packageAppImage`
- Linux Flatpak bundle: `./gradlew packageFlatpak`
- Current host smoke package: `./gradlew packageDistributionForCurrentOS`

Before a public release:

1. Run `./gradlew build` from a clean checkout.
2. Use a release JDK distribution such as Amazon Corretto or Eclipse Temurin for production packages.
3. Build every package on its target OS and architecture. Linux releases must include `.deb`,
   `x86_64`/`aarch64` AppImage, and `x86_64`/`aarch64` Flatpak artifacts with non-colliding names.
4. On a clean Linux user account, install/query each Flatpak bundle with
   `flatpak install --user ./Indagium-<version>-<arch>.flatpak`, then run `flatpak info com.indagium.desktop`.
   Extract and launch each AppImage without FUSE (`APPIMAGE_EXTRACT_AND_RUN=1`); install the `.deb` normally.
5. Launch each installed app and verify the runtime icon, file-open dialog, drag/drop, autosave restore, note save/open, and filter import/export.
6. Upgrade over the previous released version and verify existing app data is still readable.
7. macOS public distribution: sign and notarize the `.dmg` with the release certificate.
8. Windows public distribution: sign the `.msi` with the release certificate.
9. Linux public distribution: validate desktop/MIME metadata, verify the Flatpak home-directory
   permission, and confirm API/network providers work while Codex/Claude host-CLI profiles report
   their sandbox limitation.

Local app data is stored in OS-specific app data/state directories via `DesktopStorage`. Automatic note export can be disabled in Settings for private logs.
