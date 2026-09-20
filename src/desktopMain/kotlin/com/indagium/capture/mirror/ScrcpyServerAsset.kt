package com.indagium.capture.mirror

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** The only server binary that the embedded transport will accept. */
internal data class ScrcpyServerDescriptor(
    val version: String,
    val resourcePath: String,
    val sha256: String,
) {
    init {
        require(version.isNotBlank()) { "scrcpy server version cannot be blank" }
        require(resourcePath.isNotBlank()) { "scrcpy server resource path cannot be blank" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "scrcpy server checksum must be a 64-character SHA-256 digest"
        }
    }
}

internal object PinnedScrcpyServer {
    // Keep this in lockstep with the checked-in properties file. The release checksum is from
    // Genymobile's signed SHA256SUMS.txt; the binary itself is intentionally not checked in.
    val descriptor = ScrcpyServerDescriptor(
        version = "4.1",
        resourcePath = "scrcpy/scrcpy-server-v4.1",
        sha256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae",
    )
}

internal data class ScrcpyServerAsset(
    val descriptor: ScrcpyServerDescriptor,
    val bytes: ByteArray,
) {
    init {
        require(bytes.isNotEmpty()) { "scrcpy server asset is empty" }
        require(sha256(bytes) == descriptor.sha256.lowercase()) {
            "scrcpy server asset checksum does not match pinned ${descriptor.version} checksum"
        }
    }

    /** Writes a verified copy. The caller owns the destination and may delete it after use. */
    fun materialize(destination: File): File {
        destination.parentFile?.mkdirs()
        // Sync the same stream that receives the payload. Re-opening with outputStream() after
        // writeBytes() truncates the file before syncing, producing a zero-byte classpath jar.
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        return destination
    }
}

internal class ScrcpyServerAssetUnavailable(message: String) : IllegalStateException(message)

/** Loads only bundled resources; this class never downloads or falls back to an installed file. */
internal class ScrcpyServerAssetResolver(
    private val resourceLoader: (String) -> InputStream? = { path ->
        ScrcpyServerAssetResolver::class.java.classLoader?.getResourceAsStream(path)
    },
    private val descriptor: ScrcpyServerDescriptor = PinnedScrcpyServer.descriptor,
) {
    fun resolve(): ScrcpyServerAsset {
        val stream = resourceLoader(descriptor.resourcePath)
            ?: throw unavailable("bundled scrcpy-server ${descriptor.version} is not present")
        val bytes = stream.use { it.readBytes() }
        return try {
            ScrcpyServerAsset(descriptor, bytes)
        } catch (failure: IllegalArgumentException) {
            throw unavailable("bundled scrcpy-server ${descriptor.version} failed checksum validation", failure)
        }
    }

    fun availability(): Result<ScrcpyServerAsset> = runCatching { resolve() }

    private fun unavailable(reason: String, cause: Throwable? = null): ScrcpyServerAssetUnavailable =
        ScrcpyServerAssetUnavailable(
            "$reason. Add the official release asset at ${descriptor.resourcePath}; " +
                "do not download it at runtime (expected SHA-256 ${descriptor.sha256}).",
        ).also { if (cause != null) it.initCause(cause) }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
