package com.openlog.update

import com.openlog.generated.BuildInfo
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** GitHub repo backing the in-app "Check for updates" flow (releases API + asset downloads). */
const val UPDATE_REPO = "rarnaut-dev/openLog2"

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
 * [com.openlog.ai.AnthropicMessagesProvider]'s transport shape — an injectable [HttpClient] (so
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
     * Streams [asset] into [destDir]/[ReleaseAsset.name], via a `.part` temp file that's only
     * moved into place once the whole download completes — mirroring
     * [com.openlog.utils.writeFileAtomically]'s temp-file-then-atomic-move shape (that helper is
     * text-Writer only, so this duplicates rather than reuses it). [onProgress] is called after
     * every chunk with the running byte count and the response's Content-Length as the total,
     * falling back to [ReleaseAsset.size] when that header is missing or non-positive.
     */
    suspend fun downloadAsset(
        asset: ReleaseAsset,
        destDir: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        destDir.mkdirs()
        val dest = File(destDir, asset.name)
        val tmp = File(destDir, ".${asset.name}.part")
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
            moveAtomicallyIfPossible(tmp, dest)
            moved = true
        } finally {
            if (!moved) tmp.delete()
        }
        return dest
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

/** Picks the packaged asset matching this JVM's OS: macOS -> .dmg, Windows -> .msi, else -> .deb. */
fun assetForCurrentOs(assets: List<ReleaseAsset>, osName: String = System.getProperty("os.name").orEmpty()): ReleaseAsset? {
    val lower = osName.lowercase()
    return when {
        lower.contains("mac") -> assets.firstOrNull { it.name.endsWith(".dmg") }
        lower.contains("win") -> assets.firstOrNull { it.name.endsWith(".msi") }
        else -> assets.firstOrNull { it.name.endsWith(".deb") }
    }
}
