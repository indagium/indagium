package com.indagium.proofread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnglishProofreaderTest {
    @Test
    fun packagedEnglishResourcesFindSpellingAndGrammarAtOriginalUnicodeMarkdownOffsets() {
        val checker = LanguageToolEnglishProofreader()
        assertNotNull(javaClass.getResource("/licenses/languagetool-LGPL-2.1.txt"))
        val source = "🙂 **This are a test** with a speling mistake. `spelingCode` /tmp/speling.log Привіт"
        val issues = checker.check(source, "en-US")

        assertTrue(issues.any { issue ->
            source.substring(issue.start, issue.end) == "speling" && "spelling" in issue.replacements
        }, "English spelling dictionary and replacement resources must be present: $issues")
        assertTrue(issues.any { issue ->
            source.substring(issue.start, issue.end).contains("are") && issue.ruleId != "MORFOLOGIK_RULE_EN_US"
        }, "local grammar rules must find subject/verb disagreement: $issues")
        issues.forEach { issue ->
            assertTrue(issue.start in source.indices && issue.end <= source.length && issue.end > issue.start)
            val excerpt = source.substring(issue.start, issue.end)
            assertFalse(excerpt.contains("spelingCode") || excerpt.contains("/tmp") || excerpt.contains("Привіт"))
        }
    }

    @Test
    fun respectsUsAndUkDictionaryVariants() {
        val checker = LanguageToolEnglishProofreader()
        val source = "The colour is beautiful."
        assertTrue(checker.check(source, "en-US").any {
            source.substring(it.start, it.end) == "colour" && "color" in it.replacements
        })
        assertFalse(checker.check(source, "en-GB").any { source.substring(it.start, it.end) == "colour" })
    }

    @Test
    fun engineIsLazyAndChecksAreSerializedOnOneWorker() = runBlocking {
        val created = AtomicInteger()
        val calls = AtomicInteger()
        val workerIds = mutableSetOf<Long>()
        EnglishProofreadingService {
            created.incrementAndGet()
            EnglishProofreader { _, _ ->
                calls.incrementAndGet()
                workerIds += Thread.currentThread().threadId()
                emptyList()
            }
        }.use { service ->
            assertEquals(0, created.get())
            assertTrue(service.check("```\ncode\n```", "en-US").isEmpty())
            assertEquals(0, created.get(), "protected text must not initialize the engine")
            (1..6).map { async(Dispatchers.Default) { service.check("English prose.", "en-US") } }.awaitAll()
            assertEquals(1, created.get())
            assertEquals(6, calls.get())
            assertEquals(1, workerIds.size)
        }
    }

    @Test
    fun cancellationStopsBeforeTheNextLongDocumentSection() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        EnglishProofreadingService {
            EnglishProofreader { _, _ ->
                calls.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                emptyList()
            }
        }.use { service ->
            val job = launch(Dispatchers.Default) { service.check("English prose. ".repeat(1_000), "en-US") }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                job.cancel()
            } finally {
                release.countDown()
            }
            job.cancelAndJoin()
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun serviceRebasesChunkIssuesAndRejectsRequestsAfterClose() = runBlocking<Unit> {
        val source = "```\n" + "code ".repeat(1_000) + "\n```\nA speling mistake."
        val service = EnglishProofreadingService {
            EnglishProofreader { text, _ ->
                val offset = text.indexOf("speling")
                if (offset < 0) emptyList()
                else listOf(ProofreadingIssue(offset, offset + "speling".length, "Spelling", listOf("spelling"), "test"))
            }
        }
        service.use {
            val issue = service.check(source, "en-US").single()
            assertEquals(source.indexOf("speling"), issue.start)
            assertEquals("speling", source.substring(issue.start, issue.end))
        }
        assertFailsWith<IllegalStateException> { service.check("English", "en-US") }
    }

    @Test
    fun replacementSpanningProtectedCodeIsDiscarded() = runBlocking {
        EnglishProofreadingService {
            EnglishProofreader { text, _ ->
                listOf(ProofreadingIssue(0, text.length, "Broad grammar match", listOf("replacement"), "broad"))
            }
        }.use { service ->
            assertTrue(service.check("English `preserveCode` prose.", "en-US").isEmpty())
        }
    }
}
