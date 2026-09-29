package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.ui.highlighterFromToken
import com.indagium.ui.highlighterToken
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HighlighterTokenTest {
    private fun b64(s: String) = if (s.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    // The encoder as it was before whole-line/scoped highlighters existed.
    private fun legacyToken(h: Highlighter) = listOf(
        h.id, h.pattern, h.regex.toString(), h.color.value.toString(), h.on.toString(),
    ).joinToString("|") { b64(it) }

    @Test
    fun anOldFiveFieldTokenDecodesToAllDefaults() {
        val old = Highlighter("h1", "Network", regex = false, color = Color.Yellow, on = true)
        val decoded = legacyToken(old).highlighterFromToken()
        assertEquals(old, decoded)
        assertFalse(decoded!!.wholeLine)
        assertEquals(HighlightTarget.ANY, decoded.target)
        assertNull(decoded.tag)
        assertNull(decoded.textColor)
    }

    @Test
    fun theFirstFiveFieldsAreByteIdenticalToTheOldEncoder() {
        val h = Highlighter("h1", "a|b,c", regex = true, color = Color.Cyan, on = false, wholeLine = true, tag = "T")
        val prefix = h.highlighterToken().split("|").take(5).joinToString("|")
        assertEquals(legacyToken(h), prefix)
    }

    @Test
    fun aFullyPopulatedHighlighterRoundTrips() {
        val h = Highlighter(
            id = "h2",
            pattern = "id=(\\d+)|x",
            regex = true,
            color = Color(0xFF112233),
            on = false,
            wholeLine = true,
            target = HighlightTarget.MESSAGE,
            tag = "com.app.Net",
            caseSensitive = true,
            textColor = Color(0xFFAABBCC),
            captureGroupsOnly = true,
            colorVariance = 25,
        )
        assertEquals(h, h.highlighterToken().highlighterFromToken())
    }

    @Test
    fun defaultsRoundTrip() {
        val h = Highlighter("h3", "plain", regex = false, color = Color.Red, on = true)
        assertEquals(h, h.highlighterToken().highlighterFromToken())
    }

    @Test
    fun unknownTargetFallsBackToAny() {
        val h = Highlighter("h4", "p", regex = false, color = Color.Red, on = true, target = HighlightTarget.TAG)
        val fields = h.highlighterToken().split("|").toMutableList()
        fields[6] = b64("FUTURE_TARGET")
        assertEquals(HighlightTarget.ANY, fields.joinToString("|").highlighterFromToken()?.target)
    }

    @Test
    fun aTooShortTokenIsRejected() {
        assertNull("aGk=|cA==".highlighterFromToken())
    }
}
