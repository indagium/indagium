package com.indagium.capture

import java.util.Collections
import java.util.IdentityHashMap

/** Keeps the first JavaCPP/JNI loader cause visible instead of reporting only a cached
 * `NoClassDefFoundError: Could not initialize class ...` on every reconnect. */
internal fun captureNativeFailureDiagnostic(failure: Throwable, osName: String = System.getProperty("os.name")): String {
    val causes = throwableCauseChain(failure)
    val detail = causes.joinToString("; caused by ") { cause ->
        val name = cause::class.simpleName ?: "Error"
        val message = cause.message?.replace(Regex("\\s+"), " ")?.trim()
        if (message.isNullOrEmpty()) name else "$name: $message"
    }.take(MAX_NATIVE_FAILURE_DIAGNOSTIC_CHARS)
    val glibcRequirement = GLIBC_SYMBOL_REGEX.findAll(detail)
        .mapNotNull { it.groupValues[1].split('.').mapNotNull(String::toIntOrNull).takeIf { parts -> parts.size == 2 } }
        .maxWithOrNull(compareBy<List<Int>> { it[0] }.thenBy { it[1] })
    if (osName.contains("linux", ignoreCase = true) && glibcRequirement != null) {
        val required = "${glibcRequirement[0]}.${glibcRequirement[1]}"
        return "Bundled FFmpeg could not load: $detail. This Linux package needs glibc $required or newer " +
            "for in-app video (in-app mirror, video recording, playback, export) — Ubuntu 22.04 or newer has " +
            "it, Ubuntu 20.04 (glibc 2.31) does not. Capturing logs and the scrcpy window still work here."
    }
    val ubuntuHint = if (osName.contains("linux", ignoreCase = true)) {
        ". On an older Linux distribution (e.g. Ubuntu 20.04) this is usually the glibc 2.35 requirement " +
            "for in-app video; Ubuntu 22.04 or newer resolves it. Capturing logs and the scrcpy window " +
            "still work here."
    } else {
        ""
    }
    return "Bundled native media library could not load: $detail$ubuntuHint"
}

internal fun hasNativeLinkageFailure(failure: Throwable): Boolean =
    throwableCauseChain(failure).any { it is LinkageError }

/**
 * Walks both `cause` and `suppressed` — JavaCPP often keeps the actually informative
 * `UnsatisfiedLinkError` ("... version `GLIBC_2.34' not found") in a suppressed exception or
 * deeper in the chain rather than as the direct cause, so a `cause`-only walk (the original
 * implementation) could miss it and leave only a generic "no jniavutil in java.library.path".
 * Depth-first through each throwable's own cause chain before its suppressed exceptions, so the
 * primary chain still reads first in the joined diagnostic; identity-deduplicated (a suppressed
 * exception can legitimately equal something already seen via `cause`) and bounded by the same
 * [MAX_NATIVE_FAILURE_CAUSES] cap regardless of which edge contributed each throwable.
 */
private fun throwableCauseChain(failure: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val result = mutableListOf<Throwable>()

    fun visit(candidate: Throwable?) {
        if (candidate == null || result.size >= MAX_NATIVE_FAILURE_CAUSES || !seen.add(candidate)) return
        result += candidate
        visit(candidate.cause)
        for (suppressed in candidate.suppressed) {
            if (result.size >= MAX_NATIVE_FAILURE_CAUSES) break
            visit(suppressed)
        }
    }
    visit(failure)
    return result
}

private const val MAX_NATIVE_FAILURE_CAUSES = 6
private const val MAX_NATIVE_FAILURE_DIAGNOSTIC_CHARS = 2_000
private val GLIBC_SYMBOL_REGEX = Regex("\\bGLIBC_(\\d+\\.\\d+)\\b")
