package com.openlog.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max

const val SPLIT_PROMPT_BYTES: Long = 500L * 1024L * 1024L
const val DEFAULT_SPLIT_POSTFIX: String = "part"

fun requiresSplitPrompt(sizeBytes: Long, thresholdBytes: Long = SPLIT_PROMPT_BYTES): Boolean =
    sizeBytes >= thresholdBytes

fun suggestedSplitPartCount(sizeBytes: Long, thresholdBytes: Long = SPLIT_PROMPT_BYTES): Int {
    if (sizeBytes <= 0L) return 1
    return max(1, ((sizeBytes + thresholdBytes - 1) / thresholdBytes).toInt())
}

fun planSplitOutputs(
    sourceName: String,
    destinationDir: File,
    postfix: String,
    partCount: Int,
): List<File> {
    val cleanPostfix = postfix.trim().ifBlank { DEFAULT_SPLIT_POSTFIX }
    val count = partCount.coerceAtLeast(1)
    val stem = sourceName.substringBeforeLast('.', sourceName)
    val ext = sourceName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { sourceName.contains('.') }
        ?.let { ".$it" }
        .orEmpty()

    fun candidates(extraSuffix: String): List<File> = (1..count).map { idx ->
        File(destinationDir, "${stem}${extraSuffix}_${cleanPostfix}_$idx$ext")
    }

    var suffixIndex = 1
    var outputs = candidates("")
    while (outputs.any { it.exists() }) {
        suffixIndex += 1
        outputs = candidates("_$suffixIndex")
    }
    return outputs
}

fun splitStreamToFiles(
    input: InputStream,
    outputFiles: List<File>,
    sourceSizeBytes: Long,
): List<File> {
    require(outputFiles.isNotEmpty()) { "At least one output file is required" }
    outputFiles.forEach { file ->
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
    }
    val targetBytes = max(1L, (sourceSizeBytes + outputFiles.size - 1) / outputFiles.size)
    var outputIndex = 0
    var currentBytes = 0L

    fun writeLine(line: ByteArray) {
        if (
            currentBytes > 0L &&
            outputIndex < outputFiles.lastIndex &&
            currentBytes + line.size > targetBytes
        ) {
            outputIndex += 1
            currentBytes = 0L
        }
        outputFiles[outputIndex].appendBytes(line)
        currentBytes += line.size
    }

    input.use { stream ->
        val line = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b < 0) {
                if (line.size() > 0) writeLine(line.toByteArray())
                break
            }
            line.write(b)
            if (b == '\n'.code) {
                writeLine(line.toByteArray())
                line.reset()
            }
        }
    }
    return outputFiles
}

fun splitFileToFiles(source: File, outputFiles: List<File>): List<File> =
    splitStreamToFiles(source.inputStream().buffered(), outputFiles, source.length())
