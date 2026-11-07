package com.indagium.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Regression for the JavaCPP class-initialization deadlock: `Loader.loadLibrary` holds the Loader
 * monitor while a library's JNI_OnLoad waits for `Pointer`'s class initialization, and a thread
 * inside `Pointer.<clinit>` waits for that monitor. It needs a process where nothing has touched
 * JavaCPP yet, so each round runs [JavaCppFirstTouchProbe] in a fresh JVM: many threads that are
 * the first to touch FFmpeg through different production entry points, released together. Every
 * entry point must go through [awaitFfmpegNatives], so the probe has to exit 0 well within the
 * timeout. The old code failed this in about one round in thirty when only the video load and a
 * capture preview raced; INDAGIUM_PROBE_ROUNDS and INDAGIUM_PROBE_ONLY (a comma-separated list of
 * the probe's touch names) turn it into a longer stress run.
 */
class JavaCppFirstTouchDeadlockTest {
    @Test
    fun simultaneousFirstTouchesInAFreshJvmNeverDeadlock() {
        val rounds = System.getenv("INDAGIUM_PROBE_ROUNDS")?.toIntOrNull() ?: ROUNDS
        repeat(rounds) { round ->
            val work = Files.createTempDirectory("javacpp-first-touch-").toFile()
            try {
                runProbe(round, work)
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private fun runProbe(round: Int, work: File) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = buildList {
            add(java)
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("-Djava.awt.headless=true")
            // The JavaCPP cache lives under user.home (the build's test-home) unless overridden;
            // sharing it keeps the fresh JVM from re-extracting the natives.
            add("-Duser.home=${System.getProperty("user.home")}")
            System.getProperty("org.bytedeco.javacpp.cachedir")?.let { add("-Dorg.bytedeco.javacpp.cachedir=$it") }
            add(JavaCppFirstTouchProbe::class.java.name)
            add(work.path)
            add(JITTER_MS.toString())
            add(System.getenv("INDAGIUM_PROBE_ONLY").orEmpty())
        }
        val output = File(work, "probe-output.txt")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val stacks = jstack(process.pid())
            process.destroyForcibly()
            process.waitFor(DESTROY_SECONDS, TimeUnit.SECONDS)
            fail("Round $round: the JavaCPP first-touch probe hung for ${TIMEOUT_SECONDS}s.\n$stacks\n${output.readTextOrEmpty()}")
        }
        assertEquals(0, process.exitValue(), "Round $round: probe failed.\n${output.readTextOrEmpty()}")
    }

    private fun jstack(pid: Long): String = runCatching {
        val tool = File(System.getProperty("java.home"), "bin/jstack").path
        val dump = ProcessBuilder(tool, pid.toString()).redirectErrorStream(true).start()
        val text = dump.inputStream.bufferedReader().readText()
        dump.waitFor(DESTROY_SECONDS, TimeUnit.SECONDS)
        text.lineSequence()
            .filter { "probe-" in it || "indagium-ffmpeg-load" in it || "Class initialization" in it || "waiting to lock" in it }
            .joinToString("\n")
    }.getOrDefault("(jstack unavailable)")

    private fun File.readTextOrEmpty(): String = if (isFile) readText().takeLast(OUTPUT_TAIL_CHARS) else ""

    private companion object {
        const val ROUNDS = 8
        const val JITTER_MS = 150L
        const val TIMEOUT_SECONDS = 60L
        const val DESTROY_SECONDS = 10L
        const val OUTPUT_TAIL_CHARS = 4_000
    }
}
