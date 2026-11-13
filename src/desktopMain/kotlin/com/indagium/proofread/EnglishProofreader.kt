package com.indagium.proofread

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.languagetool.JLanguageTool
import org.languagetool.Languages
import org.languagetool.rules.Rule
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Drives how a mark is drawn and labelled. In memory only: never persisted. */
enum class ProofreadingCategory(val label: String) {
    SPELLING("spelling"),
    GRAMMAR("grammar"),
    STYLE("style"),
    CAPITALIZATION("capitalization"),
}

/** UTF-16 offsets refer to the original Markdown, matching Compose text selections. */
data class ProofreadingIssue(
    val start: Int,
    val end: Int,
    val message: String,
    val replacements: List<String>,
    val ruleId: String,
    val category: ProofreadingCategory = ProofreadingCategory.SPELLING,
)

private val VOCABULARY_WORD = Regex("[\\p{L}\\p{M}]+(?:['’\\-‐‑][\\p{L}\\p{M}]+)*")

/** One normalized single-word key, or null for empty/multiword/punctuated input. */
internal fun normalizeProofreadingVocabularyWord(raw: String): String? {
    val word = raw.trim()
    if (word.isEmpty() || !VOCABULARY_WORD.matches(word)) return null
    return word.lowercase(Locale.ROOT)
}

/** Trims entries, rejects invalid values, and keeps the first spelling of each case-insensitive word. */
internal fun normalizeProofreadingVocabulary(words: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    return buildList {
        words.forEach { raw ->
            val trimmed = raw.trim()
            val key = normalizeProofreadingVocabularyWord(trimmed) ?: return@forEach
            if (seen.add(key)) add(trimmed)
        }
    }
}

/** Returns the spelling token, including a base word before a trailing possessive 's/’s suffix. */
internal fun proofreadingVocabularyWordForIssue(text: String, issue: ProofreadingIssue): String? {
    if (issue.category != ProofreadingCategory.SPELLING || issue.start < 0 || issue.end > text.length || issue.start >= issue.end) {
        return null
    }
    val word = text.substring(issue.start, issue.end)
    normalizeProofreadingVocabularyWord(word) ?: return null
    if (word != word.trim()) return null
    if (continuesProofreadingWordAt(text, issue.start - 1)) return null
    if (!continuesProofreadingWordAt(text, issue.end) || hasCompletePossessiveSuffixAt(text, issue.end)) return word
    return null
}

private fun isProofreadingCoreWordChar(char: Char?): Boolean = char != null && (char.isLetterOrDigit() || char.isMark())

private fun joinsProofreadingWordAt(text: String, index: Int): Boolean {
    val char = text.getOrNull(index) ?: return false
    return char in "'’\\-‐‑" && isProofreadingCoreWordChar(text.getOrNull(index - 1)) &&
        isProofreadingCoreWordChar(text.getOrNull(index + 1))
}

private fun continuesProofreadingWordAt(text: String, index: Int): Boolean =
    isProofreadingCoreWordChar(text.getOrNull(index)) || joinsProofreadingWordAt(text, index)

private fun hasCompletePossessiveSuffixAt(text: String, index: Int): Boolean {
    val apostrophe = text.getOrNull(index)
    return (apostrophe == '\'' || apostrophe == '’') && text.getOrNull(index + 1)?.lowercaseChar() == 's' &&
        !continuesProofreadingWordAt(text, index + 2)
}

/** True only for an exact spelling issue whose extracted token is a saved vocabulary word. */
internal fun isProofreadingVocabularyIssue(
    text: String,
    issue: ProofreadingIssue,
    vocabulary: Set<String>,
): Boolean {
    val word = proofreadingVocabularyWordForIssue(text, issue) ?: return false
    val key = normalizeProofreadingVocabularyWord(word) ?: return false
    return key in vocabulary
}

private fun Char.isMark(): Boolean = Character.getType(this) in setOf(
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
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
                    categoryOf(match.rule),
                )
            }
        }
    }
}

private val STYLE_CATEGORIES = setOf("STYLE", "REDUNDANCY", "COLLOQUIALISMS", "BARBARISM", "WIKIPEDIA", "GENDER_NEUTRALITY")

/** Maps a LanguageTool rule to the handful of kinds the editor distinguishes visually. */
internal fun categoryOf(rule: Rule): ProofreadingCategory {
    val category = rule.category?.id?.toString().orEmpty()
    return when {
        rule.isDictionaryBasedSpellingRule || category == "TYPOS" -> ProofreadingCategory.SPELLING
        category == "CASING" -> ProofreadingCategory.CAPITALIZATION
        category in STYLE_CATEGORIES -> ProofreadingCategory.STYLE
        else -> ProofreadingCategory.GRAMMAR
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

    suspend fun check(text: String, language: String, vocabulary: Set<String> = emptySet()): List<ProofreadingIssue> {
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
                            ProofreadingText.isProseRange(text, chunk, it.start, it.end) &&
                            !isProofreadingVocabularyIssue(chunk.text, it, vocabulary)
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
