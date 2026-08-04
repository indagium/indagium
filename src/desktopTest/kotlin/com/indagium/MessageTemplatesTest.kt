package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.MessageCompositionState
import com.indagium.model.MessageTemplate
import com.indagium.model.TemplateGranularity
import com.indagium.ui.MessageRuleVariant
import com.indagium.ui.messageRuleVariantsForEntry
import com.indagium.ui.mkTab
import com.indagium.utils.MESSAGE_RULE_SEPARATORS
import com.indagium.utils.computeItems
import com.indagium.utils.computeMessageTemplates
import com.indagium.utils.computeStackTraceGroups
import com.indagium.utils.foldTemplates
import com.indagium.utils.invalidateComputeCache
import com.indagium.utils.maskMessage
import com.indagium.utils.mergeMessageTemplates
import com.indagium.utils.messageRuleSpecForTemplate
import com.indagium.utils.regexPatternForTemplate
import com.indagium.utils.truncateAtSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun entry(id: Int, tag: String, msg: String, level: LogLevel = LogLevel.I, pid: Int = 0) =
    LogEntry(id, "10:00:00.$id", level, tag, msg, pid = pid)

// Projection used to compare fold results against an independent reference implementation without
// dragging literalPrefixLength/literalPrefixRawLength (not part of the level-independence contract
// under test) into the comparison — matches the task's "assert on whole projected lists" style.
private data class Projected(val tag: String, val template: String, val count: Int, val firstEntryId: Int, val lastEntryId: Int)

private fun List<MessageTemplate>.projected() = map { Projected(it.tag, it.template, it.count, it.firstEntryId, it.lastEntryId) }

class MessageTemplatesTest {
    // ── Masking table ────────────────────────────────────────────────────────────────────────

