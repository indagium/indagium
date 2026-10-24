# Media-library licensing and source maintenance

This is an internal maintenance record for the native media libraries and their redistribution. It complements the shipped `THIRD_PARTY_NOTICES.md`; it does not replace any upstream licence or provide a patent opinion.

## Current package and records

The canonical component, version, SPDX/custom identifier, attribution, platform scope, full-text hashes, and source references are in `licenses/third-party/media-components.json`. `media-source-lock.json` fixes the source archives, recipe inputs, configurations, and inspected native JAR checksums. `SOURCE-BUNDLE.md` is the user-facing source preparation and rebuild guide. Update all three records together when the resolved native closure changes.

The inspected FFmpeg classifiers are FFmpeg 8.0.1-1.5.13 shared libraries built with `--enable-version3`, without `--enable-gpl` or `--enable-nonfree`. FFmpeg is under LGPL 3.0-or-later. JavaCV and JavaCPP 1.5.13 are separate Apache-2.0 bindings. OpenH264 2.6.0 is built from source; the separately distributed Cisco OpenH264 binary is not included. The inventory separates `zstd-jni` 1.5.7-4 (BSD-2-Clause) from its Zstandard 1.5.7 native code (BSD-3-Clause). It also records platform-specific runtimes, compiler notices, and the inspected macOS classifier's x86_64 `libatomic.1.dylib` without asserting that the file can load on Apple Silicon.

The project licence remains in the unchanged root `LICENSE`; third-party rights are carved out explicitly in `NOTICE`. The in-app **Third-party licences…** viewer is read-only and does not alter the existing agreement-acceptance gate. New packages carry the generated legal resources. Previously published installers are immutable and are not replaced by these source and documentation changes; the application version and agreement version are unchanged.

## Application (non-media) dependencies

Every other runtime JAR, the embedded scrcpy server and the AppImage runtime are recorded in `licenses/application/application-components.json`, with the licence texts their JARs do not carry themselves under `licenses/application/texts/`. `./gradlew validateApplicationLicenseInventory` (a dependency of `generateLicenseResources`) fails when a resolved `desktopRuntimeClasspath` module matches no `modules` pattern. When it fails, check the new module's POM and JAR for its licence, add the text if the JAR lacks it and the licence requires attribution, and update the inventory, `THIRD_PARTY_NOTICES.md` and the public `/licenses/` page together.

## Updating the inventory

For every media dependency update:

1. Resolve the actual platform-specific JARs used by each supported package and record their SHA-256 values. Inspect the embedded native members and each FFmpeg configure command; do not infer linked components from declared Maven dependencies alone.
2. Pin each source archive, source recipe, patch, compiler/runtime input, and licence text to an immutable upstream reference and SHA-256. Record where every full notice came from inside that pinned source. Keep build-only inputs distinct from linked or bundled runtime components.
3. Update component source references, SPDX/custom identifiers, attribution, applicable platforms, platform closure, and all legal-text hashes. Preserve original legal text verbatim. Update `THIRD_PARTY_NOTICES.md`, the public `/licenses/` page, and the user-facing `SOURCE-BUNDLE.md` together.
4. Run `./gradlew validateMediaLicenseInventory testMediaSourceGenerator prepareMediaSources`. The source task checks the complete required source closure, downloads only locked HTTPS inputs, verifies archive and binary hashes, and writes the archive, SHA-256 file, and manifest under `build/`.
5. Build the final installer for each supported target. Gradle validates resolved classifier JARs and staged app resources; GitHub and GitLab packaging jobs also inspect legal resources inside the final package. Retain the source archive, checksum, and manifest with the corresponding release or CI run.

Do not describe an input as matching a shipped library unless the runtime bytes and their source/configuration evidence have been inspected. The current source bundle is checksum-pinned but is not a bit-for-bit reproducible-build claim.

## Replacing a classifier in an extracted app image

First make a copy of the installed image and close Indagium. Extract packages with the platform's normal tools: mount the macOS DMG with `hdiutil`; use `msiexec /a <file.msi> /qn TARGETDIR=<folder>` for Windows MSI; use `dpkg-deb -x <file.deb> <folder>` for Debian; extract an AppImage with `<file.AppImage> --appimage-extract`; for Flatpak, install the bundle and use `flatpak info --user --show-location com.indagium.Indagium` to locate its deployment. The application JAR directory is:

- macOS: `<Indagium.app>/Contents/app/`
- Windows: `<install directory>/app/`
- Linux: `<install directory>/lib/app/`

Back up and replace only the matching platform classifier JAR, keeping its runtime filename, JavaCV/JavaCPP bindings, and app resources. A changed native payload must keep the interfaces the bindings load and must carry its own required licences and notices. If a Zstandard JAR is replaced, also remove stale extracted `libzstd-jni*` files from the app data directory's `native-cache/zstd/` while the application is stopped. If FFmpeg is replaced, clear JavaCPP's extracted-native cache (`org.bytedeco.javacpp.cachedir`, or its default `~/.javacpp/cache/`) while Indagium is stopped.

Reopen the copied image and smoke-test both video playback and Compact archive read/write. Rebuild the installer from the modified image when redistributing. Re-sign the modified app or installer when signatures are used; changing files inside a signed macOS bundle, MSI, DMG, or package invalidates the prior signature. Preserve the project and third-party notices with the replacement and distribute the complete corresponding source and build instructions as required by each licence.

## Patent and licence questions still open

The source inventory records licence texts and patent notices; it does not establish freedom to operate. Seek a qualified patent review before making broader claims or changing distribution scope, especially for:

- H.264/AVC encoding and decoding, including the effect of the OpenH264 source build and the absence of Cisco's separately distributed binary, across target countries and distribution models.
- The AOM Patent License 1.0 as applied to libaom and SVT-AV1, and any AV1 implementation or product-level claims outside the included licence text.
- Google patent grants that accompany libvpx and libwebp, including whether the planned uses and territories fit their stated scope.
- Future FFmpeg configuration changes, additional codecs, or patches that could alter LGPL/GPL compatibility or introduce a nonfree dependency.

Do not describe any codec as patent-cleared worldwide. The public licence page and source archive should continue to state that limitation until counsel resolves the relevant scope.
