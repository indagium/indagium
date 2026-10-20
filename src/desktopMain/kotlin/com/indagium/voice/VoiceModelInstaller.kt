@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class VoiceModelDescriptor(
    val id: String,
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val licenseUrl: String,
    val displayName: String = id,
    val description: String = "",
    val supportsTranslation: Boolean = true,
)

object VoiceModelCatalog {
    /** Multilingual Whisper base, pinned to the current content hash rather than a mutable URL alone. */
    val base = VoiceModelDescriptor(
        id = "whisper-base",
        version = "2026-07-26",
        fileName = "ggml-base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        sizeBytes = 147_951_465L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        displayName = "Base",
        description = "Multilingual, fast, and compact. Best for clear speech and longer phrases.",
    )

    /** More accurate multilingual model, especially for short non-English dictation. */
    val small = VoiceModelDescriptor(
        id = "whisper-small",
        version = "2026-07-26",
        fileName = "ggml-small.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small.bin",
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        sizeBytes = 487_601_967L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        displayName = "Small",
        description = "Multilingual and more accurate on short or non-English phrases, with higher memory and CPU use.",
    )

    val largeV3 = VoiceModelDescriptor(
        id = "whisper-large-v3",
        version = "2026-07-26",
        fileName = "ggml-large-v3.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3.bin",
        sha256 = "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2",
        sizeBytes = 3_095_033_483L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        displayName = "Large v3",
        description = "Highest multilingual accuracy; needs about 3.1 GB of disk and substantially more memory and CPU/GPU time.",
    )

    val largeV3Turbo = VoiceModelDescriptor(
        id = "whisper-large-v3-turbo",
        version = "2026-07-26",
        fileName = "ggml-large-v3-turbo.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo.bin",
        sha256 = "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69",
        sizeBytes = 1_624_553_275L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        displayName = "Large v3 Turbo",
        description = "Large multilingual model optimized for faster decoding; uses more disk and memory than Small and does not support translation.",
        supportsTranslation = false,
    )

    val all = listOf(base, small, largeV3, largeV3Turbo)

    fun byId(id: String): VoiceModelDescriptor = all.firstOrNull { it.id == id } ?: base
}

fun interface VoiceModelDownloader {
    /** Writes [destination] and reports downloaded bytes. Implementations must not retain audio. */
    fun download(model: VoiceModelDescriptor, destination: File, onProgress: (downloadedBytes: Long) -> Unit)
}

