# Indagium media source bundle

This repository records the source inputs for media and compression libraries distributed with Indagium 1.8.15. The inspected versions are FFmpeg 8.0.1-1.5.13, JavaCV and JavaCPP 1.5.13, and zstd-jni 1.5.7-4. The pinned FFmpeg classifier libraries cover macOS arm64, Windows x64, Linux x64, and Linux arm64. The lock includes native source archives, the complete Bytedeco recipe tree and patches, relevant compiler/runtime sources, target configurations, legal notices, and checksums.

## Prepare and verify the source archive

From the repository root, run:

```sh
./gradlew prepareMediaSources
```

The task downloads only HTTPS archives and Maven artifacts listed in `media-source-lock.json`, checks each SHA-256, validates the inventory and all four FFmpeg configurations, and writes these local build artifacts under `build/`:

- `Indagium-1.8.15-media-sources.tar.gz`
- `Indagium-1.8.15-media-sources.tar.gz.sha256`
- `Indagium-1.8.15-media-sources.tar.gz.manifest.json`

Verify an archive with `sha256sum -c Indagium-1.8.15-media-sources.tar.gz.sha256`. Its manifest records every included file, byte size, and SHA-256. Source archives are preserved as downloaded; the lock records their URLs, versions or immutable references, and hashes. The task creates a local artifact. New official EULA releases must publish it, its checksum and manifest alongside the corresponding installers, together with the application/JVM source archive described in licenses/application/SOURCE-BUNDLE.md. Obtain the exact version from https://github.com/indagium/indagium/releases without accepting the EULA or paying. Downloadable CI packages must include the same sources. This procedure does not change old releases or assert that their missing source assets have been supplied.

## Rebuild the FFmpeg classifiers

Extract `sources/javacpp-presets-60df69b.tar.gz`, enter the extracted `javacpp-presets-60df69b176e3623da1d53db432a5a3a82a13f2ea` directory, and apply `build-inputs/indagium-pinned-build-inputs.patch` with `patch -p1`. This patch replaces the mutable x264 `stable` reference with commit `b35605ace3ddf7c1a5d67a2eb553f034aef41d55`, replaces the HTTP libxml2 download with the SHA-locked HTTPS 2.9.12 archive, and pins Raspberry Pi userland to commit `a54a0dbb2b8dcf9bafdddfc9a9374fb51d97e976`.

The recipe's `download` helper uses the `downloads/` directory at the source-tree root. To build without network access, copy each recipe archive from `sources/` into `downloads/` using the exact `archive` filename recorded in `legal/media-source-lock.json`. For the Raspberry Pi userland archive, name the copy `userland-a54a0db.tar.gz`, as required by the pinned setup action. Keep the original source archives unchanged. The lock also includes Ubuntu libva/libdrm source-package descriptors and archives, GCC, MinGW-w64 and packaging recipes, plus supplementary Maven Java sources; these support the documented runtime and legal inventory even when they are not downloaded by the FFmpeg shell recipe itself.

Install the toolchains and host packages identified by the pinned setup actions under `sources/javacpp-presets-60df69b176e3623da1d53db432a5a3a82a13f2ea/.github/actions/`, then run the recipe from the extracted source-tree root for each target:

```sh
bash cppbuild.sh -platform macosx-arm64 install ffmpeg
bash cppbuild.sh -platform windows-x86_64 install ffmpeg
bash cppbuild.sh -platform linux-x86_64 install ffmpeg
bash cppbuild.sh -platform linux-arm64 install ffmpeg
```

The equivalent Maven build entry point is `mvn -B -f ffmpeg/pom.xml -Djavacpp.platform=<classifier> install`, with classifier values `macosx-arm64`, `windows-x86_64`, `linux-x86_64`, and `linux-arm64`. Build the normal FFmpeg configuration without the optional `gpl` extension. The inspected packaged configuration uses shared libraries, `--enable-version3`, and no `--enable-gpl` or `--enable-nonfree`; the source build should retain those choices.

The zstd-jni 1.5.7-4 source archive contains its JNI wrapper and native Zstandard C sources under `src/main/native`; the separate upstream Zstandard 1.5.7 archive is included as an independently inspectable source reference. zstd-jni uses SBT, not Maven. The source archive includes its `sbt-java-module-info` helper; from the zstd-jni project root, first run `cd sbt-java-module-info && ./sbt publishLocal && cd -`, then `./sbt compile test package`. Follow the archived README and `make_so.sh`/`make_so_cross.sh` scripts for the target classifier and toolchain. Do not treat the FFmpeg recipe as a zstd-jni build recipe or claim that an arbitrary host build reproduces the published classifier.

## Replace an FFmpeg classifier in an application image

The bundled FFmpeg shared libraries reside in the classifier JAR for the target platform. Replace the corresponding classifier JAR in an extracted app image while retaining JavaCV/JavaCPP bindings and legal resources. The app directory is platform-specific:

- macOS: `<Indagium.app>/Contents/app/`
- Windows: `<install directory>/app/`
- Linux: `<install directory>/lib/app/`

Rebuild the matching classifier and retain the original JAR naming pattern, native library names, and compatible interfaces. Keep all applicable legal resources and update notices when the replacement changes the covered components. Repackage the application image after replacement. Modifying a signed installer or app bundle can invalidate its signature; sign a redistributed modified package as required by the platform.

The EULA governs new official Indagium binaries; public source code remains under PolyForm. Neither replaces or restricts rights granted by third-party licences. Third-party sources are supplied under their original licences, not the EULA. The EULA does not restrict compatible replacement, recombination, relinking, modification of the combined work for your own use, or reverse engineering to debug LGPL modifications as allowed by the applicable licence. In particular, LGPL terms for FFmpeg preserve rights to modify the LGPL-covered code and permit reverse engineering to debug those modifications, subject to the licence conditions. See `legal/texts/ffmpeg-lgpl-3.0.txt`, `legal/media-components.json`, and `legal/THIRD_PARTY_NOTICES.md`.

## Scope and limitations

The inventory distinguishes libraries linked into FFmpeg, native runtime libraries, Java bindings, and build-only inputs. OpenH264 is built from its source release; the separately distributed Cisco OpenH264 binary is not included. Linux x64 includes the inspected Ubuntu libva/libdrm runtime files, Linux arm64 includes Raspberry Pi userland and ALSA libraries, Windows includes GCC runtime and MinGW/winpthreads inputs, and the macOS arm64 classifier contains an inspected bundled `libatomic.1.dylib` whose Mach-O architecture is x86_64. The inventory records these shipped facts without asserting that the macOS library can be loaded on arm64.

This bundle pins source archives and records build inputs; it does not claim that the original or a rebuilt binary is bit-for-bit reproducible. Compiler versions, operating-system packages, signing, and other host build details can affect output bytes. Patent coverage is not established by this source bundle. An assessment of H.264 and other codec patent claims, territories, and distribution models remains separate; included AOM patent terms are not a worldwide patent clearance.
