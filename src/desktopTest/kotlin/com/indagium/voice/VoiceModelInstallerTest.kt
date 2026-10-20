package com.indagium.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceModelInstallerTest {
    @Test
    fun catalogKeepsExistingModelsAndAddsPinnedLargeModelsWithTheirCapabilities() {
        assertEquals(listOf("whisper-base", "whisper-small", "whisper-large-v3", "whisper-large-v3-turbo"), VoiceModelCatalog.all.map { it.id })
        assertEquals("60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", VoiceModelCatalog.base.sha256)
        assertEquals(147_951_465L, VoiceModelCatalog.base.sizeBytes)
        assertEquals("1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", VoiceModelCatalog.small.sha256)
        assertEquals(487_601_967L, VoiceModelCatalog.small.sizeBytes)
        assertEquals("64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2", VoiceModelCatalog.largeV3.sha256)
        assertEquals(3_095_033_483L, VoiceModelCatalog.largeV3.sizeBytes)
        assertEquals("1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", VoiceModelCatalog.largeV3Turbo.sha256)
        assertEquals(1_624_553_275L, VoiceModelCatalog.largeV3Turbo.sizeBytes)
        assertTrue(VoiceModelCatalog.largeV3Turbo.displayName.contains("Turbo"))
        assertTrue(VoiceModelCatalog.largeV3Turbo.description.isNotBlank())
        assertFalse(VoiceModelCatalog.largeV3Turbo.supportsTranslation)
        assertTrue(VoiceModelCatalog.largeV3.supportsTranslation)
        assertEquals(VoiceModelCatalog.base, VoiceModelCatalog.byId("unknown-model"))
    }

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

    @Test
    fun installedStatusIsSharedAndFileChangesAreReverifiedAsynchronously() = runBlocking {
        val root = Files.createTempDirectory("openlog-voice-cache-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bytes = "verified model".encodeToByteArray()
            val installer = VoiceModelInstaller(root, descriptor(bytes), VoiceModelDownloader { _, target, _ -> target.writeBytes(bytes) })
            val settingsInstaller = VoiceModelInstaller(root, descriptor(bytes))
            assertIs<VoiceModelInstallResult.Installed>(installer.install())
            assertEquals(VoiceModelCheckState.INSTALLED, settingsInstaller.cachedCheckState())

            settingsInstaller.modelFile.writeBytes("tampered model".encodeToByteArray())
            Files.setLastModifiedTime(settingsInstaller.modelFile.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 30_000L))
            assertEquals(VoiceModelCheckState.CHECKING, installer.cachedCheckState())
            val job = assertNotNull(installer.requestVerification(scope))
            job.join()

            assertEquals(VoiceModelCheckState.MISSING_OR_INVALID, settingsInstaller.checkState.value)
        } finally {
            scope.cancel()
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
