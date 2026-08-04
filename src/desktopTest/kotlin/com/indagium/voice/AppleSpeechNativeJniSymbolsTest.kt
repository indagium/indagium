package com.indagium.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * The three JNI symbols in `native/macos/indagium_speech.m` are matched by string at runtime —
 * no compiler checks them. If the Kotlin package/class ever moves without updating the native
 * file in lockstep, `System.load()` still succeeds (the dylib is fine) and the
 * `UnsatisfiedLinkError` fires later, uncaught, on the first `nativeEnsureReady` call: macOS
 * dictation crashes instead of degrading to the friendly "unavailable" message.
 *
 * This test derives the expected symbol prefix from the class itself, so it stays correct
 * through future renames instead of hardcoding "com_indagium".
 */
class AppleSpeechNativeJniSymbolsTest {
    @Test
    fun nativeFileExportsSymbolsMatchingTheCurrentPackage() {
        val expectedPrefix = "Java_" + AppleSpeechNative::class.java.name.replace('.', '_')
        val nativeSource = File("native/macos/indagium_speech.m").readText()

        listOf(
            "${expectedPrefix}_nativeEnsureReady",
            "${expectedPrefix}_nativeAvailabilityMessage",
            "${expectedPrefix}_nativeTranscribe",
        ).forEach { symbol -> assertContains(nativeSource, symbol) }
    }
}
