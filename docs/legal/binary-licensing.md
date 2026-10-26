# Official binary licensing maintenance

## Distribution boundary

Public source stays under the unmodified root `LICENSE` (PolyForm Perimeter). Only new official binary builds select `-Pindagium.license=eula` and use `EULA.md`. Default development/self-built copies use `polyform`. The binary EULA is an independent grant: users of official EULA builds do not accept PolyForm, and it does not withdraw source or third-party rights. Do not republish old tags/installers or apply the EULA retrospectively. Version 1.8.15's missing public source assets remain separate follow-up work.

The EULA permits free commercial/non-commercial organizational use, internal deployments and backups. Premium subscriptions are optional and require separately disclosed purchase terms. This change does not implement payments, change edition limits, prohibit ordinary use by competing companies, choose governing law, or create warranty/indemnity obligations for upstream contributors.

## Evidence and review boundaries

The two inventories below are grouped records, not a count of physical libraries. Every resolved runtime artifact is checked for an inventory entry and an explicitly reviewed coordinate/version in `reviewed-runtime-modules.json`; new modules or versions fail even if they match a broad attribution group. The media wrapper group is limited to the four actual wrapper modules. The generated `build/reports/licenses/runtime-artifacts-<platform>.json` records its exact coordinate, SHA-256, selected inventory/licence entries and embedded licence/notice paths. Official CI retains those reports with each binary artifact. The eight LGPL/CDDL Maven binaries additionally match SHA-locked source evidence. Their preferred sources and POMs are in the application source lock, which rejects coordinate/version drift against `gradle.lockfile`.

Media evidence maps the four actual classifier configurations and checksums, linked/runtime libraries, legal-text provenance and full source closure. It rejects GPL/nonfree FFmpeg configurations and unexpected classifier bytes. The existing source generator verifies all four classifiers; final native package validation runs separately on each target. Application package validation verifies the exact selected governing agreement, metadata, readable installed files, all inventoried texts and the JVM source mapping/notices. Build failures prevent release preparation. Inventory matching and embedded filenames alone are not a legal certification; review a dependency's upstream terms before changing its approved entry.

The current macOS resolution contains 185 runtime artifacts. Their embedded notice paths and hashes are inspected directly; 115 have no filename identifying a licence/notice and rely on the separately bundled inventory texts. Windows/Linux final packages and replacement smoke tests must pass the configured target CI before a release. No test running on macOS is represented as Windows/Linux execution. Native code signing may change permitted binary metadata; existing media validation keeps its explicit macOS signing rules.

## Copyleft and EULA compatibility

- LGPL: FFmpeg 8.0.1, LanguageTool 6.8, OpenRegex 1.1.1, LAME and ALSA retain library modification/replacement/recombination/relinking rights, modification of the combined work for the user's own use and reverse engineering of the application to debug library changes, as provided by each applicable LGPL version. Neither modification/redistribution restrictions nor premium entitlement clauses override these rights. Compatible JAR/shared-library replacement remains possible. Do not add hash/signature checks that require original library bytes at application runtime.
- MPL: SRT retains MPL-covered source availability and notices. The EULA does not restrict source rights. Sources are in the media archive.
- CDDL: keep the explicit CDDL-1.1 choice for legacy Java EE APIs, full texts, matching source JARs and POMs. Do not silently change that selection to bare GPL. Differing application obligations are undertaken by Roman Arnaut alone, not upstream contributors.
- JVM/GCC: the Classpath Exception and GCC Runtime Library Exception permit applicable combinations with differently licensed application code; they do not eliminate upstream source/notices obligations. The media archive includes the relevant GCC source/recipe evidence. The application archive includes the complete OpenJDK tree, native HotSpot/build inputs, exact Temurin recipe revision and platform build metadata, not merely `src.zip`.
- Apache/MIT/BSD/FTL/Zlib/public-domain components retain their full notices and selected licences. FreeType stays under the FTL choice, not GPL. Preserve shaded R8 notices, the BoringSSL/OpenSSL/Tomcat Native notices of grpc-netty-shaded, dictionary/WordNet/Moby notices, scrcpy Apache notices and JVM module-specific legal files.
- Codec patent grants/notices are retained verbatim. The inventory does not establish worldwide patent clearance. No codec-patent guarantee or upstream indemnity is created by the EULA.

## Native graphics audit findings

