# Third-Party Notices

Indagium ships third-party software under the separate terms shown here. These licences apply to their components and are not replaced or restricted by the EULA for official binaries or the PolyForm licence for public source. Third-party rights take precedence for their components. Neither EULA acceptance nor payment is required to obtain their published sources.

For new official binary releases, matching native sources are in `Indagium-<version>-media-sources.tar.gz` and Java/JVM sources and build materials in `Indagium-<version>-application-sources.tar.gz`, supplied alongside the installers with checksums and manifests. See [application/JVM source instructions](licenses/application/SOURCE-BUNDLE.md) and [media source instructions](licenses/third-party/SOURCE-BUNDLE.md). Existing published binaries retain their original terms; these instructions do not relicense them.

Paths in the application table are relative to `licenses/` in the repository and in the app's bundled resources (the LanguageTool text is checked in as `src/desktopMain/resources/licenses/languagetool-LGPL-2.1.txt`); paths in the media table are relative to `licenses/third-party/`. The application components are kept in [`licenses/application/application-components.json`](licenses/application/application-components.json), and `./gradlew validateApplicationLicenseInventory` fails when a runtime module is not covered by it. Where a component offers a choice of licences, Indagium uses JNA under Apache-2.0 and the legacy Java EE APIs under CDDL-1.1. The LGPL libraries (LanguageTool, OpenRegex) are used unmodified as separate JARs in the app's `app/` directory and may be replaced by compatible builds.

| Application component | Version | Scope | SPDX/custom identifier | Full texts and notices |
| --- | --- | --- | --- | --- |
| Kotlin, Compose Multiplatform, Skiko and AndroidX | Kotlin 2.4.0, Compose 1.11.1, Skiko 0.144.6 | Application runtime and UI toolkit | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Skia native graphics closure | Skiko 0.144.6 / JetBrains Skia m144-22f58c9fd4 | Native rendering, fonts and image codecs; separate from FFmpeg | `LicenseRef-Skia-Native-Notices` (BSD, Apache, MIT, Unicode, FTL, IJG, libpng, Zlib and Adobe DNG terms) | `application/texts/skia-native-notices.txt`, `application/texts/skiko-NOTICE.txt`; provenance in `application/graphics-notice-provenance.json` |
| libffi in JNA | As pinned by JNA 5.12.1 | Native dispatch support; JNA itself is used under Apache-2.0 | `MIT` | `application/texts/jna-libffi-license.txt` |
| Ktor | 3.4.3 | Loopback control server and HTTP clients | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Model Context Protocol Kotlin SDK | 0.14.0 | MCP server and client | `MIT AND Apache-2.0` | `application/texts/mcp-kotlin-sdk-license.txt` |
| SLF4J | 2.0.17 | Logging facade (no-op binding) | `MIT` | `application/texts/slf4j-license.txt` |
| kotlin-logging | 8.0.4 | Logging used by the MCP SDK | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Apache Commons | Compress 1.28.0, Lang 3.18.0, IO 2.20.0, Codec 1.19.0 and others | Archive, text, I/O and validation utilities | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Jansi | 2.4.2 | Transitive dependency (terminal colour support) | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Multiplatform Markdown Renderer | 0.41.0 | Markdown rendering in notes and AI panels | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| ZXing | 3.5.3 | QR code for the wireless-debugging pairing dialog | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| XZ for Java | 1.10 | XZ/7z decompression | `0BSD` | `application/texts/xz-java-0bsd.txt` |
| R8 | 9.4.17 | Retrace API for deobfuscating stack traces | `BSD-3-Clause AND Apache-2.0 AND MIT` | `application/texts/r8-license.txt` |
| Guava, Gson, gRPC, OpenTelemetry and annotation libraries | Guava 33.5.0, gRPC 1.75.0, OpenTelemetry 1.42.1 | Transitive dependencies of LanguageTool and the MCP SDK | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Protocol Buffers for Java | 3.25.8 | Transitive dependency of LanguageTool (gRPC) | `BSD-3-Clause` | `application/texts/protobuf-license.txt` |
| LanguageTool | 6.8 (english-pos-dict 0.6) | Offline English proofreading | `LGPL-2.1-or-later` | `languagetool-LGPL-2.1.txt` |
| LanguageTool Apache-licensed dependencies | Lucene 5.5.5, OpenNLP 1.9.4, Jackson 2.18.0, JNA 5.12.1 and others | Transitive dependencies of LanguageTool | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| Morfologik | 2.1.9 | Spelling dictionaries for LanguageTool | `BSD-3-Clause` | `application/texts/morfologik-license.txt` |
| Units of Measurement (unit-api, Indriya, uom-lib) | unit-api 1.0, Indriya 1.3, uom-lib-common 1.1 | Transitive dependency of LanguageTool | `BSD-3-Clause` | `application/texts/unit-api-license.txt`, `application/texts/indriya-license.txt`, `application/texts/uom-lib-license.txt` |
| Segment | 2.0.3 | Sentence segmentation for LanguageTool | `MIT` | `application/texts/segment-license.txt` |
| OpenRegex | 1.1.1 | Token regular expressions for LanguageTool | `LGPL-3.0-only` | `application/texts/openregex-lgpl-3.0.txt`, `third-party/texts/gpl-3.0.txt` |
| Jakarta XML Binding and Activation | JAXB runtime 4.0.5, jaxb-impl 2.3.3, istack 4.1.2, Activation 2.1.3 | Transitive dependency of LanguageTool | `BSD-3-Clause` | `application/texts/eclipse-distribution-license-1.0.txt` |
| Legacy Java EE APIs (JAXB API, jaxb-core, Annotation, Activation) | jaxb-api 2.3.1, jaxb-core 2.3.0.1, annotation-api 1.3.2, activation-api 1.2.0 | Transitive dependency of LanguageTool | `CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0` | `application/texts/cddl-1.1-gpl-2.0-cpe.txt` |
| JSON-java, HdrHistogram and LatencyUtils | JSON-java 20240303, HdrHistogram 2.1.12, LatencyUtils 2.0.3 | Transitive dependencies of LanguageTool | `LicenseRef-Public-Domain AND CC0-1.0` | `application/texts/json-java-license.txt`, `application/texts/hdrhistogram-license.txt`, `application/texts/latencyutils-cc0-notice.txt` |
| Animal Sniffer annotations | 1.24 | Annotation-only transitive dependency | `MIT` | `application/texts/animal-sniffer-license.txt` |
| scrcpy server | 4.1 | Embedded device mirror (pushed to the Android device) | `Apache-2.0` | `third-party/texts/apache-2.0.txt` |
| AppImage type 2 runtime | As embedded by the pinned appimagetool | Linux AppImage packages only | `MIT` | `application/texts/appimage-type2-runtime-license.txt` |
| `whisper.cpp` | 1.9.5 | Statically linked into the generated native voice bridge | `MIT` | `whisper.cpp-LICENSE.txt` (upstream `LICENSE`, generated at build time) |
| OpenAI Whisper model weights distributed through `ggerganov/whisper.cpp` | Pinned Hugging Face model repository | Downloaded only after an explicit user action | `MIT` | Model repository licence |
| Temurin/OpenJDK bundled runtime | 21.0.9+10-LTS in new official release CI | Supplied with native packages; complete source and build recipes pinned in the application source lock | `GPL-2.0-only WITH Classpath-exception-2.0` | Runtime `legal/` directory and packaged runtime manifest |

