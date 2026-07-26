package com.openlog.voice

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceModelInstallerTest {
    @Test
    fun installsOnlyWhenDownloadedFileMatchesPinnedChecksum() = runBlocking {
        val root = Files.createTempDirectory("openlog-voice-test").toFile()
        try {
            val bytes = "local whisper model".encodeToByteArray()
            val installer = VoiceModelInstaller(root, descriptor(bytes), VoiceModelDownloader { _, target, progress ->
                target.writeBytes(bytes)
                progress(bytes.size.toLong())
            })
            var progress = 0L

            val result = installer.install { progress = it }

            assertIs<VoiceModelInstallResult.Installed>(result)
            assertTrue(installer.modelFile.isFile)
            assertEquals(bytes.size.toLong(), progress)
            assertEquals(VoiceModelStatus.INSTALLED, installer.status())
            assertIs<VoiceModelInstallResult.AlreadyInstalled>(installer.install())
            assertTrue(installer.remove())
            assertEquals(VoiceModelStatus.MISSING_OR_INVALID, installer.status())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptDownloadsAndDeletesPartialFile() = runBlocking {
        val root = Files.createTempDirectory("openlog-voice-test").toFile()
        try {
            val expected = "expected model".encodeToByteArray()
            val installer = VoiceModelInstaller(root, descriptor(expected), VoiceModelDownloader { _, target, _ ->
                target.writeText("tampered")
            })

            val result = installer.install()

            assertIs<VoiceModelInstallResult.Failure>(result)
            assertFalse(installer.modelFile.exists())
            assertFalse(File(root, "ggml-base.bin.part").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun descriptor(bytes: ByteArray): VoiceModelDescriptor = VoiceModelDescriptor(
        id = "test",
        version = "1",
        fileName = "ggml-base.bin",
        downloadUrl = "https://example.com/ggml-base.bin",
        sha256 = sha256(bytes),
        sizeBytes = bytes.size.toLong(),
        licenseUrl = "https://example.com/license",
    )

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
