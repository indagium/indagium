package com.indagium.proofread

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.languagetool.JLanguageTool
import org.languagetool.Languages
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** UTF-16 offsets refer to the original Markdown, matching Compose text selections. */
data class ProofreadingIssue(
    val start: Int,
    val end: Int,
    val message: String,
    val replacements: List<String>,
    val ruleId: String,
)

fun interface EnglishProofreader {
    fun check(text: String, language: String): List<ProofreadingIssue>
}

/** Local rules and dictionaries only. Instances must be confined to one worker (JLanguageTool is not thread safe). */
class LanguageToolEnglishProofreader : EnglishProofreader {
    private val engines = mutableMapOf<String, JLanguageTool>()
    private val explanationMarkup = Regex("<[^>]+>")

    override fun check(text: String, language: String): List<ProofreadingIssue> {
        val variant = if (language == "en-GB") "en-GB" else "en-US"
        val engine = engines.getOrPut(variant) {
            JLanguageTool(Languages.getLanguageForShortCode(variant))
        }
        return ProofreadingText.chunks(text).flatMap { chunk ->
            engine.check(chunk.text).mapNotNull { match ->
                val start = chunk.offset + match.fromPos
                val end = chunk.offset + match.toPos
                if (start < 0 || end > text.length || start >= end ||
                    !ProofreadingText.isProseRange(text, chunk, match.fromPos, match.toPos)
                ) null
                else ProofreadingIssue(
                    start,
                    end,
                    match.message.replace(explanationMarkup, ""),
                    match.suggestedReplacements.toList(),
                    match.rule.id,
                )
            }
        }
    }
}

/** Lazy English engine, one worker, and cancellation between bounded prose sections. No audio or note text is persisted here. */
class EnglishProofreadingService(
    private val proofreaderFactory: () -> EnglishProofreader = { LanguageToolEnglishProofreader() },
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "indagium-english-proofreader").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var proofreader: EnglishProofreader? = null

    suspend fun check(text: String, language: String): List<ProofreadingIssue> {
        check(!closed.get()) { "English proofreader is closed" }
        return withContext(dispatcher) {
            coroutineContext.ensureActive()
            val chunks = ProofreadingText.chunks(text)
            if (chunks.isEmpty()) return@withContext emptyList()
            val engine = proofreader ?: proofreaderFactory().also { proofreader = it }
            buildList {
                for (chunk in chunks) {
                    coroutineContext.ensureActive()
                    val issues = engine.check(chunk.text, language)
                    coroutineContext.ensureActive()
                    issues.filter {
                        it.start >= 0 && it.end <= chunk.text.length && it.start < it.end &&
                            ProofreadingText.isProseRange(text, chunk, it.start, it.end)
                    }
                        .forEach { add(it.copy(start = it.start + chunk.offset, end = it.end + chunk.offset)) }
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) dispatcher.close()
    }
}
