package com.indagium.testing.run

import com.indagium.testing.model.TestRun
import com.indagium.testing.store.encodeRunFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class TestRunReportFormat { JSON, MARKDOWN, EVIDENCE_ZIP }

data class TestRunReportExportProgress(val percent: Int, val current: String)

data class TestRunReportExportResult(val file: File, val bytes: Long, val evidenceFiles: Int)

/** Shared UI/MCP writer for local report files. Evidence paths are always resolved below the run directory. */
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal suspend fun exportTestRunReport(
    run: TestRun,
    runDir: File,
    destination: File,
    format: TestRunReportFormat,
    evidencePaths: List<String> = emptyList(),
    overwrite: Boolean = false,
    progress: (TestRunReportExportProgress) -> Unit = {},
): Result<TestRunReportExportResult> = withContext(Dispatchers.IO) {
    var temp: File? = null
    try {
        require(destination.name.isNotBlank()) { "Choose a destination file." }
        require(!destination.exists() || overwrite) { "${destination.path} already exists; confirm overwrite before replacing it." }
        val parent = destination.absoluteFile.parentFile ?: error("The destination has no parent folder.")
        require(parent.exists() || parent.mkdirs()) { "Could not create the destination folder." }
        require(run.lanes.size <= MAX_REPORT_LANES) { "A report can include at most $MAX_REPORT_LANES lanes." }
        val activityFiles = run.lanes.mapNotNull { lane ->
            val path = lane.toolActivityPath ?: return@mapNotNull null
            val file = resolveRunArtifact(runDir, path) ?: return@mapNotNull null
            require(file.length() <= MAX_LANE_ACTIVITY_BYTES) { "Lane activity is too large to export: ${lane.laneId}" }
            lane.laneId to file
        }
        val activityBytes = activityFiles.sumOf { it.second.length() }
        require(activityBytes <= MAX_REPORT_ACTIVITY_BYTES) { "Lane activity exceeds the 32 MB report limit." }
        temp = File.createTempFile(".test-run-export-", ".tmp", parent)
        val needsMarkdown = format != TestRunReportFormat.JSON
        val needsJson = format != TestRunReportFormat.MARKDOWN
        val activity = activityFiles.associate { (laneId, file) -> laneId to readFullActivity(file) }
        val markdownBytes = if (needsMarkdown) buildString {
            append(run.toMarkdown())
            activity.forEach { (laneId, text) ->
                if (!text.isNullOrBlank()) {
                    append("\n### Complete lane activity — ").append(laneId).append("\n\n```jsonl\n")
                    append(text.trimEnd()).append("\n```\n")
                }
            }
        }.toByteArray(Charsets.UTF_8) else ByteArray(0)
        val jsonBytes = if (needsJson) {
            val baseJson = Json.parseToJsonElement(encodeRunFile(run)).jsonObject
            val activityJson = buildMap {
                activity.forEach { (laneId, text) ->
                    val rows = mutableListOf<JsonElement>()
                    text.lineSequence().forEach { line ->
                        currentCoroutineContext().ensureActive()
                        if (line.isNotBlank()) runCatching { Json.parseToJsonElement(line) }.getOrNull()?.let(rows::add)
                    }
                    put(laneId, JsonArray(rows))
                }
            }
            Json { prettyPrint = true }.encodeToString(
                JsonElement.serializer(),
                JsonObject(baseJson + ("laneActivity" to JsonObject(activityJson))),
            ).toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        var copied = 0L
        val chosen = if (format == TestRunReportFormat.EVIDENCE_ZIP) evidencePaths.distinct() else emptyList()
        val files = chosen.map { relative ->
            val file = resolveRunArtifact(runDir, relative) ?: error("Evidence path is missing or outside this run: $relative")
            require(file.length() <= MAX_EVIDENCE_FILE_BYTES) { "Evidence file is too large to export: $relative" }
            relative to file
        }
        val total = jsonBytes.size.toLong() + markdownBytes.size + files.sumOf { it.second.length() }
        require(total <= MAX_EVIDENCE_TOTAL_BYTES) { "The selected report and evidence exceed the 512 MB export limit." }
        when (format) {
            TestRunReportFormat.JSON -> {
                temp.writeBytes(jsonBytes)
                progress(TestRunReportExportProgress(100, "Report JSON"))
            }
            TestRunReportFormat.MARKDOWN -> {
                temp.writeBytes(markdownBytes)
                progress(TestRunReportExportProgress(100, "Report Markdown"))
            }
            TestRunReportFormat.EVIDENCE_ZIP -> ZipOutputStream(FileOutputStream(temp)).use { zip ->
                writeZipEntry(zip, "report.json", jsonBytes)
                copied += jsonBytes.size
                writeZipEntry(zip, "report.md", markdownBytes)
                copied += markdownBytes.size
                files.forEach { (relative, source) ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("evidence/${safeZipPath(relative)}"))
                    var fileBytes = 0L
                    source.inputStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            copied += read
                            require(fileBytes <= MAX_EVIDENCE_FILE_BYTES && copied <= MAX_EVIDENCE_TOTAL_BYTES) {
                                "Evidence changed while exporting and exceeded the size limit: $relative"
                            }
                            zip.write(buffer, 0, read)
                            progress(TestRunReportExportProgress(((copied * 100) / total.coerceAtLeast(1L)).coerceIn(0, 100).toInt(), relative))
                        }
                    }
                    zip.closeEntry()
                }
                progress(TestRunReportExportProgress(100, "Report and evidence"))
            }
        }
        currentCoroutineContext().ensureActive()
        if (overwrite) {
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            // Same-directory moves are atomic on supported local file systems; omitting REPLACE_EXISTING
            // also keeps a concurrently-created destination safe.
            Files.move(temp.toPath(), destination.toPath())
        }
        Result.success(TestRunReportExportResult(destination, destination.length(), files.size))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    } finally {
        temp?.delete()
    }
}

private fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(bytes)
    zip.closeEntry()
}

private suspend fun readFullActivity(file: File): String {
    require(file.length() <= MAX_LANE_ACTIVITY_BYTES) { "Lane activity is too large to export." }
    val output = ByteArrayOutputStream(file.length().toInt().coerceAtMost(MAX_LANE_ACTIVITY_BYTES.toInt()))
    file.inputStream().use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_LANE_ACTIVITY_BYTES) { "Lane activity changed while exporting and exceeded its limit." }
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun safeZipPath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!isUnsafeRunArtifactPath(path)) { "Unsafe evidence path: $path" }
    return normalized
}

private const val MAX_EVIDENCE_FILE_BYTES = 128L * 1024 * 1024
private const val MAX_EVIDENCE_TOTAL_BYTES = 512L * 1024 * 1024
private const val MAX_LANE_ACTIVITY_BYTES = 20L * 1024 * 1024
private const val MAX_REPORT_ACTIVITY_BYTES = 32L * 1024 * 1024
private const val MAX_REPORT_LANES = 64
private const val COPY_BUFFER_BYTES = 64 * 1024
