package com.indagium.testing.store

import com.indagium.testing.model.isSafeId
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

// Reference images of golden-screenshot examples. The suite file only stores a RELATIVE `assetPath`
// (see StepExample.GoldenScreenshot); the image itself lives in `<testing dir>/assets/<suiteId>/`.
// UI-free so the copy/resolve rules are testable without Compose.

const val TEST_ASSETS_DIR_NAME = "assets"
private const val BYTES_PER_MB = 1024L * 1024L
const val MAX_GOLDEN_IMAGE_BYTES = 10L * BYTES_PER_MB
private const val MAX_ASSET_FILE_NAME_CHARS = 100
private const val DEFAULT_ASSET_BASE_NAME = "screenshot"

val GOLDEN_IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp")

/** Reads at most [maxBytes]+1 bytes so changed-on-disk assets are bounded before allocation. */
fun readBoundedTestAsset(file: File, maxBytes: Long = MAX_GOLDEN_IMAGE_BYTES): ByteArray? {
    if (maxBytes < 0 || !file.isFile) return null
    return runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            bytes.takeIf { it.size.toLong() <= maxBytes }
        }
    }.getOrNull()
}

private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
private val ABSOLUTE_PATH_START = Regex("^([/\\\\]|[A-Za-z]:)")

/** The folder that holds [suiteId]'s reference images. Not created here. */
fun testAssetDir(rootDir: File, suiteId: String): File = File(File(rootDir, TEST_ASSETS_DIR_NAME), suiteId)

/** The image file [assetPath] names inside [suiteId]'s asset folder, or null when the path would leave that folder. */
@Suppress("ReturnCount") // Fail-closed path validation rejects each unsafe relative-path/symlink condition before resolving an asset.
fun resolveTestAsset(rootDir: File, suiteId: String, assetPath: String): File? {
    if (!isSafeId(suiteId) || assetPath.isBlank() || ABSOLUTE_PATH_START.containsMatchIn(assetPath)) return null
    val relative = runCatching { Path.of(assetPath) }.getOrNull() ?: return null
    if (relative.isAbsolute || relative.any { it.toString() == ".." || it.toString().isBlank() }) return null
    val assetsRootFile = File(rootDir, TEST_ASSETS_DIR_NAME)
    if (Files.isSymbolicLink(assetsRootFile.toPath())) return null
    val assetsRoot = assetsRootFile.canonicalFile
    val expectedDir = File(assetsRoot, suiteId).absoluteFile.toPath().normalize().toFile()
    val dir = testAssetDir(rootDir, suiteId)
    // Reject a suite directory symlink even when it points back inside assets: each suite owns a leaf folder.
    if (Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile != expectedDir) return null
    val normalized = expectedDir.toPath().resolve(relative).normalize()
    if (!normalized.startsWith(expectedDir.toPath())) return null
    var cursor = expectedDir.toPath()
    for (part in expectedDir.toPath().relativize(normalized)) {
        cursor = cursor.resolve(part)
        if (Files.isSymbolicLink(cursor)) return null
    }
    val file = normalized.toFile().canonicalFile
    return file.takeIf { it.toPath().startsWith(expectedDir.toPath()) && it.toPath().startsWith(assetsRoot.toPath()) }
}

private fun assetFileName(source: File, taken: (String) -> Boolean): String {
    val extension = source.extension.lowercase()
    val base = source.nameWithoutExtension.replace(UNSAFE_FILE_NAME_CHARS, "_").trim('_', '.').take(MAX_ASSET_FILE_NAME_CHARS)
        .ifBlank { DEFAULT_ASSET_BASE_NAME }
    var candidate = "$base.$extension"
    var counter = 2
    while (taken(candidate)) candidate = "$base-${counter++}.$extension"
    return candidate
}

/**
 * Copies [source] into [suiteId]'s asset folder under a safe, unused file name and returns that name,
 * which is the value to store as the example's `assetPath`. Only small png/jpg/webp images are accepted.
 */