The FFmpeg native libraries use the shared LGPL 3.0-or-later build of FFmpeg 8.0.1 (`org.bytedeco:ffmpeg` 8.0.1-1.5.13). The inspected four platform configurations include `--enable-version3`, omit `--enable-gpl` and `--enable-nonfree`, and do not link x264 or x265. FFmpeg is distributed as replaceable classifier JARs. LGPL-covered modifications and reverse engineering to debug those modifications remain permitted under the applicable licence terms. See [the source instructions](licenses/third-party/SOURCE-BUNDLE.md), which describe the replacement layout and source archive preparation task.

The separate Skia native graphics stack retains its upstream notices, including the Skiko AOSP notice, FreeType Project (https://www.freetype.org/) terms and libjpeg-turbo/IJG notices. This software is based in part on the work of the Independent JPEG Group. This product includes DNG technology under license by Adobe Systems Incorporated. The Adobe DNG SDK terms include commercial-distribution indemnification conditions; they are preserved in full and are not replaced by the EULA. ICU's complete licence text also contains terms for the build-only `config.guess` script with an Autoconf exception; that inclusion does not put Indagium under GPL.

OpenH264 2.6.0 is built from its source release. Indagium does not include the separately distributed Cisco OpenH264 binary. The inventory also records target-specific runtime libraries and GCC/MinGW notices found inside or linked by the inspected classifiers.

| Component | Version | Scope | SPDX/custom identifier | Platforms | Full texts and notices |
| --- | --- | --- | --- | --- | --- |
| JavaCV (`javacv`) | 1.5.13 | bundled-java | `Apache-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/apache-2.0.txt` |
| JavaCPP (`javacpp`) | 1.5.13 | bundled-java | `Apache-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/apache-2.0.txt` |
| FFmpeg (`ffmpeg`) | 8.0.1-1.5.13 | bundled-native | `LGPL-3.0-or-later` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/ffmpeg-lgpl-3.0.txt`, `texts/gpl-3.0.txt` |
| zstd-jni (`zstd-jni`) | 1.5.7-4 | bundled-native | `BSD-2-Clause` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/zstd-jni-license.txt` |
| Zstandard native library (`zstandard-native`) | 1.5.7 | bundled-native-compression | `BSD-3-Clause` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/zstd-license.txt` |
| libaom (`libaom`) | 3.9.1 | ffmpeg-linked-native | `BSD-2-Clause AND LicenseRef-AOM-Patent-1.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/libaom-license.txt`, `texts/aom-patent-license.txt`, `texts/libaom-fastfeat-license.txt`, `texts/libaom-libyuv-license.txt`, `texts/libaom-libwebm-license.txt`, `texts/libaom-libwebm-patents.txt`, `texts/libaom-vector-license.txt`, `texts/libaom-x86inc-license.txt` |
| SVT-AV1 (`svt-av1`) | 3.1.2 | ffmpeg-linked-native | `BSD-3-Clause-Clear AND LicenseRef-AOM-Patent-1.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/svt-av1-license.md`, `texts/svt-av1-patents.md`, `texts/svt-fastfeat-license.txt`, `texts/svt-safestringlib-license.txt` |
| zlib (`zlib`) | 1.3.2 | ffmpeg-linked-native | `Zlib` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/zlib-license.txt` |
| LAME (`lame`) | 3.100 | ffmpeg-linked-native | `LGPL-2.0-or-later` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/lame-license.txt`, `texts/lame-notice.txt` |
| Speex (`speex`) | 1.2.1 | ffmpeg-linked-native | `BSD-3-Clause` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/speex-license.txt` |
| OpenCORE AMR (`opencore-amr`) | 0.1.6 | ffmpeg-linked-native | `Apache-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/apache-2.0.txt`, `texts/opencore-amr-notice.txt`, `texts/vo-amrwbenc-patent-disclaimer.txt` |
| VisualOn AMR-WB encoder (`vo-amrwbenc`) | 0.1.3 | ffmpeg-linked-native | `Apache-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/apache-2.0.txt`, `texts/vo-amrwbenc-notice.txt`, `texts/vo-amrwbenc-patent-disclaimer.txt` |
| OpenSSL (`openssl`) | 3.5.5 | ffmpeg-linked-native | `Apache-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/openssl-license.txt` |
| OpenH264 (`openh264`) | 2.6.0 | ffmpeg-linked-native | `BSD-2-Clause AND LicenseRef-OpenH264-Patent-Notice` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/openh264-license.txt` |
| libvpx (`libvpx`) | 1.15.2 | ffmpeg-linked-native | `BSD-3-Clause AND LicenseRef-Google-Patent-Grant` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/libvpx-license.txt`, `texts/libvpx-patents.txt` |
| ALSA library (`alsa-lib`) | 1.2.14 | ffmpeg-linked-native-or-platform-runtime | `LGPL-2.1-or-later` | linux-arm64 | `texts/alsa-lib-license.txt` |
| FreeType (`freetype`) | 2.14.1 | ffmpeg-linked-native | `LicenseRef-FTL AND MIT AND Zlib` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/freetype-license-choice.txt`, `texts/freetype-ftl-license.txt`, `texts/freetype-bdf-notice.txt`, `texts/freetype-pcf-notice.txt`, `texts/freetype-zlib-notice.txt`, `texts/freetype-zutil-notice.txt` |
| HarfBuzz (`harfbuzz`) | 12.3.0 | ffmpeg-linked-native | `MIT` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/harfbuzz-license.txt` |
| Opus (`opus`) | 1.3.1 | ffmpeg-linked-native | `BSD-3-Clause` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/opus-license.txt` |
| libxml2 (`libxml2`) | 2.9.12 | ffmpeg-linked-native | `MIT` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/libxml2-license.txt` |
| SRT (`srt`) | 1.5.4 | ffmpeg-linked-native | `MPL-2.0` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/srt-license.txt` |
| libwebp (`libwebp`) | 1.6.0 | ffmpeg-linked-native | `BSD-3-Clause AND LicenseRef-Google-Patent-Grant` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/libwebp-license.txt`, `texts/libwebp-patents.txt` |
| zimg (`zimg`) | 3.0.6 | ffmpeg-linked-native | `WTFPL` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/zimg-license.txt` |
| GCC libatomic runtime (`gcc-libatomic`) | 13.4.0 | bundled-native-macos-arm64-classifier (observed binary is Mach-O x86_64) | `GPL-3.0-or-later WITH GCC-exception-3.1` | macos-arm64 | `texts/gpl-3.0.txt`, `texts/gcc-runtime-exception-3.1.txt` |
| GCC runtime libraries (Windows) (`gcc-runtime-windows`) | 14.1.0-3 (MSYS2) | statically linked in Windows FFmpeg DLLs | `GPL-3.0-or-later WITH GCC-exception-3.1` | windows-x64 | `texts/gpl-3.0.txt`, `texts/gcc-runtime-exception-3.1.txt` |
| MinGW-w64 C runtime and headers (`mingw-w64-crt`) | commit 90abf784ac344fe558e7e22cb5411d807862888d | statically linked Windows runtime inputs | `LicenseRef-mingw-w64` | windows-x64 | `texts/mingw-w64-license.txt` |
| winpthreads (`winpthreads`) | 12.0.0.r81.g90abf784a-1 | statically linked Windows runtime input | `LicenseRef-mingw-w64` | windows-x64 | `texts/winpthreads-license.txt` |
| Video Acceleration API (libva) (`libva`) | 2.14.0-1 (Ubuntu 22.04 source package) | bundled-native-linux-x64 | `MIT` | linux-x64 | `texts/libva-license.txt`, `texts/libva-ubuntu-copyright.txt` |
| Video Acceleration API DRM backend (`libva-drm`) | 2.14.0-1 (Ubuntu 22.04 source package) | bundled-native-linux-x64 | `MIT` | linux-x64 | `texts/libva-license.txt`, `texts/libva-ubuntu-copyright.txt` |
| Direct Rendering Manager library (`libdrm`) | 2.4.113-2~ubuntu0.22.04.1 | bundled-native-linux-x64 | `MIT` | linux-x64 | `texts/libdrm-ubuntu-copyright.txt` |
| Raspberry Pi bcm_host userland (`bcm-host`) | commit a54a0dbb2b8dcf9bafdddfc9a9374fb51d97e976 | bundled-native-linux-arm64 | `BSD-3-Clause` | linux-arm64 | `texts/raspberry-pi-userland-license.txt` |
| Raspberry Pi vchiq_arm userland (`vchiq-arm`) | commit a54a0dbb2b8dcf9bafdddfc9a9374fb51d97e976 | bundled-native-linux-arm64 | `BSD-3-Clause` | linux-arm64 | `texts/raspberry-pi-userland-license.txt` |
| Raspberry Pi vcos userland (`vcos`) | commit a54a0dbb2b8dcf9bafdddfc9a9374fb51d97e976 | bundled-native-linux-arm64 | `BSD-3-Clause` | linux-arm64 | `texts/raspberry-pi-userland-license.txt` |
| ALSA runtime library (`alsa-runtime`) | 1.2.14 | bundled-native-linux-arm64 | `LGPL-2.1-or-later` | linux-arm64 | `texts/alsa-lib-license.txt` |
| Intel Media SDK dispatcher (`mfx-dispatch`) | 1.35.1 | FFmpeg-linked native dependency on Windows and Linux x64 | `MIT` | linux-x64, windows-x64 | `texts/mfx-dispatch-license.txt` |
| NVIDIA codec headers (`nv-codec-headers`) | 13.0.19.0 | pinned FFmpeg source/build input; no standalone runtime library | `MIT` | linux-arm64, linux-x64, macos-arm64, windows-x64 | `texts/nv-codec-dynlink_cuda.h-notice.txt`, `texts/nv-codec-dynlink_cuviddec.h-notice.txt`, `texts/nv-codec-dynlink_loader.h-notice.txt`, `texts/nv-codec-dynlink_nvcuvid.h-notice.txt`, `texts/nv-codec-nvEncodeAPI.h-notice.txt` |

The [machine-readable inventory](licenses/third-party/media-components.json) records upstream copyright and attribution, legal-text member provenance, source references and platform scope for each component. Its full legal texts are pinned by SHA-256 and included under `licenses/third-party/texts/`. The source lock also pins every source archive and the four inspected platform classifier JARs. See [SOURCE-BUNDLE.md](licenses/third-party/SOURCE-BUNDLE.md) for rebuild, replacement, and patent-scope limitations; it does not claim bit-for-bit reproducibility or worldwide patent clearance.

Retain the runtime notices supplied with each native package. Dependency-specific notices remain bundled with their JARs and runtime images.
