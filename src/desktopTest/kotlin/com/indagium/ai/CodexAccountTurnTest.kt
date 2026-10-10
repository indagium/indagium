package com.indagium.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexAccountTurnTest {
    @Test
    fun imageDeliveryCallbackReflectsWhetherCodexCanActuallyAttachTheFiles() = runBlocking {
        val image = LlmImage("c2NyZWVu", "image/jpeg")
        val failedDirectory = Files.createTempFile("codex-image-target", ".file")
        val failedDelivery = mutableListOf<Boolean>()
        try {
            val fallback = codexTurnInput(
                AiRun(tabId = "delivery-test"),
                AccountRequest("prompt with images", listOf(image), "text-only fallback") { failedDelivery += it },
                "system",
                "preamble",
                failedDirectory,
            )
            assertEquals(listOf(false), failedDelivery)
            assertTrue(fallback.imagePaths.isEmpty())
            assertTrue(fallback.text.endsWith("text-only fallback"), fallback.text)
        } finally {
            Files.deleteIfExists(failedDirectory)
        }

        val successfulDirectory = Files.createTempDirectory("codex-image-success")
        val successfulDelivery = mutableListOf<Boolean>()
        try {
            val attached = codexTurnInput(
                AiRun(tabId = "delivery-test-success"),
                AccountRequest("prompt with images", listOf(image), "text-only fallback") { successfulDelivery += it },
                "system",
                "preamble",
                successfulDirectory,
            )
            assertEquals(listOf(true), successfulDelivery)
            assertEquals(1, attached.imagePaths.size)
            assertTrue(Files.exists(successfulDirectory.resolve("evidence-1.jpg")))
        } finally {
            successfulDirectory.toFile().deleteRecursively()
        }
    }
}
