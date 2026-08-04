package com.indagium.update

import com.indagium.generated.BuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** GitHub repo backing the in-app "Check for updates" flow (releases API + asset downloads). */
const val UPDATE_REPO = "indagium/indagium"

data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)

/** [version] is [tag] with a leading `v`/`V` stripped; [body] is the release notes (Markdown, may be blank). */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val htmlUrl: String,
    val body: String,
    val assets: List<ReleaseAsset>,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(val release: ReleaseInfo) : UpdateCheckResult

    /** Covers every non-success case: HTTP failure, a private/renamed repo, or no network at all. */
    data class Unavailable(val reason: String) : UpdateCheckResult
}

/**
 * Native client for the in-app "Check for updates" flow. Mirrors
 * [com.indagium.ai.AnthropicMessagesProvider]'s transport shape — an injectable [HttpClient] (so
 * tests can substitute a MockEngine) and a contract that never throws for a failed check, only for
 * cancellation.
 */
class UpdateChecker(
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 0
        }
    },
) {
    suspend fun fetchLatest(currentVersion: String = BuildInfo.APP_VERSION): UpdateCheckResult = try {
        val response = httpClient.get("https://api.github.com/repos/$UPDATE_REPO/releases/latest") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) {
            UpdateCheckResult.Unavailable("GitHub release check failed (HTTP ${response.status.value}).")
        } else {
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            if (tag.isNullOrBlank()) {
                UpdateCheckResult.Unavailable("GitHub release response was missing a tag.")
            } else {
                val release = ReleaseInfo(
                    version = tag.removePrefix("v").removePrefix("V"),
                    tag = tag,
                    htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    assets = (root["assets"] as? JsonArray)?.mapNotNull { it.toReleaseAssetOrNull() }.orEmpty(),
                )
                if (compareVersions(currentVersion, release.version) >= 0) {
                    UpdateCheckResult.UpToDate
                } else {
                    UpdateCheckResult.Available(release)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        UpdateCheckResult.Unavailable("Unable to reach GitHub to check for updates.")
    }

    /**
     * Streams [asset] into [destination] (a full file path — the caller/user picks the exact name
     * via a native save dialog, which need not match [ReleaseAsset.name]), via a `.part` temp file
     * that's only moved into place once the whole download completes — mirroring
     * [com.indagium.utils.writeFileAtomically]'s temp-file-then-atomic-move shape (that helper is
     * text-Writer only, so this duplicates rather than reuses it). The temp file lives in
     * [destination]'s own parent directory (not a shared temp dir) so the final move stays on one
     * filesystem and [moveAtomicallyIfPossible]'s atomic-rename path actually applies.
     * [onProgress] is called after every chunk with the running byte count and the response's
     * Content-Length as the total, falling back to [ReleaseAsset.size] when that header is missing
     * or non-positive.
     */
    suspend fun downloadAsset(
        asset: ReleaseAsset,
        destination: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("Download destination has no parent directory: ${destination.absolutePath}")
        if (parent.exists() && !parent.isDirectory) {
            throw IOException("Download destination's parent is not a directory: ${parent.absolutePath}")
        }
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create download directory: ${parent.absolutePath}")
        }
        val tmp = File(parent, ".${destination.name}.part")
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var totalRead = 0L
        var moved = false
        try {
            httpClient.prepareGet(asset.downloadUrl).execute { response ->
                val total = response.contentLength()?.takeIf { it > 0 } ?: asset.size
                val channel = response.bodyAsChannel()
                tmp.outputStream().use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                        if (bytesRead == -1) break
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onProgress(totalRead, total)
                    }
                }
            }
            moveAtomicallyIfPossible(tmp, destination)
            moved = true
        } finally {
            if (!moved) tmp.delete()
        }
        return destination
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonElement.toReleaseAssetOrNull(): ReleaseAsset? {
    val obj = this as? JsonObject ?: return null
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
    val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
    return ReleaseAsset(name, url, size)
}

private fun moveAtomicallyIfPossible(tmp: File, destination: File) {
    try {
        Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Numeric, dot-segment comparison tolerant of a leading `v`/`V`, differing segment counts (missing
 * segments read as 0, so `1.4` == `1.4.0`), and non-numeric segments (read as 0). Returns
 * -1/0/1 the same way [Comparable.compareTo] does.
 */
fun compareVersions(current: String, latest: String): Int {
    val currentSegments = current.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
    val latestSegments = latest.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(currentSegments.size, latestSegments.size)) {
        val cmp = currentSegments.getOrElse(i) { 0 }.compareTo(latestSegments.getOrElse(i) { 0 })
        if (cmp != 0) return cmp
    }
    return 0
}

/**
 * Picks the packaged asset matching this JVM's OS: macOS -> first `.dmg`, Windows -> first `.msi`.
 * Neither of those carries an architecture in its filename (jpackage doesn't add one, and only one
 * build of each is ever published), so there's nothing to discriminate on and the first match wins.
 *
 * Linux is different: as of 1.7.0 a release ships one `.deb` per architecture (amd64 and arm64), so
 * "any `.deb`" is no longer a safe answer — it could hand an arm64 machine an amd64 package it can't
 * install. So for Linux we also require the asset name to carry an arch token matching [osArch]:
 * `aarch64`/`arm64` (some JVMs report one, some the other) select an asset whose name contains
 * `arm64` or `aarch64`; anything else is treated as x86-64 and must contain `amd64`, `x86_64`, or
 * `x64`. If no `.deb` carries the matching token — e.g. an arm64 machine checking against an older
 * release that only ever shipped an amd64 build — this returns null rather than silently falling
 * back to a mismatched package. That's a real, expected outcome: [com.indagium.ui.UpdateDialog]
 * already renders a "View on GitHub" link instead of a "Download" button when this returns null.
 */
fun assetForCurrentOs(
    assets: List<ReleaseAsset>,
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
): ReleaseAsset? {
    val lowerOsName = osName.lowercase()
    return when {
        lowerOsName.contains("mac") -> assets.firstOrNull { it.name.endsWith(".dmg") }
        lowerOsName.contains("win") -> assets.firstOrNull { it.name.endsWith(".msi") }
        else -> {
            val debs = assets.filter { it.name.endsWith(".deb") }
            val isArm = osArch.lowercase().trim() in setOf("aarch64", "arm64")
            val archTokens = if (isArm) listOf("arm64", "aarch64") else listOf("amd64", "x86_64", "x64")
            debs.firstOrNull { deb -> archTokens.any { token -> deb.name.lowercase().contains(token) } }
        }
    }
}
