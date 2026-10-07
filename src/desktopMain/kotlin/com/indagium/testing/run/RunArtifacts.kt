package com.indagium.testing.run

import com.indagium.capture.readCaptureSessionDirectory
import com.indagium.testing.model.TestRun
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import java.io.File

private const val MAX_RUN_ARTIFACT_PATHS = 256

/** Resolves only existing files contained by a run directory, including protection from symlink escapes. */
internal fun resolveRunArtifact(runDir: File, relativePath: String): File? = runCatching {
    if (isUnsafeRunArtifactPath(relativePath)) return@runCatching null
    val root = runDir.canonicalFile
    val candidate = File(root, relativePath).canonicalFile
    candidate.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
}.getOrNull()

internal fun isUnsafeRunArtifactPath(relativePath: String): Boolean {
    if (relativePath.isBlank() || File(relativePath).isAbsolute) return true
    val portable = relativePath.replace('\\', '/')
    if (portable.startsWith("//")) return true
    if (Regex("^[A-Za-z]:.*").matches(portable)) return true
    return portable.split('/').any { it == ".." || ':' in it }
}

internal fun readBoundedRunArtifact(runDir: File, relativePath: String, maxBytes: Int): ByteArray? {
    val file = resolveRunArtifact(runDir, relativePath) ?: return null
    if (file.length() !in 1..maxBytes.toLong()) return null
    return runCatching {
        file.inputStream().use { input -> input.readNBytes(maxBytes + 1).takeIf { it.size in 1..maxBytes } }
    }.getOrNull()
}

/** Shared UI/MCP inventory of saved evidence. All returned paths are relative and resolve below [runDir]. */
internal fun availableRunArtifactPaths(run: TestRun, runDir: File): List<String> {
    val root = runCatching { runDir.canonicalFile }.getOrNull() ?: return emptyList()
    val candidates = linkedSetOf<String>()
    run.lanes.forEach laneLoop@{ lane ->
        candidates += listOfNotNull(lane.logPath, lane.transcriptPath, lane.toolActivityPath)
        candidates += lane.cases.flatMap { it.steps }.mapNotNull { it.screenshotPath }
        val captureRoot = File(root, "lanes/${lane.laneId}/capture")
        val containedCaptureRoot = runCatching { captureRoot.canonicalFile }.getOrNull() ?: return@laneLoop
        if (!containedCaptureRoot.path.startsWith(root.path + File.separator)) return@laneLoop
        containedCaptureRoot.listFiles()?.asSequence()?.filter { it.isDirectory }?.take(64)?.forEach sessionLoop@{ sessionDirectory ->
            val session = readCaptureSessionDirectory(sessionDirectory) ?: return@sessionLoop
            val relative = runCatching { session.videoFile.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: return@sessionLoop
            candidates += relative
        }
    }
    val judge = File(root, TEST_RUN_JUDGE_FILE_NAME)
    if (judge.isFile) candidates += TEST_RUN_JUDGE_FILE_NAME
    return candidates.asSequence()
        .filter { resolveRunArtifact(root, it) != null }
        .take(MAX_RUN_ARTIFACT_PATHS)
        .toList()
}
