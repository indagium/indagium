package com.indagium.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every production file that uses a JavaCPP/FFmpeg class (`org.bytedeco`) must call
 * [awaitFfmpegNatives] before the first use, so the shared bootstrap in FfmpegNatives.kt is the only
 * place JavaCPP classes are first initialized (see the comment there for the deadlock). A file that
 * legitimately cannot await goes in [ALLOWED] with the reason; a stale entry fails too.
 */
class NativeLoadGuardTest {
    @Test
    fun everyFileUsingJavaCppAwaitsTheSharedLoadOrIsAllowlisted() {
        val root = File("src/desktopMain/kotlin")
        assertTrue(root.isDirectory, "run from the project directory: ${root.absolutePath}")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val users = files.filter { it.name != BOOTSTRAP_FILE && "org.bytedeco" in it.readText() }
        val problems = mutableListOf<String>()

        for (file in users) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            val awaits = AWAIT_CALL in file.readText()
            when {
                name in ALLOWED && awaits -> problems += "$name awaits the load now; remove it from ALLOWED"
                name !in ALLOWED && !awaits -> problems += "$name uses org.bytedeco but never calls awaitFfmpegNatives()"
            }
        }
        val userNames = users.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        ALLOWED.keys.filter { it !in userNames }.forEach { problems += "$it is in ALLOWED but no longer references org.bytedeco" }

        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        const val AWAIT_CALL = "awaitFfmpegNatives()"
        const val BOOTSTRAP_FILE = "FfmpegNatives.kt"
        const val PATH_PREFIX = "com/indagium/"

        val ALLOWED: Map<String, String> = mapOf(
            "${PATH_PREFIX}utils/HeapTrim.kt" to "only trims once ffmpegNativesLoaded(), never starts the load",
            "${PATH_PREFIX}video/GrabberSeek.kt" to "extension on a FFmpegFrameGrabber the caller already created after the await",
            "${PATH_PREFIX}capture/mirror/EmbeddedDeviceSession.kt" to "only inlined AV_CODEC_ID_* int constants",
            "${PATH_PREFIX}ui/EmbeddedMirrorGpuSurface.kt" to "only inlined AV_* int constants; the decoder it builds awaits in decode()",
            "${PATH_PREFIX}capture/mirror/LinuxVaapiEglMirrorNative.kt" to "present() only receives frames from a decoder that awaited",
            "${PATH_PREFIX}capture/mirror/WindowsD3D11MirrorNative.kt" to "present() only receives frames from a decoder that awaited",
        )
    }
}