Skiko 0.144.6's upstream build properties pin JetBrains Skia `m144-22f58c9fd4`. All four native release archives were inspected for linked static libraries and supplied notices. Their hashes, member paths and notice provenance are in `licenses/application/graphics-notice-provenance.json`; the missing Skiko/AOSP notice, Skia BSD licence, image/font/Unicode notices are now bundled and checked by SHA-256. This closure is separate from the FFmpeg inventory. Skia's root BSD terms cover its own skcms/bentleyottmann modules; external Expat, FreeType (FTL choice), ICU, libjpeg-turbo/IJG, DNG, Piex and Wuffs notices come from the exact upstream DEPS revisions. Native-package development/header notices are retained conservatively without asserting that every optional module is active on each platform.

**Material existing dependency condition:** the macOS Skiko binary contains `dng_info`, `SkRawCodec` and `dng_negative`, confirming compiled DNG support. The Adobe DNG SDK licence, section 5, requires commercial distributors to defend/indemnify Adobe for claims arising from distribution. EULA wording cannot remove this upstream condition. The product owner must assess that condition before commercial distribution; removing DNG would require a separate native rebuild and compatibility review. This implementation preserves and discloses the terms; it does not claim that a legal assessment or negotiation has occurred. It does not add an indemnity from the customer to Indagium. ICU's full notice includes GPL/Autoconf-exception terms for a build-only script; those are not a bare-GPL runtime dependency.

## JVM and non-JAR assets

Official builds pin Temurin **21.0.9+10-LTS**, with verified artifacts for all four targets and matching source/build evidence. This was the verified complete multi-platform release selected for this change; newer platform-incomplete releases were not mixed into it. This is a maintained pin, not a claim that the version remains the newest or has no vulnerabilities. Upgrade all runtime/source records and evidence together when updating it. `prepare_application_sources.py --install-jdk` installs a SHA-verified vendor JDK locally; no global JDK installation is changed. Official packaging checks Gradle's actual vendor/runtime version before jlink, records the generated runtime module image, release properties and legal notices, then verifies those records after installer extraction.

The embedded scrcpy server is independently SHA-pinned Apache-2.0 software. `native/whisper/CMakeLists.txt` pins whisper.cpp 1.9.5 and its SHA-256, retains its MIT licence and records the local allocation-failure patch in source; no copyleft obligation is introduced by that MIT patch. Downloaded Whisper model weights retain their MIT notices and are not silently bundled into installers. Indagium icon assets are project branding, separately reserved in NOTICE; this review does not establish their authorship. System Apple/Windows frameworks and separately provided Flatpak runtimes are distinguished from bundled libraries. AppImage's embedded type-2 runtime is MIT, with its own notice.

## Source delivery, acceptance and release checks

Run `./gradlew validateApplicationLicenseInventory validateApplicationSourceInventory validateMediaLicenseInventory validateMediaRuntimeArtifacts testDistributionLicensing testMediaSourceGenerator prepareApplicationSources prepareMediaSources`. Both source archives have SHA-256 and manifests; acquire them from the exact release without EULA acceptance or payment. English POS dictionary 0.6 publishes no sources JAR: its lock records the preferred text/build-input commit immediately preceding the published binary's build timestamp and the matching versioned POM. Do not describe that evidence as a byte-reproducible rebuild.

The application agreement identity includes model, document version and normalized-text SHA-256; generated Kotlin metadata, bundled Markdown/plain text and the validator must agree. Old PolyForm acceptance cannot accept the EULA. Missing/corrupt agreement resources disable acceptance, including the state method. Updates with identical terms retain acceptance. Upstream notices are available separately, not appended to the EULA as additional terms to accept. On changing the EULA version, update the canonical document, version constants, generated website and tests; preserve already published versioned EULA pages.

Native packages retain readable legal documents in `legal/` at the image root (macOS `Contents/app/legal/`) and separate upstream JVM notices. Official CI includes legal documents, the native media and application/JVM source archives, checksums and manifests with downloadable binary artifacts. GitLab retains these matching artifacts without expiration; GitHub promotes complete artifacts to durable public release assets. The GitHub release workflow refuses to modify an already published release, deduplicates identical artifacts, validates the complete package/source/legal set, uploads a draft, verifies all uploaded bytes/digests, and only then publishes it. Failed uploads leave a draft, not a partially published EULA release.

Run `python3 scripts/verify_lgpl_replacement.py --image <extracted-image> --jdk-home <pinned-jdk>` after `prepareApplicationSources`. It copies the image into a temporary directory, rebuilds OpenRegex from the pinned source JAR, modifies the LanguageTool JAR with a test marker and changes only the informational FFmpeg configure-prefix string in the native library (re-signing the test copy on macOS). It verifies proofreading and that the modified shared library is actually loaded with an isolated JavaCPP cache. No original package or installed app is modified. Also run platform-compatible replacement smoke tests as described in both SOURCE-BUNDLE documents. Test media playback, proofreading and Compact archive operations. Re-sign copied packages only as required by the platform; a licensor signature is not a prerequisite to LGPL rights. Preserve historical evidence and contributor acknowledgements; the current contributor grant does not prove agreement by historical contributors or ownership of employer-created work.

