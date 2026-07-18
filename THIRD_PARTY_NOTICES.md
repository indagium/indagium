# Third-Party Notices

openLog includes third-party components. Their licenses apply to those components independently of the license for openLog itself.

| Component | License |
| --- | --- |
| Kotlin, JetBrains Compose, AndroidX, Ktor, kotlinx libraries, Skiko, Apache Commons Compress, Jansi, and Multiplatform Markdown Renderer | Apache License 2.0 |
| Model Context Protocol Kotlin SDK and SLF4J | MIT License |
| XZ for Java 1.10 | 0BSD License |

Native packages also bundle a Java runtime. The bundled Temurin/OpenJDK runtime includes its own notices and is licensed under GPLv2 with the Classpath Exception and additional component notices. Distribution packages must retain the notices supplied with that runtime.

The release workflow produces a resolved dependency report for each release. Review it when dependency versions change and update this notice if the license inventory changes.
