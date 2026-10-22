package com.indagium

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.indagium.model.ThemePreset
import com.indagium.ui.ANNOTATION_MARKDOWN_CACHE
import com.indagium.ui.AnnotationMarkdownText
import com.indagium.ui.ParsedMarkdownCache
import com.indagium.ui.themeColors
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A tab switch disposes the notes panel, so every visit composes note text from scratch. The
 * markdown must already be laid out on that very first frame; an async parse renders zero-height
 * text first and the following note cards then glide down when the real height arrives.
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationMarkdownFirstFrameUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun noteTextHasRealHeightOnFirstCompositionFrame() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AnnotationMarkdownText("Device attached", themeColors(ThemePreset.DRACULA))
        }

        rule.onNodeWithText("Device attached").assertHeightIsAtLeast(1.dp)
    }

    @Test
    fun revisitingUnchangedNoteTextRendersFromTheParseCacheWithTheSameFirstFrameHeight() {
        val text = "Cached revisit ${System.nanoTime()}"
        var visible by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            if (visible) AnnotationMarkdownText(text, themeColors(ThemePreset.DRACULA))
        }
        rule.onNodeWithText(text).assertHeightIsAtLeast(1.dp)
        val missesAfterFirstVisit = ANNOTATION_MARKDOWN_CACHE.misses
        val hitsAfterFirstVisit = ANNOTATION_MARKDOWN_CACHE.hits

        // A tab switch away and back disposes and recomposes the panel from scratch.
        rule.runOnIdle { visible = false }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { visible = true }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText(text).assertHeightIsAtLeast(1.dp)
        assertEquals(missesAfterFirstVisit, ANNOTATION_MARKDOWN_CACHE.misses, "an unchanged note must not be parsed again")
        assertEquals(hitsAfterFirstVisit + 1, ANNOTATION_MARKDOWN_CACHE.hits)
    }

    @Test
    fun parsedMarkdownCacheIsBoundedLeastRecentlyUsedAndSkipsOversizedSources() {
        val cache = ParsedMarkdownCache(maxEntries = 2, maxSourceChars = 10)
        val parser = MarkdownParser(GFMFlavourDescriptor())

        fun parsed(source: String) = State.Success(
            parser.buildMarkdownTreeFromString(source), source, linksLookedUp = true, referenceLinkHandler = ReferenceLinkHandlerImpl(),
        )
        val a = parsed("a")
        cache.put("a", a)
        cache.put("b", parsed("b"))
        assertSame(a, cache.get("a"), "a hit returns the cached parse itself")
        cache.put("c", parsed("c"))
        assertNull(cache.get("b"), "the least recently used entry is evicted")
        assertSame(a, cache.get("a"))

        cache.put("0123456789X", parsed("0123456789X"))
        assertNull(cache.get("0123456789X"), "sources above the size cap are never kept")
        cache.put("err", State.Error(IllegalStateException("x")))
        assertNull(cache.get("err"), "a failed parse is not cached")
        assertEquals(2, cache.size)
    }
}