## Inventory review map

| Inventory | Component record | Selected licence / reference |
| --- | --- | --- |
| Application | `kotlin-compose-androidx` | `Apache-2.0` |
| Application | `ktor` | `Apache-2.0` |
| Application | `mcp-kotlin-sdk` | `MIT AND Apache-2.0` |
| Application | `slf4j` | `MIT` |
| Application | `kotlin-logging` | `Apache-2.0` |
| Application | `apache-commons` | `Apache-2.0` |
| Application | `jansi` | `Apache-2.0` |
| Application | `markdown-renderer` | `Apache-2.0` |
| Application | `zxing` | `Apache-2.0` |
| Application | `xz-java` | `0BSD` |
| Application | `r8` | `BSD-3-Clause AND Apache-2.0 AND MIT` |
| Application | `google-libraries` | `Apache-2.0` |
| Application | `grpc-netty-native` | `Apache-2.0 AND OpenSSL AND ISC AND MIT` |
| Application | `protobuf-java` | `BSD-3-Clause` |
| Application | `languagetool` | `LGPL-2.1-or-later` |
| Application | `languagetool-apache-deps` | `Apache-2.0` |
| Application | `morfologik` | `BSD-3-Clause` |
| Application | `units-of-measurement` | `BSD-3-Clause` |
| Application | `segment` | `MIT` |
| Application | `openregex` | `LGPL-3.0-only` |
| Application | `jakarta-xml-binding` | `BSD-3-Clause` |
| Application | `javax-xml-binding` | `CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0` |
| Application | `public-domain` | `LicenseRef-Public-Domain AND CC0-1.0` |
| Application | `animal-sniffer-annotations` | `MIT` |
| Application | `media-closure` | `LicenseRef-Media-Inventory` |
| Application | `scrcpy-server` | `Apache-2.0` |
| Application | `appimage-runtime` | `MIT` |
| Media | `javacv` | `Apache-2.0` |
| Media | `javacpp` | `Apache-2.0` |
| Media | `ffmpeg` | `LGPL-3.0-or-later` |
| Media | `zstd-jni` | `BSD-2-Clause` |
| Media | `zstandard-native` | `BSD-3-Clause` |
| Media | `libaom` | `BSD-2-Clause AND LicenseRef-AOM-Patent-1.0` |
| Media | `svt-av1` | `BSD-3-Clause-Clear AND LicenseRef-AOM-Patent-1.0` |
| Media | `zlib` | `Zlib` |
| Media | `lame` | `LGPL-2.0-or-later` |
| Media | `speex` | `BSD-3-Clause` |
| Media | `opencore-amr` | `Apache-2.0` |
| Media | `vo-amrwbenc` | `Apache-2.0` |
| Media | `openssl` | `Apache-2.0` |
| Media | `openh264` | `BSD-2-Clause AND LicenseRef-OpenH264-Patent-Notice` |
| Media | `libvpx` | `BSD-3-Clause AND LicenseRef-Google-Patent-Grant` |
| Media | `alsa-lib` | `LGPL-2.1-or-later` |
| Media | `freetype` | `LicenseRef-FTL AND MIT AND Zlib` |
| Media | `harfbuzz` | `MIT` |
| Media | `opus` | `BSD-3-Clause` |
| Media | `libxml2` | `MIT` |
| Media | `srt` | `MPL-2.0` |
| Media | `libwebp` | `BSD-3-Clause AND LicenseRef-Google-Patent-Grant` |
| Media | `zimg` | `WTFPL` |
| Media | `gcc-libatomic` | `GPL-3.0-or-later WITH GCC-exception-3.1` |
| Media | `gcc-runtime-windows` | `GPL-3.0-or-later WITH GCC-exception-3.1` |
| Media | `mingw-w64-crt` | `LicenseRef-mingw-w64` |
| Media | `winpthreads` | `LicenseRef-mingw-w64` |
| Media | `libva` | `MIT` |
| Media | `libva-drm` | `MIT` |
| Media | `libdrm` | `MIT` |
| Media | `bcm-host` | `BSD-3-Clause` |
| Media | `vchiq-arm` | `BSD-3-Clause` |
| Media | `vcos` | `BSD-3-Clause` |
| Media | `alsa-runtime` | `LGPL-2.1-or-later` |
| Media | `mfx-dispatch` | `MIT` |
| Media | `nv-codec-headers` | `MIT` |
| Media | `source-delivery` | `LicenseRef-Indagium-Source-Instructions` |
| Application | `skia-native` | `LicenseRef-Skia-Native-Notices` |
