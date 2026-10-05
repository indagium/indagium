package com.indagium.testing.store

import com.indagium.testing.model.isSafeId
import java.io.File
import java.io.IOException
import java.nio.file.Files

// Reference images of golden-screenshot examples. The suite file only stores a RELATIVE `assetPath`
// (see StepExample.GoldenScreenshot); the image itself lives in `<testing dir>/assets/<suiteId>/`.
// UI-free so the copy/resolve rules are testable without Compose.

const val TEST_ASSETS_DIR_NAME = "assets"
private const val BYTES_PER_MB = 1024L * 1024L
const val MAX_GOLDEN_IMAGE_BYTES = 10L * BYTES_PER_MB
private const val MAX_ASSET_FILE_NAME_CHARS = 100
private const val DEFAULT_ASSET_BASE_NAME = "screenshot"

val GOLDEN_IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp")

private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
private val ABSOLUTE_PATH_START = Regex("^([/\\\\]|[A-Za-z]:)")

/** The folder that holds [suiteId]'s reference images. Not created here. */
fun testAssetDir(rootDir: File, suiteId: String): File = File(File(rootDir, TEST_ASSETS_DIR_NAME), suiteId)

/** The image file [assetPath] names inside [suiteId]'s asset folder, or null when the path would leave that folder. */
fun resolveTestAsset(rootDir: File, suiteId: String, assetPath: String): File? {
    if (!isSafeId(suiteId) || assetPath.isBlank() || ABSOLUTE_PATH_START.containsMatchIn(assetPath)) return null
    val dir = testAssetDir(rootDir, suiteId).canonicalFile
    val file = File(dir, assetPath).canonicalFile
    return file.takeIf { it.parentFile == dir || it.path.startsWith(dir.path + File.separator) }
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
        else -> copyIntoAssetDir(rootDir, suiteId, source)
    }
}

private fun copyIntoAssetDir(rootDir: File, suiteId: String, source: File): StoreResult<String> {
    val dir = testAssetDir(rootDir, suiteId)
    return try {
        Files.createDirectories(dir.toPath())
        val name = assetFileName(source) { File(dir, it).exists() }
        Files.copy(source.toPath(), File(dir, name).toPath())
        StoreResult.Ok(name)
    } catch (e: IOException) {
        StoreResult.Invalid("Could not copy ${source.name}: ${e.message}")
    }
}
