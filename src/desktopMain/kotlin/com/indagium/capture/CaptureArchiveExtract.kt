package com.indagium.capture

import com.indagium.generated.BuildInfo
import com.indagium.utils.moveReplacingAtomically
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** What "Extract from this archive" copies out of an opened archive. */
enum class CaptureExtractKind { ALL_FILES, LOG_ONLY, VIDEO_ONLY }

/**
 * An opened archive's extraction directory, as the extractor needs it: [root] is the folder every descriptor asset path
 * resolves against, whose files are already decoded (zstd undone) and hash-verified by [CaptureArchiveReader]. [archive] is
 * what the user opened (ZIP/`.ind`, folder, or descriptor file): its name gives the default output name.
 */
internal data class CaptureExtractSource(
    val root: File,
    val descriptor: CaptureArchiveDescriptor,
    val archive: File,
)

/** One descriptor asset to copy, with the cache file it is read from and that file's size on disk. */
internal data class CaptureExtractItem(val asset: CaptureArchiveAsset, val file: File, val bytes: Long)

/**
 * What an extraction would write, taken from the files on disk (no archive scan): drives the popover's size line and the
 * free-space check. [fileCount] includes the descriptor and the courtesy note an [CaptureExtractKind.ALL_FILES] folder gets.
 */
internal data class CaptureExtractPlan(
    val kind: CaptureExtractKind,
    val items: List<CaptureExtractItem>,
    val logBytes: Long,
    val videoBytes: Long,
    val totalBytes: Long,
    val fileCount: Int,
)

data class CaptureExtractResult(val output: File, val fileCount: Int, val bytes: Long)

private const val EXTRACT_BUFFER_BYTES = 128 * 1024
private const val EXTRACT_SPACE_MARGIN_BYTES = 1L * 1024L * 1024L
private const val PERCENT = 100

/**
 * Copies an opened archive's contents out as plain files: the whole archive as a folder, only the log, or only the video.
 * UI-free and blocking: call it from an IO thread; interrupting that thread cancels it and leaves nothing behind.
 *
 * Nothing is decoded here. The extraction cache already holds the decoded bytes, so extracting is a copy that re-checks every
 * file's SHA-256 against the descriptor (a cache file changed since the archive was opened fails loudly and publishes nothing).
 * Output is staged next to the destination (same volume, so the final rename is one step) and removed on any failure.
 */
internal object CaptureArchiveExtractor {
    fun plan(source: CaptureExtractSource, kind: CaptureExtractKind): CaptureExtractPlan {
        val descriptor = source.descriptor
        val assets = when (kind) {
            CaptureExtractKind.ALL_FILES -> descriptor.assets()
            CaptureExtractKind.LOG_ONLY -> listOf(descriptor.log)
            CaptureExtractKind.VIDEO_ONLY -> listOf(requireNotNull(descriptor.video) { "This archive has no video" })
        }
        val items = assets.map { asset ->
            val file = resolveSafe(source.root, asset.path)
            require(!hasSymbolicLink(source.root, file)) { "Capture asset uses a symbolic link: ${asset.path}" }
            require(file.isFile) { "Capture asset is missing: ${asset.path}" }
            CaptureExtractItem(asset, file, file.length())
        }
        val total = items.fold(0L) { sum, item -> Math.addExact(sum, item.bytes) }
        return CaptureExtractPlan(
            kind = kind,
            items = items,
            logBytes = items.firstOrNull { it.asset == descriptor.log }?.bytes ?: 0L,
            videoBytes = items.firstOrNull { it.asset == descriptor.video }?.bytes ?: 0L,
            totalBytes = total,
            fileCount = items.size + if (kind == CaptureExtractKind.ALL_FILES) 2 else 0,
        )
    }

    /**
     * Writes [kind] to [destination]: a new folder for ALL_FILES (an existing one is refused, never merged or replaced),
     * a single file for the other two (an existing one is replaced atomically only when [overwrite]). [onProgress] gets
     * (bytes copied, bytes total) whenever the whole percent changes.
     */
    fun extract(
        source: CaptureExtractSource,
        kind: CaptureExtractKind,
        destination: File,
        overwrite: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): CaptureExtractResult {
        val plan = plan(source, kind)
        val parent = requireNotNull(destination.absoluteFile.parentFile) { "Destination has no parent folder" }
        check(parent.isDirectory || parent.mkdirs()) { "Destination folder could not be created: $parent" }
        val required = Math.addExact(plan.totalBytes, EXTRACT_SPACE_MARGIN_BYTES)
        require(parent.usableSpace >= required) {
            "Not enough free space to extract (need $required bytes, have ${parent.usableSpace} bytes)"
        }
        val progress = Progress(plan.totalBytes, onProgress)
        return if (kind == CaptureExtractKind.ALL_FILES) {
            extractFolder(source, plan, destination, parent, progress)
        } else {
            extractFile(plan.items.single(), destination, parent, overwrite, progress)
            CaptureExtractResult(destination, 1, plan.totalBytes)
        }
    }