fun importGoldenImage(rootDir: File, suiteId: String, source: File): StoreResult<String> {
    if (!isSafeId(suiteId)) return StoreResult.NotFound("suite", suiteId)
    return when {
        !source.isFile -> StoreResult.Invalid("${source.name} is not a file.")
        source.extension.lowercase() !in GOLDEN_IMAGE_EXTENSIONS ->
            StoreResult.Invalid("Choose a ${GOLDEN_IMAGE_EXTENSIONS.sorted().joinToString("/")} image.")
        source.length() > MAX_GOLDEN_IMAGE_BYTES -> StoreResult.Invalid("${source.name} is larger than ${MAX_GOLDEN_IMAGE_BYTES / BYTES_PER_MB} MB.")
        else -> copyIntoAssetDir(rootDir, suiteId, source, ::copyBoundedAsset)
    }
}

/** Injectable copy boundary keeps failure cleanup testable without changing the public import API. */
internal fun importGoldenImageWithCopier(
    rootDir: File,
    suiteId: String,
    source: File,
    copier: (File, Path) -> Unit,
): StoreResult<String> {
    if (!isSafeId(suiteId)) return StoreResult.NotFound("suite", suiteId)
    return when {
        !source.isFile -> StoreResult.Invalid("${source.name} is not a file.")
        source.extension.lowercase() !in GOLDEN_IMAGE_EXTENSIONS ->
            StoreResult.Invalid("Choose a ${GOLDEN_IMAGE_EXTENSIONS.sorted().joinToString("/")} image.")
        source.length() > MAX_GOLDEN_IMAGE_BYTES -> StoreResult.Invalid("${source.name} is larger than ${MAX_GOLDEN_IMAGE_BYTES / BYTES_PER_MB} MB.")
        else -> copyIntoAssetDir(rootDir, suiteId, source, copier)
    }
}

@Suppress("ReturnCount") // Import reports specific validation/copy failures and cleans only its own staging file.
private fun copyIntoAssetDir(rootDir: File, suiteId: String, source: File, copier: (File, Path) -> Unit): StoreResult<String> {
    val assetsRoot = File(rootDir, TEST_ASSETS_DIR_NAME)
    if (Files.isSymbolicLink(assetsRoot.toPath())) return StoreResult.Invalid("The golden image asset folder must not be a symbolic link.")
    val dir = testAssetDir(rootDir, suiteId)
    if (Files.isSymbolicLink(dir.toPath())) return StoreResult.Invalid("The suite's golden image folder must not be a symbolic link.")
    var stage: Path? = null
    return try {
        Files.createDirectories(dir.toPath())
        // Import into a private sibling first. A failed/partial copy is never published as an asset.
        val staged = Files.createTempFile(dir.toPath(), ".golden-${UUID.randomUUID()}-", ".tmp")
        stage = staged
        copier(source, staged)
        if (Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile != File(assetsRoot.canonicalFile, suiteId)) {
            return StoreResult.Invalid("The suite's golden image folder changed while importing.")
        }
        if (!Files.isRegularFile(staged) || Files.size(staged) > MAX_GOLDEN_IMAGE_BYTES) {
            return StoreResult.Invalid("${source.name} changed or exceeded the ${MAX_GOLDEN_IMAGE_BYTES / BYTES_PER_MB} MB image limit while copying.")
        }
        while (true) {
            val name = assetFileName(source) { File(dir, it).exists() }
            val target = File(dir, name).toPath()
            if (resolveTestAsset(rootDir, suiteId, name) == null) {
                return StoreResult.Invalid("The suite's golden image folder is outside the assets directory.")
            }
            try {
                Files.move(staged, target)
                stage = null
                return StoreResult.Ok(name)
            } catch (_: FileAlreadyExistsException) {
                // Another importer won this name; select a fresh leaf and retry.
            }
        }
        @Suppress("UNREACHABLE_CODE")
        StoreResult.Invalid("Could not choose a golden image name.")
    } catch (e: IOException) {
        StoreResult.Invalid("Could not copy ${source.name}: ${e.message}")
    } finally {
        stage?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

private fun copyBoundedAsset(source: File, destination: Path) {
    source.inputStream().use { input ->
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_GOLDEN_IMAGE_BYTES) throw IOException("Image exceeds the ${MAX_GOLDEN_IMAGE_BYTES / BYTES_PER_MB} MB limit.")
                output.write(buffer, 0, count)
            }
        }
    }
}
