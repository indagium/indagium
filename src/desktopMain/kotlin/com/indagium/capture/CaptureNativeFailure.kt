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
        return "Bundled FFmpeg could not load: $detail. This Linux package needs glibc $required or newer; " +
            "Ubuntu 20.04 has glibc 2.31. Use Ubuntu 22.04 or newer with the AppImage/.deb, or use Flatpak " +
            "after installing org.freedesktop.Platform//24.08."
    }
    val ubuntuHint = if (osName.contains("linux", ignoreCase = true)) {
        ". On Ubuntu 20.04, use Flatpak with org.freedesktop.Platform//24.08 for embedded capture."
    } else {
        ""
    }
    return "Bundled native media library could not load: $detail$ubuntuHint"
}

internal fun hasNativeLinkageFailure(failure: Throwable): Boolean =
    throwableCauseChain(failure).any { it is LinkageError }

private fun throwableCauseChain(failure: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val result = mutableListOf<Throwable>()
    var current: Throwable? = failure
    while (current != null && seen.add(current) && result.size < MAX_NATIVE_FAILURE_CAUSES) {
        result += current
        current = current.cause
    }
    return result
}

private const val MAX_NATIVE_FAILURE_CAUSES = 6
private const val MAX_NATIVE_FAILURE_DIAGNOSTIC_CHARS = 2_000
private val GLIBC_SYMBOL_REGEX = Regex("\\bGLIBC_(\\d+\\.\\d+)\\b")
