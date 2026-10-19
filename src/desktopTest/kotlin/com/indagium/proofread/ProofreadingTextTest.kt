package com.indagium.proofread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProofreadingTextTest {
    @Test
    fun preservesOffsetsAndLineBreaksWhileExcludingMarkdownAndTechnicalText() {
        val source = """
            # This are prose
            **A speling mistake** and `spelingCode`.
            [A visible label](https://example.com/speling) and /Users/me/speling.txt
            https://example.com/speling user@example.com processName PID_TID com.example.App
            <!-- indagium:diagram3 v1 speling -->
            ```kotlin
            val speling = "bad grammer"
            ```
            Привіт помилка and useful English.
        """.trimIndent()

        val prepared = ProofreadingText.mask(source)

        assertEquals(source.length, prepared.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, prepared.indices.filter { prepared[it] == '\n' })
        listOf("This are prose", "A speling mistake", "A visible label", "useful English").forEach {
            assertTrue(prepared.contains(it), "prose must remain checkable: $it")
        }
        listOf("spelingCode", "example.com", "/Users", "processName", "PID_TID", "indagium", "val speling", "Привіт").forEach {
            assertFalse(prepared.contains(it), "technical content must be protected: $it")
        }
    }

    @Test
    fun handlesTildeFencesUnclosedFencesAndDoubleBackticks() {
        val source = "Text\n~~~java\nspeling\n~~~\nGood ``literal ` speling`` words\n```\nspeling"
        val prepared = ProofreadingText.mask(source)

        assertTrue(prepared.contains("Text"))
        assertTrue(prepared.contains("Good"))
        assertTrue(prepared.contains("words"))
        assertFalse(prepared.contains("speling"))
        assertFalse(prepared.contains("literal"))
        assertEquals(source.length, prepared.length)
    }

    @Test
    fun chunksAreBoundedAndKeepOriginalOffsetsAfterLargeCodeBlocks() {
        val prose = "This sentence is visible. ".repeat(400)
        val source = "```\n" + "speling ".repeat(1_000) + "\n```\n" + prose + "🙂 End."
        val prepared = ProofreadingText.mask(source)
        val chunks = ProofreadingText.chunks(source)

        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= ProofreadingText.MAX_CHUNK_LENGTH)
            assertEquals(prepared.substring(chunk.offset, chunk.offset + chunk.text.length), chunk.text)
            assertFalse(chunk.text.last().isHighSurrogate())
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.contains("speling"))
        }
        val found = chunks.first { it.text.contains("This sentence") }
        val offset = found.offset + found.text.indexOf("This sentence")
        assertEquals(source.indexOf("This sentence"), offset)
    }

    @Test
    fun excludesIndentedCodeAndNonLatinSupplementaryLettersWithoutChangingUtf16Length() {
        val source = "🙂 English\r\n    speling code\r\n\tmore code\r\n中文 Ελληνικά 𐐀𐐁\r\nNext prose"
        val prepared = ProofreadingText.mask(source)
        assertEquals(source.length, prepared.length)
        assertTrue(prepared.contains("🙂 English"))
        assertTrue(prepared.endsWith("Next prose"))
        assertFalse(prepared.contains("code"))
        assertFalse(prepared.contains("中文"))
        assertFalse(prepared.contains("𐐀"))
        assertEquals(source.count { it == '\r' }, prepared.count { it == '\r' })
    }
}
