# Third-Party Notices

Indagium includes third-party components. Their licenses apply to those components independently of the license for Indagium itself.

| Component | License |
| --- | --- |
| Kotlin, JetBrains Compose, AndroidX, Ktor, kotlinx libraries, Skiko, Apache Commons Compress, Jansi, Multiplatform Markdown Renderer, and ZXing | Apache License 2.0 |
| Model Context Protocol Kotlin SDK and SLF4J | MIT License |
| `whisper.cpp` 1.9.5, statically linked into the generated native voice bridge | MIT License (upstream `LICENSE`) |
| OpenAI Whisper model weights distributed through `ggerganov/whisper.cpp` | MIT License (pinned Hugging Face model repository) |
| XZ for Java 1.10 | 0BSD License |
| zstd-jni 1.5.7-4, including the bundled Zstandard native code (used under its BSD license) | BSD 2-Clause License |
| JavaCV 1.5.13 and JavaCPP 1.5.13 (bytedeco; Java bindings and native-library loader for the bundled FFmpeg) | Apache License 2.0 (their POMs also offer GPLv2 options, which Indagium does not use) |
| FFmpeg 8.0.1 native libraries (libavcodec, libavformat, libavutil, libswscale, libswresample), packaged as `org.bytedeco:ffmpeg` 8.0.1-1.5.13 and loaded as shared libraries | LGPL version 3 or later (built with `--enable-version3`, without `--enable-gpl`) |
| libaom, the AV1 codec linked into the bundled FFmpeg build (AV1 decoding) | BSD-style license plus the Alliance for Open Media Patent License 1.0 |
| SVT-AV1, the AV1 encoder linked into the bundled FFmpeg build (Indagium Compact exports) | BSD-style license plus the Alliance for Open Media Patent License 1.0 |

The bundled FFmpeg build also links other third-party libraries, among them OpenH264, libvpx, Opus, LAME, Speex, OpenCORE AMR, OpenSSL, libwebp, zimg, SRT and libxml2, each under its own license. FFmpeg and its build scripts are available from https://ffmpeg.org and https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg; the exact libraries are fixed by the `org.bytedeco:ffmpeg` version listed above.

Native packages also bundle a Java runtime. The bundled Temurin/OpenJDK runtime includes its own notices and is licensed under GPLv2 with the Classpath Exception and additional component notices. Distribution packages must retain the notices supplied with that runtime.

The release workflow produces a resolved dependency report for each release. Review it when dependency versions change and update this notice if the license inventory changes.