object HttpsVoiceModelDownloader : VoiceModelDownloader {
    override fun download(model: VoiceModelDescriptor, destination: File, onProgress: (Long) -> Unit) {
        val source = URI(model.downloadUrl)
        require(source.scheme.equals("https", ignoreCase = true)) { "Voice model URLs must use HTTPS" }
        val connection = source.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.connect()
        try {
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "Voice model download redirected away from HTTPS" }
            check(connection.responseCode in 200..299) { "Voice model download returned HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

sealed interface VoiceModelInstallResult {
    data class Installed(val file: File) : VoiceModelInstallResult

    data class AlreadyInstalled(val file: File) : VoiceModelInstallResult

    data class Failure(val message: String, val cause: Throwable? = null) : VoiceModelInstallResult
}

enum class VoiceModelStatus {
    INSTALLED,
    MISSING_OR_INVALID,
}

enum class VoiceModelCheckState {
    CHECKING,
    INSTALLED,
    MISSING_OR_INVALID,
}

/** Verifies a downloaded model before atomically making it visible to the recognizer. */
class VoiceModelInstaller(
    private val modelDirectory: File,
    private val model: VoiceModelDescriptor = VoiceModelCatalog.base,
    private val downloader: VoiceModelDownloader = HttpsVoiceModelDownloader,
) {
    private data class FileSignature(val exists: Boolean, val size: Long, val modified: Long)

    val descriptor: VoiceModelDescriptor
        get() = model

    val modelFile: File
        get() = File(modelDirectory, model.fileName)

    private val statusEntry: SharedStatusEntry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val key = "${modelFile.absoluteFile.toPath().normalize()}|${model.sha256}|${model.sizeBytes}"
        sharedStatusEntries.computeIfAbsent(key) {
            SharedStatusEntry(if (modelFile.isFile) VoiceModelCheckState.CHECKING else VoiceModelCheckState.MISSING_OR_INVALID)
        }
    }

    val checkState: StateFlow<VoiceModelCheckState>
        get() = statusEntry.state.asStateFlow()

    fun isInstalled(): Boolean = isExpectedModel(modelFile).also { publish(it) }

    fun status(): VoiceModelStatus = if (isInstalled()) VoiceModelStatus.INSTALLED else VoiceModelStatus.MISSING_OR_INVALID

    /** Cheap microphone-path status; a changed file becomes Checking until background verification finishes. */
    fun cachedCheckState(): VoiceModelCheckState {
        val signature = fileSignature()
        synchronized(statusEntry.lock) {
            if (!signature.exists) {
                statusEntry.verifiedSignature = signature
                statusEntry.state.value = VoiceModelCheckState.MISSING_OR_INVALID
            } else if (signature != statusEntry.verifiedSignature) {
                statusEntry.state.value = VoiceModelCheckState.CHECKING
            }
            return statusEntry.state.value
        }
    }

    /** Checks large models away from the UI/microphone path, then publishes the result for this file revision. */
    fun requestVerification(scope: CoroutineScope): Job? {
        if (cachedCheckState() != VoiceModelCheckState.CHECKING) return null
        synchronized(statusEntry.lock) {
            if (statusEntry.verificationJob?.isActive == true) return statusEntry.verificationJob
            val signature = fileSignature()
            val verificationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var valid: Boolean? = null
                try {
                    valid = isExpectedModel(modelFile)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Leave this signature unverified so a later request can retry transient IO failures.
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    val currentSignature = runCatching(::fileSignature).getOrNull()
                    synchronized(statusEntry.lock) {
                        if (statusEntry.verificationJob === completedJob) {
                            if (valid != null && currentSignature == signature) {
                                statusEntry.verifiedSignature = signature
                                statusEntry.state.value = if (valid == true) VoiceModelCheckState.INSTALLED else VoiceModelCheckState.MISSING_OR_INVALID
                            } else {
                                statusEntry.verifiedSignature = null
                                statusEntry.state.value = VoiceModelCheckState.CHECKING
                            }
                            statusEntry.verificationJob = null
                        }
                    }
                }
            }
            statusEntry.verificationJob = verificationJob
            verificationJob.start()
            return verificationJob
        }
    }

    @Synchronized
    fun installBlocking(onProgress: (downloadedBytes: Long) -> Unit = {}): VoiceModelInstallResult {
        val destination = modelFile
        val partial = File(modelDirectory, "${model.fileName}.part")
        return try {
            require(modelDirectory.exists() || modelDirectory.mkdirs()) { "Could not create the local voice-model folder" }
            require(modelDirectory.isDirectory) { "Local voice-model path is not a folder" }
            require(destination.canonicalFile.parentFile == modelDirectory.canonicalFile) { "Unsafe voice model file path" }
            if (isExpectedModel(destination)) {
                publish(true)
                return VoiceModelInstallResult.AlreadyInstalled(destination)
            }
            partial.delete()
            downloader.download(model, partial, onProgress)
            require(isExpectedModel(partial)) { "Downloaded voice model did not pass integrity verification" }
            try {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            publish(true)
            VoiceModelInstallResult.Installed(destination)
        } catch (error: Exception) {
            partial.delete()
            publish(false)
            VoiceModelInstallResult.Failure("Could not install the local voice model: ${error.message ?: "unknown error"}", error)
        }
    }

    /** Call from a coroutine scope; download and checksum verification remain local to this class. */
    suspend fun install(onProgress: (downloadedBytes: Long) -> Unit = {}): VoiceModelInstallResult = installBlocking(onProgress)

    @Synchronized
    fun remove(): Boolean {
        val file = modelFile
        val removed = file.isFile && file.delete()
        if (removed) publish(false)
        return removed
    }

    private fun fileSignature(): FileSignature {
        val file = modelFile
        return FileSignature(file.isFile, if (file.isFile) file.length() else 0L, if (file.isFile) file.lastModified() else 0L)
    }

    private fun publish(installed: Boolean) {
        synchronized(statusEntry.lock) {
            statusEntry.verifiedSignature = fileSignature()
            statusEntry.state.value = if (installed) VoiceModelCheckState.INSTALLED else VoiceModelCheckState.MISSING_OR_INVALID
        }
    }

    private fun isExpectedModel(file: File): Boolean {
        if (!file.isFile || file.length() != model.sizeBytes) return false
        return sha256(file).equals(model.sha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class SharedStatusEntry(initial: VoiceModelCheckState) {
        val lock = Any()
        var verifiedSignature: FileSignature? = null
        var verificationJob: Job? = null
        val state = MutableStateFlow(initial)
    }

    private companion object {
        val sharedStatusEntries = java.util.concurrent.ConcurrentHashMap<String, SharedStatusEntry>()
    }
}