    @Test
    fun maskingTableCoversEveryRule() {
        val cases = listOf(
            "addr 0x1A2b3C ready" to "addr <hex> ready",
            "id a1b2c3d4-e5f6-4890-abcd-ef1234567890 done" to "id <uuid> done",
            "token deadbeefcafe1234 seen" to "token <hex> seen",
            "id=42 ok" to "id=<n> ok",
            "version 1.5 stable" to "version <n> stable",
            "ip 1.2.3 subnet" to "ip <n> subnet",
            "name \"hello world\" set" to "name <str> set",
            "open /system/bin/app_process now" to "open <path> now",
            // Only one slash, so the path rule never starts here.
            "toggle on/off state" to "toggle on/off state",
            "fetch https://api.example.com/v2/users/12345 done" to "fetch https://api.example.com/v2/users/<n> done",
            "multiple   spaces   here" to "multiple spaces here",
            "plain message text" to "plain message text",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, maskMessage(raw).template, "masking '$raw'")
        }
    }

    @Test
    fun quotedStringLongerThan128CharsIsNotMasked() {
        val longContent = "x".repeat(129)
        val raw = "payload \"$longContent\" end"
        assertEquals(raw, maskMessage(raw).template)
    }

    // ── The digit-after-letter guard ─────────────────────────────────────────────────────────
    // Without checking the character immediately preceding a digit run, "Camera2"/"H264"/"sha256"/
    // "utf8"/"MD5" would each mask their trailing digits and merge genuinely distinct subsystems
    // into the same template.

    @Test
    fun identifiersWithTrailingDigitsAfterALetterSurviveUnmasked() {
        for (raw in listOf("Camera2", "H264", "sha256", "utf8", "MD5")) {
            val result = maskMessage(raw)
            assertEquals(raw, result.template, "'$raw' must survive unmasked")
            assertSame(raw, result.template, "'$raw' must be the same String instance (nothing masked)")
        }
    }

    @Test
    fun digitsNotPrecededByALetterStillMaskCorrectly() {
        assertEquals("id=<n>", maskMessage("id=42").template)
        assertEquals("pid <n>", maskMessage("pid 1234").template)
        assertEquals("stack_home_<n>", maskMessage("stack_home_2").template)
    }

    // ── Invariant: no mask token contains a separator character ────────────────────────────────

    @Test
    fun noMaskTokenContainsASeparatorCharacter() {
        val tokens = listOf(
            maskMessage("0x1a2b3c4d").template,
            maskMessage("a1b2c3d4-e5f6-4890-abcd-ef1234567890").template,
            maskMessage("deadbeefcafe1234").template,
            maskMessage("42").template,
            maskMessage("\"hi\"").template,
            maskMessage("/a/b/c").template,
        )
        for (token in tokens) {
            for (sep in MESSAGE_RULE_SEPARATORS) {
                assertFalse(sep in token, "token '$token' must not contain separator '$sep'")
            }
        }
    }

    // ── Allocation discipline ────────────────────────────────────────────────────────────────

    @Test
    fun sameStringInstanceIsReturnedWhenNothingIsMasked() {
        val raw = "Nothing to see here, move along"
        assertSame(raw, maskMessage(raw).template)
    }

    @Test
    fun aDifferentStringInstanceIsReturnedWhenSomethingIsMasked() {
        val raw = "value 42"
        assertNotSame(raw, maskMessage(raw).template)
    }

    // ── literalPrefixLength / literalPrefixRawLength ─────────────────────────────────────────

    @Test
    fun literalPrefixLengthWhenTheWholeMessageIsLiteral() {
        val raw = "Started service alpha"
        val result = maskMessage(raw)
        assertEquals(raw, result.template)
        assertEquals(raw.length, result.literalPrefixLength)
        assertEquals(raw.length, result.literalPrefixRawLength)
    }

    @Test
    fun literalPrefixLengthWhenTheMaskStartsAtIndexZero() {
        val raw = "42 things happened"
        val result = maskMessage(raw)
        assertEquals("<n> things happened", result.template)
        assertEquals(0, result.literalPrefixLength)
        assertEquals(0, result.literalPrefixRawLength)
    }

    @Test
    fun literalPrefixLengthWhenTheMaskStartsMidString() {
        val prefix = "value stack_home_"
        val raw = "${prefix}2 ready"
        val result = maskMessage(raw)
        assertEquals("$prefix<n> ready", result.template)
        assertEquals(prefix.length, result.literalPrefixLength)
        assertEquals(prefix.length, result.literalPrefixRawLength)
        assertTrue(result.literalPrefixLength in 1 until result.template.length)
    }

    // Whitespace collapse means the template's literal prefix cannot be recovered by searching the
    // raw message for it — literalPrefixRawLength must come from the scan, not from re-deriving an
    // offset afterwards.
    @Test
    fun literalPrefixRawLengthAccountsForWhitespaceCollapseUnlikeLiteralPrefixLength() {
        val raw = "Start   proc 42"
        val result = maskMessage(raw)
        assertEquals("Start proc <n>", result.template)
        assertEquals("Start proc ".length, result.literalPrefixLength)
        assertEquals("Start   proc ".length, result.literalPrefixRawLength)
        assertTrue(result.literalPrefixLength < result.literalPrefixRawLength)
        val templatePrefix = result.template.substring(0, result.literalPrefixLength)
        assertFalse(raw.startsWith(templatePrefix), "raw must NOT contains-match the collapsed template prefix")
    }

    // ── Ranking, bounds, tag separation ──────────────────────────────────────────────────────

    @Test
    fun ranksByCountDescendingThenFirstEntryIdAscendingWithCorrectBounds() {
        val entries = listOf(
            entry(1, "Net", "Connection lost"),
            entry(2, "Net", "Signal weak"),
            entry(3, "Net", "Connection lost"),
            entry(4, "Net", "Connection lost"),
            entry(5, "Net", "Signal weak"),
        )
        val histogram = computeMessageTemplates(entries)
        assertEquals(
            listOf(
                MessageTemplate("Net", "Connection lost", 3, 1, 4, "Connection lost".length, "Connection lost".length),
                MessageTemplate("Net", "Signal weak", 2, 2, 5, "Signal weak".length, "Signal weak".length),
            ),
            histogram.templates,
        )
        assertEquals(5, histogram.totalEntries)
        assertEquals(5, histogram.countedEntries)
        assertFalse(histogram.overflowed)
    }

    @Test
    fun sameMessageUnderTwoDifferentTagsStaysSeparate() {
        val entries = listOf(
            entry(1, "TagA", "Connection lost"),
            entry(2, "TagB", "Connection lost"),
        )
        val histogram = computeMessageTemplates(entries)
        assertEquals(2, histogram.templates.size)
        assertTrue(histogram.templates.any { it.tag == "TagA" && it.template == "Connection lost" && it.count == 1 })
        assertTrue(histogram.templates.any { it.tag == "TagB" && it.template == "Connection lost" && it.count == 1 })
    }

    // ── Stack-trace member exclusion ─────────────────────────────────────────────────────────

    @Test
    fun stackTraceMembersAreExcludedButTheTriggerLineIsKept() {
        val logs = listOf(
            entry(1, "AndroidRuntime", "FATAL EXCEPTION: main", level = LogLevel.E, pid = 100),
            entry(2, "AndroidRuntime", "java.lang.NullPointerException: boom", level = LogLevel.E, pid = 100),
            entry(3, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", level = LogLevel.E, pid = 100),
            entry(4, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", level = LogLevel.E, pid = 100),
        )
        val groups = computeStackTraceGroups(logs)
        assertEquals(listOf(2, 3, 4), groups.single().memberIds)

        val histogram = computeMessageTemplates(logs, groups)
        assertEquals(4, histogram.totalEntries)
        assertEquals(1, histogram.countedEntries)
        assertEquals(1, histogram.templates.size)
        val only = histogram.templates.single()
        assertEquals("AndroidRuntime", only.tag)
        assertEquals("FATAL EXCEPTION: main", only.template)
        assertEquals(1, only.firstEntryId)
        assertEquals(1, only.lastEntryId)
    }

    // ── Cap / overflow ────────────────────────────────────────────────────────────────────────

    @Test
    fun capReachedSetsOverflowedAndCountedEntriesExcludesDroppedLines() {
        val entries = listOf(
            // Keys A and B fill the cap of 2; gamma is a third distinct key and is dropped,
            // while the second alpha still increments the key that already exists.
            entry(1, "Svc", "Started service alpha"),
            entry(2, "Svc", "Started service beta"),
            entry(3, "Svc", "Started service gamma"),
            entry(4, "Svc", "Started service alpha"),
        )
        val histogram = computeMessageTemplates(entries, cap = 2)

        assertTrue(histogram.overflowed)
        assertEquals(4, histogram.totalEntries)
        assertEquals(3, histogram.countedEntries) // entry 3 dropped, the other three counted
        assertEquals(2, histogram.templates.size)
        assertEquals(2, histogram.templates.first { it.template == "Started service alpha" }.count)
        assertEquals(1, histogram.templates.first { it.template == "Started service beta" }.count)
    }

    // ── THE LOAD-BEARING TEST ─────────────────────────────────────────────────────────────────
    // This is the executable form of the level-independence constraint documented at the top of
    // utils/MessageTemplates.kt: masking runs once, at STRICT, and every coarser level is a FOLD
    // over that result, never a rescan. If a future change ever makes masking depend on the
    // granularity, this test — comparing foldTemplates() against a genuinely independent
    // direct-scan-at-that-level reference implementation — is the only thing that will catch it;
    // everything else (ranking, bounds, cap policy) would still look internally consistent.

    private fun referenceHistogramAtLevel(entries: List<LogEntry>, truncateCount: Int): List<Projected> {
        data class Acc(var count: Int, var first: Int, var last: Int)
        val byKey = LinkedHashMap<Pair<String, String>, Acc>()
        for (e in entries) {
            val maskedTemplate = maskMessage(e.msg).template
            val key = e.tag to truncateAtSeparator(maskedTemplate, truncateCount)
            val acc = byKey.getOrPut(key) { Acc(0, e.id, e.id) }
            acc.count++
            acc.first = minOf(acc.first, e.id)
            acc.last = maxOf(acc.last, e.id)
        }
        return byKey.entries
            .map { (key, acc) -> Projected(key.first, key.second, acc.count, acc.first, acc.last) }
            .sortedWith(compareByDescending<Projected> { it.count }.thenBy { it.firstEntryId })
    }

    @Test
    fun foldingTheStrictHistogramEqualsScanningDirectlyAtThatGranularity() {
        val entries = listOf(
            entry(1, "Card", "Card stack expanded: stackId=stack_home"),
            entry(2, "Card", "Card stack expanded: stackId=stack_work"),
            entry(3, "Card", "Card stack expanded: stackId=stack_settings"),
            entry(4, "Battery", "Battery level=low"),
            entry(5, "Battery", "Battery level=high"),
            entry(6, "Battery", "Battery level=low"),
            entry(7, "Boot", "System ready"),
        )
        val strict = computeMessageTemplates(entries)
        assertEquals(TemplateGranularity.STRICT, strict.granularity)
        // Sanity before any folding: 6 distinct (tag, template) keys — Card has three distinct
        // stackIds, Battery has two distinct levels (ids 4 and 6 are the same message and merge),
        // and Boot has one. Nothing here masks, so STRICT templates are the raw messages. The
        // fixture is deliberately shaped so the folds below actually collapse something: at
        // NORMAL the three Card rows merge, and at LOOSE the two Battery rows do too.
        assertEquals(6, strict.templates.size)

        val foldedNormal = foldTemplates(strict, TemplateGranularity.NORMAL).projected()
        val referenceNormal = referenceHistogramAtLevel(entries, 2)
        assertEquals(referenceNormal, foldedNormal)

        val foldedLoose = foldTemplates(strict, TemplateGranularity.LOOSE).projected()
        val referenceLoose = referenceHistogramAtLevel(entries, 1)
        assertEquals(referenceLoose, foldedLoose)
    }

    @Test
    fun foldTemplatesReturnsTheSameListInstanceWhenGranularityAlreadyMatches() {
        val entries = listOf(entry(1, "Card", "Card stack expanded: stackId=stack_home"))
        val strict = computeMessageTemplates(entries)
        assertSame(strict.templates, foldTemplates(strict, TemplateGranularity.STRICT))
    }

    // ── Incremental merge (tailing) ──────────────────────────────────────────────────────────

    @Test
    fun incrementalMergeOfTwoBatchesMatchesAFullComputeOverBoth() {
        val batchA = listOf(
            entry(1, "Net", "Connected to server1"),
            entry(2, "Net", "Connected to server2"),
            entry(3, "UI", "Rendered frame"),
        )
        val batchB = listOf(
            entry(4, "Net", "Connected to server1"),
            entry(5, "UI", "Rendered frame"),
            entry(6, "Net", "Disconnected"),
        )
        val merged = mergeMessageTemplates(computeMessageTemplates(batchA), computeMessageTemplates(batchB))
        val direct = computeMessageTemplates(batchA + batchB)

        assertEquals(direct.templates.toSet(), merged.templates.toSet())
        assertEquals(direct.totalEntries, merged.totalEntries)
        assertEquals(direct.countedEntries, merged.countedEntries)
        assertEquals(direct.overflowed, merged.overflowed)
    }

    // ── computeItems memo cache must not be affected ─────────────────────────────────────────
    // utils/Filter.kt's per-tab compute cache keys on the IDENTITY of logData/stackTraceGroups plus
    // Filter equality (utils/Filter.kt:317-323) — messageComposition lives on LogTab, not the memo
    // key or LogAnalysis, so mutating it must never trigger a different computation than the one
    // already cached. computeItems itself has no "nothing changed" identity fast path outside the
    // single-stack-toggle splice (that path always builds a fresh outer List), so the achievable
    // and correct assertion here is content equality: the two calls must produce the exact same
    // rows, proving the cached filtered/sequence work was reused rather than silently diverging
    // because of the messageComposition edit.
    @Test
    fun computeItemsIsNotInvalidatedByAMessageCompositionChange() {
        val tabId = "mt-cache-test"
        invalidateComputeCache(tabId)
        val entries = listOf(
            entry(1, "Tag", "hello one"),
            entry(2, "Tag", "hello two"),
        )
        val tab = mkTab(tabId, "f.log", entries)
        val first = computeItems(tab, applyFilter = true)

        val mutated = tab.copy(messageComposition = MessageCompositionState.Computed(computeMessageTemplates(entries), tab.filter))
        val second = computeItems(mutated, applyFilter = true)

        assertEquals(first, second)
    }

    // ── Promotion equivalence: messageRuleVariantsForEntry behaves identically after the move ──

    @Test
    fun messageRuleVariantsForEntryStillProducesFourVariantsForATwoSeparatorMessage() {
        val e = entry(1, "Card", "Card stack expanded: stackId=stack_home")
        val variants = messageRuleVariantsForEntry(e)
        assertEquals(
            listOf(
                MessageRuleVariant("Card: Card stack expanded", "Card stack expanded", "Card"),
                MessageRuleVariant("Card: Card stack expanded: stackId", "Card stack expanded: stackId", "Card"),
                MessageRuleVariant("Card stack expanded", "Card stack expanded", null),
                MessageRuleVariant("Card stack expanded: stackId", "Card stack expanded: stackId", null),
            ),
            variants,
        )
    }

    @Test
    fun messageRuleVariantsForEntryCollapsesToTwoVariantsWhenFewerThanTwoSeparatorsExist() {
        val e = entry(1, "Boot", "System ready")
        val variants = messageRuleVariantsForEntry(e)
        assertEquals(
            listOf(
                MessageRuleVariant("Boot: System ready", "System ready", "Boot"),
                MessageRuleVariant("System ready", "System ready", null),
            ),
            variants,
        )
    }

    @Test
    fun messageRuleVariantsForEntryPrefersSelectedTextWhenProvided() {
        val e = entry(1, "Card", "Card stack expanded: stackId=stack_home")
        val variants = messageRuleVariantsForEntry(e, selectedText = " stackId ")
        assertEquals(
            listOf(
                MessageRuleVariant("Card: stackId", "stackId", "Card"),
                MessageRuleVariant("stackId", "stackId", null),
            ),
            variants,
        )
    }

    // ── Row-action pattern mapping (Stage 2a's Hide/Show only/Highlight) ────────────────────────
    // Every produced rule must actually match the raw line(s) that produced the template — a
    // masked template is not itself a substring of any raw line, so this is the property that
    // matters, not merely which branch of the mapping fired.

    @Test
    fun aFullyLiteralTemplateProducesALiteralRuleOnTheWholeTemplate() {
        val raw = "Started service alpha"
        val template = computeMessageTemplates(listOf(entry(1, "Svc", raw))).templates.single()
        assertEquals(raw, template.template, "sanity: nothing should have masked here")

        val spec = messageRuleSpecForTemplate(template)

        assertFalse(spec.regex)
        assertEquals(raw, spec.pattern)
        assertTrue(raw.contains(spec.pattern))
    }

    // The case that forced this design. Sibling shapes routinely share everything before their
    // first masked field — every "Delivering touch to window Window{<n>…" variant begins
    // identically — so a rule built from that leading literal matches all of them. Pressing Hide on
    // a row counted 1x then acted on dozens of shapes, and every one of those rows honestly
    // reported itself applied. A row must act on the shape it displays, so a masked template
    // becomes a regex that matches its own lines and not its siblings'.
    @Test
    fun aMaskedTemplateProducesARuleThatMatchesItsOwnLinesButNotASiblingSharingItsPrefix() {
        val mine = "Delivering touch to window Window{2c07 u0 app}"
        val sibling = "Delivering touch to window Window{de5c u0 app} extra"
        val template = computeMessageTemplates(listOf(entry(1, "Input", mine))).templates.single()

        val spec = messageRuleSpecForTemplate(template)

        assertTrue(spec.regex, "a masked template must not settle for a shared literal prefix")
        val rule = Regex(spec.pattern)
        assertTrue(rule.containsMatchIn(mine), "the rule must match the line it was built from")
        assertFalse(rule.containsMatchIn(sibling), "it must NOT swallow a sibling shape sharing the prefix")
    }

    // Whitespace collapse means a rule can never be sliced out of the template text and expected to
    // match the raw line — the template says "Start proc", the line says "Start   proc".
    @Test
    fun aMaskedTemplateWhoseRawLineHadCollapsedWhitespaceStillMatchesThatRawLine() {
        val raw = "Start   proc 42"
        val template = computeMessageTemplates(listOf(entry(1, "Proc", raw))).templates.single()
        assertEquals("Start proc <n>", template.template)

        val spec = messageRuleSpecForTemplate(template)

        assertTrue(spec.regex)
        assertTrue(
            Regex(spec.pattern).containsMatchIn(raw),
            "the produced rule must match the raw line it came from, collapsed run and all",
        )
    }

    @Test
    fun aLeadingMaskProducesARegexRuleThatMatchesTheLinesItCameFrom() {
        val rawOne = "42 things happened"
        val rawTwo = "7 things happened"
        val template = computeMessageTemplates(listOf(entry(1, "Boot", rawOne), entry(2, "Boot", rawTwo))).templates.single()
        assertEquals("<n> things happened", template.template)
        assertEquals(0, template.literalPrefixLength, "sanity: the mask starts at index 0")

        val spec = messageRuleSpecForTemplate(template)

        assertTrue(spec.regex)
        val compiled = Regex(spec.pattern, RegexOption.IGNORE_CASE)
        assertTrue(compiled.containsMatchIn(rawOne))
        assertTrue(compiled.containsMatchIn(rawTwo))
    }

    @Test
    fun aShortLiteralPrefixBelowTheDiscriminatingThresholdAlsoFallsBackToRegex() {
        val raw = "id=42 ok"
        val template = computeMessageTemplates(listOf(entry(1, "Svc", raw))).templates.single()
        assertEquals("id=<n> ok", template.template)
        assertTrue(template.literalPrefixLength < 4, "sanity: 'id=' is a 3-character prefix")

        val spec = messageRuleSpecForTemplate(template)

        assertTrue(spec.regex)
        assertTrue(Regex(spec.pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw))
    }

    @Test
    fun regexPatternForTemplateEscapesRegexMetacharactersInLiteralRuns() {
        val raw = "cost: \$4.50 (approx.)"
        val pattern = regexPatternForTemplate(maskMessage(raw).template)
        assertTrue(Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(raw))
    }
}
