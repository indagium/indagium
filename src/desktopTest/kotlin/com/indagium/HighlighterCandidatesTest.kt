package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.MessageTemplate
import com.indagium.ui.HighlightCandidate
import com.indagium.ui.HighlightCandidateGroup
import com.indagium.ui.HighlighterQuery
import com.indagium.ui.existingHighlighterFor
import com.indagium.ui.formatHighlightCount
import com.indagium.ui.highlightModeFor
import com.indagium.ui.highlighterBadges
import com.indagium.ui.highlighterCandidates
import com.indagium.ui.highlighterLabel
import com.indagium.ui.matchKey
import com.indagium.ui.parseHighlighterQuery
import com.indagium.ui.scopeChipFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlighterCandidatesTest {
    private val tagCounts = mapOf("NetTag" to 50, "NetCache" to 5, "com.app.net.Client" to 20, "Db" to 9)
    private val sortedTags = tagCounts.entries.sortedByDescending { it.value }.map { it.key }

    private fun template(tag: String, text: String, count: Int) = MessageTemplate(
        tag = tag,
        template = text,
        count = count,
        firstEntryId = 1,
        lastEntryId = count,
        literalPrefixLength = text.length,
        literalPrefixRawLength = text.length,
    )

    private val templates = listOf(
        template("NetTag", "Request done", 30),
        template("Db", "Request queued", 4),
        template("NetTag", "Retry scheduled", 3),
    )

    private fun candidates(query: HighlighterQuery, tpl: List<MessageTemplate> = templates) =
        highlighterCandidates(query, sortedTags, tagCounts, emptyMap(), mostUsedTagLimit = 5, templates = tpl)

    // ── parsing ──

    @Test
    fun plainTextParsesAsASubstringUnlessTheRegexPillIsOn() {
        assertEquals(HighlighterQuery(null, "timeout", false), parseHighlighterQuery("  timeout ", null, false))
        assertEquals(HighlighterQuery(null, "time.*out", true), parseHighlighterQuery("time.*out", null, true))
    }

    @Test
    fun slashWrappedTextIsARegexWithoutTheSlashes() {
        assertEquals(HighlighterQuery(null, "err\\d+", true), parseHighlighterQuery("/err\\d+/", null, false))
        assertEquals(HighlighterQuery(null, "err\\d+", true), parseHighlighterQuery("/err\\d+/i", null, false))
    }

    @Test
    fun tagPrefixScopesTheRestOfTheTextToThatTag() {
        assertEquals(HighlighterQuery("NetTag", "Request done", false), parseHighlighterQuery("tag:NetTag Request done", null, false))
        assertEquals(HighlighterQuery("NetTag", "boom.*", true), parseHighlighterQuery("TAG:NetTag /boom.*/", null, false))
        assertEquals(HighlighterQuery("NetTag", "", false), parseHighlighterQuery("tag:NetTag", null, false))
    }

    @Test
    fun aBareTagPrefixWithoutAnyNameIsJustText() {
        assertEquals(HighlighterQuery(null, "tag:", false), parseHighlighterQuery("tag:", null, false))
    }

    @Test
    fun aScopeChipAppliesButATypedTagPrefixWins() {
        assertEquals(HighlighterQuery("Db", "x", false), parseHighlighterQuery("x", "Db", false))
        assertEquals(HighlighterQuery("NetTag", "x", false), parseHighlighterQuery("tag:NetTag x", "Db", false))
    }

    // ── ranking ──

    @Test
    fun blankQueryHasNoCandidates() {
        assertTrue(candidates(parseHighlighterQuery("  ", null, false)).isEmpty())
    }

    @Test
    fun theTypedTextComesFirstThenTagsThenMessagesAndIsAnywhereMatchedText() {
        val result = candidates(parseHighlighterQuery("net", null, false))
        assertEquals(HighlightCandidateGroup.TEXT, result.first().group)
        val literal = result.first()
        assertEquals("net", literal.pattern)
        assertEquals(HighlightTarget.ANY, literal.target)
        assertNull(literal.tag)
        val groups = result.map { it.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups, "candidates are grouped TEXT, TAGS, MESSAGES in that order")
        assertTrue(HighlightCandidateGroup.TAGS in groups)
    }

    @Test
    fun exactTagCandidatesLimitTheHighlighterToThatTagAndCarryTheirCount() {
        val tag = candidates(parseHighlighterQuery("Db", null, false)).single { it.group == HighlightCandidateGroup.TAGS }
        assertEquals("Db", tag.label)
        assertEquals("Db", tag.pattern)
        assertEquals(HighlightTarget.TAG, tag.target)
        assertEquals("Db", tag.tag)
        assertEquals(9, tag.count)
        assertEquals("Db", scopeChipFor(tag))
    }

    @Test
    fun packagePrefixCandidatesMatchInsideTheTagColumnWithoutAnExactTagLimit() {
        val pkg = candidates(parseHighlighterQuery("com.app", null, false)).first { it.isPackage }
        assertEquals("com.app.*", pkg.label)
        assertEquals("com.app", pkg.pattern)
        assertEquals(HighlightTarget.TAG, pkg.target)
        assertNull(pkg.tag)
        assertEquals(20, pkg.count)
        assertNull(scopeChipFor(pkg), "a package prefix is not an exact tag, so Tab must not turn it into a chip")
    }

    @Test
    fun messageCandidatesAreMessageScopedToTheirTemplateTag() {
        val result = candidates(parseHighlighterQuery("request", null, false))
        val messages = result.filter { it.group == HighlightCandidateGroup.MESSAGES }
        assertEquals(listOf("Request done", "Request queued"), messages.map { it.label })
        val first = messages.first()
        assertEquals(HighlightTarget.MESSAGE, first.target)
        assertEquals("NetTag", first.tag)
        assertEquals("Request done", first.pattern)
        assertFalse(first.regex)
        assertEquals(30, first.count)
        assertNull(scopeChipFor(first))
    }

    @Test
    fun aTagScopeOffersOnlyThatTagsMessagesAndNoOtherTags() {
        val result = candidates(parseHighlighterQuery("tag:NetTag re", null, false))
        assertEquals(listOf(HighlightCandidateGroup.TEXT, HighlightCandidateGroup.MESSAGES, HighlightCandidateGroup.MESSAGES), result.map { it.group })
        val literal = result.first()
        assertEquals(HighlightTarget.MESSAGE, literal.target)
        assertEquals("NetTag", literal.tag)
        assertEquals("re", literal.pattern)
        assertTrue(result.drop(1).all { it.tag == "NetTag" })
    }

    @Test
    fun aBareTagScopeIsJustThatTag() {
        val result = candidates(parseHighlighterQuery("tag:NetTag", null, false))
        val only = result.single()
        assertEquals(HighlightCandidateGroup.TAGS, only.group)
        assertEquals(HighlightTarget.TAG, only.target)
        assertEquals("NetTag", only.tag)
        assertEquals(50, only.count)
    }

    @Test
    fun aRegexQueryOffersOnlyTheRegexItself() {
        val only = candidates(parseHighlighterQuery("/req.*/", null, false)).single()
        assertEquals(HighlightCandidateGroup.TEXT, only.group)
        assertTrue(only.regex)
        assertEquals("req.*", only.pattern)
        assertEquals("/req.*/", only.label)
    }

    // ── Match is the default ──

    @Test
    fun matchTextIsTheDefaultModeForEveryCandidate() {
        assertFalse(highlightModeFor(rowAction = null, defaultWholeLine = false))
        assertFalse(highlightModeFor(rowAction = 0, defaultWholeLine = false))
        assertTrue(highlightModeFor(rowAction = 1, defaultWholeLine = false))
        // Only the user's explicit Whole line pick changes what an untouched row does.
        assertTrue(highlightModeFor(rowAction = null, defaultWholeLine = true))
        assertFalse(highlightModeFor(rowAction = 0, defaultWholeLine = true))
    }

    // ── existing highlighters ──

    private fun hl(pattern: String, target: HighlightTarget = HighlightTarget.ANY, tag: String? = null, regex: Boolean = false) =
        Highlighter("h-$pattern", pattern, regex, Color.Yellow, true, target = target, tag = tag)

    @Test
    fun aCandidateFindsTheHighlighterThatAlreadyHasItsShape() {
        val tag = candidates(parseHighlighterQuery("Db", null, false)).single { it.group == HighlightCandidateGroup.TAGS }
        val same = hl("Db", HighlightTarget.TAG, "Db")
        assertEquals(same, existingHighlighterFor(listOf(hl("other"), same), tag))
        assertNull(existingHighlighterFor(listOf(hl("Db")), tag), "an anywhere highlighter is not a tag highlighter")
        assertNull(existingHighlighterFor(listOf(hl("Db", HighlightTarget.TAG, "Other")), tag))
    }

    @Test
    fun blankAndNullTagsCountAsTheSame() {
        val literal = HighlightCandidate(HighlightCandidateGroup.TEXT, "x", "x", false, HighlightTarget.ANY, tag = null)
        val existing = Highlighter("i", "x", false, Color.Red, true, tag = "  ")
        assertEquals(existing, existingHighlighterFor(listOf(existing), literal))
    }

    // ── row description ──

    @Test
    fun theLabelShowsSlashesAndIOnlyForCaseInsensitiveRegexes() {
        assertEquals("/a.b/i", highlighterLabel(Highlighter("1", "a.b", true, Color.Red, true)).body)
        assertEquals("/a.b/", highlighterLabel(Highlighter("1", "a.b", true, Color.Red, true, caseSensitive = true)).body)
        assertEquals("plain", highlighterLabel(Highlighter("1", "plain", false, Color.Red, true)).body)
        assertEquals("plain", highlighterLabel(Highlighter("1", "plain", false, Color.Red, true, caseSensitive = true)).body)
    }

    @Test
    fun aTagLimitShowsAsADimmedPrefixExceptWhenItIsThePattern() {
        val scoped = Highlighter("1", "boom", false, Color.Red, true, target = HighlightTarget.MESSAGE, tag = "Net")
        assertEquals("tag:Net ", highlighterLabel(scoped).scopePrefix)
        val tagHl = Highlighter("2", "Net", false, Color.Red, true, target = HighlightTarget.TAG, tag = "Net")
        assertNull(highlighterLabel(tagHl).scopePrefix)
        assertNull(highlighterLabel(Highlighter("3", "x", false, Color.Red, true)).scopePrefix)
    }

    @Test
    fun badgesFollowCaseTargetAndKloggStyle() {
        assertTrue(highlighterBadges(Highlighter("1", "x", false, Color.Red, true)).isEmpty())
        assertEquals(
            listOf("Aa", "msg"),
            highlighterBadges(Highlighter("1", "x", false, Color.Red, true, target = HighlightTarget.MESSAGE, caseSensitive = true)),
        )
        assertEquals(listOf("tag"), highlighterBadges(Highlighter("1", "x", false, Color.Red, true, target = HighlightTarget.TAG)))
        assertEquals(listOf("klogg"), highlighterBadges(Highlighter("1", "x", false, Color.Red, true, textColor = Color.Black)))
    }

    @Test
    fun theMatchKeyIgnoresColourOnOffAndWholeLine() {
        val base = Highlighter("1", "x", false, Color.Red, true)
        val cosmetic = base.copy(color = Color.Blue, on = false, wholeLine = true, textColor = Color.Black)
        assertEquals(base.matchKey(), cosmetic.matchKey())
        assertTrue(base.matchKey() != base.copy(pattern = "y").matchKey())
        assertTrue(base.matchKey() != base.copy(caseSensitive = true).matchKey())
        assertTrue(base.matchKey() != base.copy(target = HighlightTarget.TAG).matchKey())
        assertTrue(base.matchKey() != base.copy(tag = "T").matchKey())
    }

    @Test
    fun cappedCountsAreShownAsALowerBound() {
        assertEquals("12", formatHighlightCount(12, capped = false))
        assertEquals("≥12", formatHighlightCount(12, capped = true))
    }
}