    private fun extractFolder(
        source: CaptureExtractSource,
        plan: CaptureExtractPlan,
        destination: File,
        parent: File,
        progress: Progress,
    ): CaptureExtractResult {
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "A folder with this name already exists: ${destination.name}"
        }
        val staging = File(parent, ".${destination.name}.extracting-${UUID.randomUUID()}")
        var published = false
        try {
            check(staging.mkdir()) { "Cannot create extraction folder: $staging" }
            // The descriptor first: a legacy archive whose file changed since it was opened is refused before any copying.
            val descriptorText = descriptorText(source)
            plan.items.forEach { item ->
                val target = resolveSafe(staging, item.asset.path)
                check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create folder: ${target.parentFile}" }
                copyVerified(item, target, progress)
            }
            File(staging, CAPTURE_DESCRIPTOR_NAME).writeText(descriptorText, Charsets.UTF_8)
            File(staging, CAPTURED_WITH_INDAGIUM_NAME).writeText(extractedFolderBlurb(), Charsets.UTF_8)
            checkNotInterrupted()
            try {
                // A plain move (no REPLACE_EXISTING) refuses a destination that appeared meanwhile.
                Files.move(staging.toPath(), destination.toPath())
            } catch (_: FileAlreadyExistsException) {
                throw IllegalArgumentException("A folder with this name already exists: ${destination.name}")
            }
            published = true
        } finally {
            if (!published) staging.deleteRecursively()
        }
        return CaptureExtractResult(destination, plan.fileCount, plan.totalBytes)
    }

    private fun extractFile(item: CaptureExtractItem, destination: File, parent: File, overwrite: Boolean, progress: Progress) {
        require(!destination.isDirectory) { "A folder with this name already exists: ${destination.name}" }
        require(overwrite || !destination.exists()) { "A file with this name already exists: ${destination.name}" }
        val staging = File(parent, ".${destination.name}.extracting-${UUID.randomUUID()}")
        var published = false
        try {
            copyVerified(item, staging, progress)
            checkNotInterrupted()
            if (overwrite) {
                moveReplacingAtomically(staging.toPath(), destination.toPath())
            } else {
                try {
                    Files.move(staging.toPath(), destination.toPath())
                } catch (_: FileAlreadyExistsException) {
                    throw IllegalArgumentException("A file with this name already exists: ${destination.name}")
                }
            }
            published = true
        } finally {
            if (!published) staging.delete()
        }
    }

    /** v3+ descriptors are re-serialized by the exporter's own writer; a v1/v2 one keeps the nested shape it shipped in. */
    private fun descriptorText(source: CaptureExtractSource): String {
        val descriptor = source.descriptor
        if (descriptor.formatVersion >= FIRST_FLAT_DESCRIPTOR_VERSION) return descriptor.toJson()
        val original = CaptureArchiveReader.readDescriptorText(source.archive)
        require(parseDescriptor(original) == descriptor) { "The archive changed since it was opened; open it again to extract" }
        return original
    }

    /** Copies [item] to [target] while hashing it; a mismatch with the descriptor (a cache file changed since open) fails. */
    private fun copyVerified(item: CaptureExtractItem, target: File, progress: Progress) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(EXTRACT_BUFFER_BYTES)
        FileInputStream(item.file).use { input ->
            FileOutputStream(target).use { output ->
                while (true) {
                    checkNotInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    progress.add(count)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == item.asset.sha256) { "Capture asset checksum mismatch: ${item.asset.path}" }
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Extraction cancelled")
    }

    /** Throttles progress to whole-percent changes. */
    private class Progress(private val total: Long, private val onProgress: (Long, Long) -> Unit) {
        private var done = 0L
        private var lastPercent = -1

        fun add(count: Int) {
            done += count
            val percent = if (total <= 0L) PERCENT else (done * PERCENT / total).toInt().coerceIn(0, PERCENT)
            if (percent == lastPercent) return
            lastPercent = percent
            onProgress(done, total)
        }
    }
}

private const val FIRST_FLAT_DESCRIPTOR_VERSION = 3

/** The courtesy note of an extracted folder; unlike a ZIP's it has no zstd line (nothing in the folder is zstd any more). */
private fun extractedFolderBlurb(): String =
    "Captured with Indagium ${BuildInfo.APP_VERSION} — https://indagium.com\n" +
        "Open $CAPTURE_DESCRIPTOR_NAME in Indagium to see the synced log, video and markers together.\n" +
        "The files in this folder are plain (log, video, notes); any other tool can read them directly.\n"

private val WINDOWS_RESERVED_STEMS = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (index in 1..9) {
        add("COM$index")
        add("LPT$index")
    }
}

private const val MAX_EXTRACT_NAME_LENGTH = 180

/**
 * The folder or file name an extraction writes for the typed [input], or null when it is not a usable name on every
 * platform (separators, `.`/`..`, characters Windows forbids, reserved device names, a trailing dot). [suffix] is `.log` or
 * the video's own extension (empty for a folder); typing it again is not doubled.
 */
internal fun captureExtractTargetName(input: String, suffix: String): String? {
    val trimmed = input.trim()
    val stem = if (suffix.isNotEmpty() && trimmed.endsWith(suffix, ignoreCase = true)) trimmed.dropLast(suffix.length) else trimmed
    val name = stem.trim()
    if (name.isEmpty() || name == "." || name == ".." || name.length > MAX_EXTRACT_NAME_LENGTH) return null
    if (name.any { it.code < 32 || it in "<>:\"/\\|?*" }) return null
    if (name.endsWith('.') || name.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED_STEMS) return null
    return name + suffix
}
