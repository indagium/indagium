package com.indagium.utils

import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.retrace.ProguardMapProducer
import com.android.tools.r8.retrace.ProguardMappingSupplier
import com.android.tools.r8.retrace.Retrace
import com.android.tools.r8.retrace.RetraceCommand
import java.nio.file.Files
import java.nio.file.Path

/** Result of a non-destructive retrace operation. */
sealed interface RetraceOutcome {
    data class Success(val text: String) : RetraceOutcome

    data class Failure(val message: String) : RetraceOutcome
}

/**
 * Thin adapter around the official R8 Retrace implementation.
 *
 * This class deliberately contains no mapping parser. R8 mapping files are versioned and can
 * include metadata, source-file information, inlining, and ambiguous frames; the official API is
 * the compatibility boundary for all of those cases. The input list and loaded log are never
 * changed by this service.
 */
class RetraceService {
    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 300
    }

    fun retrace(mappingPath: String, stackTraceLines: List<String>): RetraceOutcome =
        retrace(Path.of(mappingPath), stackTraceLines)

    @Suppress("TooGenericExceptionCaught")
    fun retrace(mappingPath: Path, stackTraceLines: List<String>): RetraceOutcome {
        if (stackTraceLines.isEmpty()) return RetraceOutcome.Failure("There is no stack trace to retrace.")
        if (!Files.isRegularFile(mappingPath)) {
            return RetraceOutcome.Failure("The selected mapping file is missing. Choose mapping.txt again.")
        }
        if (!Files.isReadable(mappingPath)) {
            return RetraceOutcome.Failure("The selected mapping file cannot be read. Choose a readable mapping.txt.")
        }

        val diagnostics = CollectingDiagnostics()
        val output = arrayListOf<String>()
        return try {
            val mappingSupplier = ProguardMappingSupplier.builder()
                .setProguardMapProducer(ProguardMapProducer.fromPath(mappingPath))
                .setLoadAllDefinitions(true)
                .build()
            val command = RetraceCommand.builder(diagnostics)
                .setMappingSupplier(mappingSupplier)
                .setStackTrace(stackTraceLines.toList())
                .setRetracedStackTraceConsumer { lines -> output.addAll(lines) }
                .build()
            Retrace.run(command)
            if (diagnostics.errorMessage != null) {
                RetraceOutcome.Failure("Could not retrace the stack trace: ${safeDiagnostic(diagnostics.errorMessage!!)}")
            } else {
                RetraceOutcome.Success(output.joinToString("\n"))
            }
        } catch (error: RuntimeException) {
            val detail = diagnostics.errorMessage?.let(::safeDiagnostic)
                ?: error.message?.let(::safeDiagnostic)?.takeIf { it.isNotBlank() }
            RetraceOutcome.Failure(
                detail?.let { "Could not retrace the stack trace: $it" }
                    ?: "Could not retrace the stack trace. Check that the mapping file is valid.",
            )
        }
    }

    private fun safeDiagnostic(raw: String): String = raw.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
        ?.take(MAX_DIAGNOSTIC_CHARS)
        ?: "Check that the mapping file is valid."

    private class CollectingDiagnostics : DiagnosticsHandler {
        var errorMessage: String? = null

        override fun error(diagnostic: Diagnostic) {
            if (errorMessage == null) errorMessage = diagnostic.diagnosticMessage
        }
    }
}
