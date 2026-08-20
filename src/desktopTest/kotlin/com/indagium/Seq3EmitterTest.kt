package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.toPlantUml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emitter tests build a [Seq3Document] directly (not via `generateSeq3` — that's
 *  Seq3GeneratorTest's job) so escaping/aliasing/repeat rendering can be exercised in isolation,
 *  mirroring `DiagramEmitterTest`'s own approach for the v1/v2 emitters. */
class Seq3EmitterTest {
    private val a = Seq3Lifeline("A", "Lifeline A", setOf("A"), 0)
    private val b = Seq3Lifeline("B", "Lifeline B", setOf("B"), 1)

    private fun occurrence(id: Int, text: String, values: Map<String, String> = emptyMap()) =
        Seq3Occurrence(entryId = id, timestampMillis = 0L, rawTimestamp = "10:00:00.000", pid = 0, tid = 0, level = 'I', text = text, captureValues = values)

    private fun message(
        id: String = "m1",
        from: String = "A",
        to: String? = "B",
        label: String = "label",
        kind: Seq3Kind = Seq3Kind.CALL,
        repeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
        repeatThreshold: Int = 3,
        occurrences: List<Seq3Occurrence> = listOf(occurrence(1, label)),
        match: Seq3Match = Seq3Match(tag = from, template = label),
    ) = Seq3Message(
        id = id,
        match = match,
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = label,
        kind = kind,
        repeat = repeat,
        repeatThreshold = repeatThreshold,
        occurrences = occurrences,
    )

    private fun doc(messages: List<Seq3Message>, fragments: List<Seq3Fragment> = emptyList(), notes: List<Seq3Note> = emptyList()) =
        Seq3Document(title = "", lifelines = listOf(a, b), messages = messages, fragments = fragments, notes = notes)

    // ── Escaping ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun mermaidEscapesReservedCharactersAndTurnsNewlinesIntoBr() {
        val label = "weird: msg; with #hash <tag> \"quote\" `tick`\nsecond line"
        val out = doc(listOf(message(label = label))).toMermaid()

        assertTrue(out.contains("#58;"), "colon must be escaped; got:\n$out")
        assertTrue(out.contains("#59;"), "semicolon must be escaped")
        assertTrue(out.contains("#35;"), "hash must be escaped")
        assertTrue(out.contains("#60;"), "'<' must be escaped")
        assertTrue(out.contains("#62;"), "'>' must be escaped")
        assertTrue(out.contains("#34;"), "double quote must be escaped")
        assertTrue(out.contains("#96;"), "backtick must be escaped")
        assertTrue(out.contains("<br/>"), "embedded newline must become <br/>")
        assertFalse(out.contains("<tag>"), "the raw, unescaped angle-bracket text must not survive")
    }

    @Test
    fun plantUmlEscapesQuotesBackslashesAndNewlines() {
        val label = "path C:\\logs \"crash.txt\"\nsecond line"
        val out = doc(listOf(message(label = label))).toPlantUml()

        assertTrue(out.contains("\\\\logs"), "a literal backslash must be doubled; got:\n$out")
        assertTrue(out.contains("\\\"crash.txt\\\""), "double quotes must be backslash-escaped")
        assertTrue(out.contains("\\n"), "the embedded newline must become the literal two-char \\n token")
        assertFalse(out.contains("\nsecond line"), "no REAL newline should appear inside the message text")
    }

    // ── Aliasing / self-call ─────────────────────────────────────────────────────────────────

    @Test
    fun collidingLifelineIdsGetDedupedAliases() {
        val weird = Seq3Lifeline("Foo!", "Foo bang", setOf("Foo!"), 0)
        val alsoWeird = Seq3Lifeline("Foo?", "Foo question", setOf("Foo?"), 1)
        val out = Seq3Document(lifelines = listOf(weird, alsoWeird), messages = emptyList()).toMermaid()

        assertTrue(out.contains("participant Foo_ as"))
        assertTrue(out.contains("participant Foo__2 as"), "a colliding sanitized alias must get a numeric suffix; got:\n$out")
    }

    @Test
    fun selfCallDrawsFromAndToTheSameLifeline() {
        val out = doc(listOf(message(from = "A", to = "A", label = "recurse"))).toMermaid()
        assertTrue(out.contains("A->>A: recurse"), "got:\n$out")
    }

    // ── needs-target stub / note kind ────────────────────────────────────────────────────────

    @Test
    fun aNullTargetRendersAsANeedsTargetStubInBothDialects() {
        val message = message(to = null, label = "unresolved")

        assertTrue(doc(listOf(message)).toMermaid().contains("needs target"))
        assertTrue(doc(listOf(message)).toPlantUml().contains("needs target"))
    }

    @Test
    fun aNoteKindMessageRendersAsANoteNeverAnArrow() {
        val note = message(kind = Seq3Kind.NOTE, to = null, label = "heads up")
        val out = doc(listOf(note)).toMermaid()

        assertTrue(out.contains("Note over A: heads up"))
        assertFalse(out.contains("->>"), "a NOTE-kind message must never draw an arrow")
    }

    // ── Repeat modes ─────────────────────────────────────────────────────────────────────────

    @Test
    fun collapseAboveThresholdFoldsIntoOneBadgedArrow() {
        val occurrences = (1..5).map { occurrence(it, "ping") }
        val out = doc(listOf(message(repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3, occurrences = occurrences))).toMermaid()

        assertTrue(out.contains("×5"), "got:\n$out")
        assertEquals(1, Regex("->>").findAll(out).count(), "above the threshold, exactly one arrow must be drawn")
    }

    @Test
    fun collapseAtOrBelowThresholdDrawsEveryOccurrenceWithoutABadge() {
        val occurrences = (1..2).map { occurrence(it, "ping") }
        val out = doc(listOf(message(repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3, occurrences = occurrences))).toMermaid()

        assertFalse(out.contains("×"), "below the threshold there is no fold, so no badge; got:\n$out")
        assertEquals(2, Regex("->>").findAll(out).count())
    }

    @Test
    fun everyModeAlwaysDrawsOneArrowPerOccurrence() {
        val occurrences = (1..7).map { occurrence(it, "ping") }
        val out = doc(listOf(message(repeat = Seq3Repeat.EVERY, repeatThreshold = 1, occurrences = occurrences))).toMermaid()

        assertFalse(out.contains("×"), "EVERY never folds; got:\n$out")
        assertEquals(7, Regex("->>").findAll(out).count())
    }

    @Test
    fun firstAndLastModeDrawsTwoArrowsWithAnElisionMarkerBetween() {
        val withValues = (1..6).map { occurrence(it, "value={value}", mapOf("value" to it.toString())) }
        val match = Seq3Match(tag = "A", template = "value={value}", captures = listOf(Seq3Capture("value", Seq3CaptureSource.NAMED_VALUE)))
        val out = doc(listOf(message(repeat = Seq3Repeat.FIRST_LAST, occurrences = withValues, match = match, label = "value={value}"))).toMermaid()

        assertEquals(2, Regex("->>").findAll(out).count(), "first+last is exactly two arrows")
        assertTrue(out.contains("value=1"), "the first occurrence's real value must be substituted in; got:\n$out")
        assertTrue(out.contains("value=6"), "the last occurrence's real value must be substituted in; got:\n$out")
        assertTrue(out.contains("elided"), "got:\n$out")
    }

    // ── Fragments ────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyRealUmlFragmentKindEmitsItsNativeKeywordAndABalancedEnd() {
        // GROUP is deliberately excluded here — it is not a UML operator and Mermaid has no bare
        // "group" keyword at all (see groupEmitsRectAndNoteOverInMermaidButGroupInPlantUml below,
        // which is the dedicated test for its very different, per-dialect shape).
        (Seq3FragmentKind.entries - Seq3FragmentKind.GROUP).forEach { kind ->
            val msg = message()
            val fragment = Seq3Fragment("f1", kind, "Retry", listOf("m1"))
            val out = doc(listOf(msg), fragments = listOf(fragment)).toMermaid()

            val keyword = kind.name.lowercase()
            assertTrue(out.contains("    $keyword Retry\n"), "expected '$keyword Retry' in:\n$out")
            assertTrue(out.contains("    end\n"), "expected a balanced 'end' in:\n$out")
        }
    }

    @Test
    fun criticalAndBreakEmitTheBareKeywordInBothDialects() {
        listOf(Seq3FragmentKind.CRITICAL, Seq3FragmentKind.BREAK).forEach { kind ->
            val fragment = Seq3Fragment("f1", kind, "Retry", listOf("m1"))
            val doc = doc(listOf(message()), fragments = listOf(fragment))
            val keyword = kind.name.lowercase()

            val mermaid = doc.toMermaid()
            assertTrue(mermaid.contains("    $keyword Retry\n"), "expected '$keyword Retry' in Mermaid:\n$mermaid")
            assertTrue(mermaid.contains("    end\n"), "expected a balanced 'end' in Mermaid:\n$mermaid")

            val plantUml = doc.toPlantUml()
            assertTrue(plantUml.contains("$keyword Retry\n"), "expected '$keyword Retry' in PlantUML:\n$plantUml")
            assertTrue(plantUml.contains("end\n"), "expected a balanced 'end' in PlantUML:\n$plantUml")
        }
    }

    @Test
    fun groupEmitsRectAndNoteOverInMermaidButGroupInPlantUml() {
        // WP12: GROUP is not a UML operator — PlantUML invented `group <label>` for exactly this,
        // but the bare word `group` is a Mermaid PARSE ERROR, so Mermaid fakes it with
        // `rect rgb(...) … end` wrapping a `Note over` that carries the label. Both dialects are
        // asserted here, for the SAME document, because that divergence is the entire point.
        val fragment = Seq3Fragment("f1", Seq3FragmentKind.GROUP, "billing retry flow", listOf("m1"))
        val document = doc(listOf(message()), fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertFalse(mermaid.contains("    group "), "bare 'group' is a Mermaid parse error; got:\n$mermaid")
        assertTrue(mermaid.contains("    rect rgb("), "expected a 'rect rgb(...)' wrapper in Mermaid:\n$mermaid")
        assertTrue(
            mermaid.contains("    Note over A,B: billing retry flow\n"),
            "expected the label to survive as a 'Note over' in Mermaid:\n$mermaid",
        )
        assertTrue(mermaid.contains("    end\n"), "expected the rect to close with a balanced 'end' in Mermaid:\n$mermaid")

        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains("group billing retry flow\n"), "expected PlantUML's own 'group <label>' verbatim:\n$plantUml")
        assertTrue(plantUml.contains("end\n"), "expected a balanced 'end' in PlantUML:\n$plantUml")
    }

    @Test
    fun nestedFragmentsClampToTheirParentAndCloseInnermostFirst() {
        val messages = (1..4).map { i -> message(id = "m$i", label = "step$i", occurrences = listOf(occurrence(i, "step$i"))) }
        val outer = Seq3Fragment("outer", Seq3FragmentKind.LOOP, "Outer", listOf("m1", "m2", "m3", "m4"))
        val inner = Seq3Fragment("inner", Seq3FragmentKind.OPT, "Inner", listOf("m2", "m3"))
        val out = doc(messages, fragments = listOf(outer, inner)).toPlantUml()

        val outerOpen = out.indexOf("loop Outer")
        val innerOpen = out.indexOf("opt Inner")
        val innerClose = out.indexOf("end\n", innerOpen)
        val outerClose = out.indexOf("end\n", innerClose + 1)

        assertTrue(outerOpen in 0 until innerOpen, "the outer fragment must open before the inner one; got:\n$out")
        assertTrue(innerClose in 0 until outerClose, "the inner fragment must close before the outer one; got:\n$out")
    }

    @Test
    fun occurrenceScopedFragmentDoesNotWrapARepeatedSibling() {
        // A capture-bearing template is load-bearing here. Seq3Emitters only substitutes an
        // occurrence's real captured values into a per-occurrence arrow when the match declares
        // captures (see `occurrenceLabel`'s early return for a literal template) -- an occurrence's
        // raw `text` is never emitted. With a capture-free template both occurrences render as the
        // identical string, so the assertions below could not tell them apart, and a fragment label
        // sharing a substring with the values would be matched by `indexOf` instead of the arrow.
        val msg = message(
            label = "{step}",
            repeat = Seq3Repeat.EVERY,
            occurrences = listOf(
                occurrence(1, "alpha", mapOf("step" to "alpha")),
                occurrence(2, "beta", mapOf("step" to "beta")),
            ),
            match = Seq3Match(
                tag = "A",
                template = "{step}",
                captures = listOf(Seq3Capture("step", Seq3CaptureSource.POSITIONAL_RUN)),
            ),
        )
        val out = doc(
            listOf(msg),
            fragments = listOf(
                Seq3Fragment(
                    "exact",
                    Seq3FragmentKind.LOOP,
                    "scoped",
                    messageIds = emptyList(),
                    occurrenceRefs = listOf(Seq3OccurrenceRef("m1", 1)),
                ),
            ),
        ).toMermaid()
        val open = out.indexOf("loop scoped")
        val close = out.indexOf("    end\n", open)
        val alpha = out.indexOf("alpha")
        val beta = out.indexOf("beta")

        assertTrue(open >= 0 && close > open, "fragment must be balanced; got:\n$out")
        assertTrue(open < alpha && alpha < close, "scoped occurrence must be inside the fragment; got:\n$out")
        assertTrue(beta > close, "sibling occurrence must stay outside the fragment; got:\n$out")
    }

    // ── Notes ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNoteSpanningASelectionRendersAfterTheLastReferencedMessage() {
        val messages = (1..2).map { i -> message(id = "m$i", label = "step$i", occurrences = listOf(occurrence(i, "step$i"))) }
        val note = Seq3Note("n1", "watch out here", listOf("m1", "m2"))
        val out = doc(messages, notes = listOf(note)).toMermaid()

        assertTrue(out.contains("Note over A,B: watch out here"), "got:\n$out")
        assertTrue(out.indexOf("step2") < out.indexOf("watch out here"), "the note must trail its last referenced message")
    }

    // ── Time-gap markers (WP11) — the two dialects genuinely differ; see Seq3Emitters.kt's own
    //    "Time-gap markers" header for why this must never be "unified" into one shared branch. ──

    @Test
    fun plantUmlEmitsRealDelaySyntaxAndMermaidEmitsNoteOverForTheSameDocument() {
        val messages = listOf(message(id = "m1", label = "step1"), message(id = "m2", label = "step2"))
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "5 minutes later")
        val document = doc(messages).copy(delays = listOf(delay))

        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains("...5 minutes later...\n"), "PlantUML must use its own real delay syntax; got:\n$plantUml")

        val mermaid = document.toMermaid()
        assertFalse(mermaid.contains("..."), "Mermaid has no delay construct at all — must never leak PlantUML's syntax; got:\n$mermaid")
        assertTrue(mermaid.contains("Note over A,B: 5 minutes later"), "Mermaid must fall back to a full-width Note over; got:\n$mermaid")
    }

    @Test
    fun aDelayAnchoredToOneOccurrenceExportsRightAfterThatOccurrenceEvenWhenTheMessageRepeatsLater() {
        // User-observed correction: PlantUML's `...` / Mermaid's Note-over used to always land
        // after a repeated message's LAST occurrence regardless of which one a delay was actually
        // anchored to.
        val messages = listOf(
            message(id = "m1", label = "repeats", occurrences = listOf(occurrence(1, "repeats"), occurrence(2, "repeats"))),
        )
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "gap", afterOccurrenceEntryId = 1)
        val document = doc(messages).copy(delays = listOf(delay))

        val plantUml = document.toPlantUml()
        val firstLine = plantUml.lines().indexOfFirst { it.contains("repeats") }
        val delayLine = plantUml.lines().indexOfFirst { it.contains("...gap...") }
        val secondLine = plantUml.lines().indexOfLast { it.contains("repeats") }
        assertTrue(firstLine < delayLine, "the delay must come after the FIRST occurrence; got:\n$plantUml")
        assertTrue(delayLine < secondLine, "the delay must come before the SECOND occurrence, not after both; got:\n$plantUml")
    }

    @Test
    fun aDelayAnchoredToAStaleOccurrenceFallsBackToAfterTheLastOccurrenceOfItsMessage() {
        val messages = listOf(
            message(id = "m1", label = "repeats", occurrences = listOf(occurrence(1, "repeats"), occurrence(2, "repeats"))),
        )
        // entryId 99 was never emitted (hidden, or the row no longer repeats that many times).
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "gap", afterOccurrenceEntryId = 99)
        val document = doc(messages).copy(delays = listOf(delay))

        val plantUml = document.toPlantUml()
        val lastLine = plantUml.lines().indexOfLast { it.contains("repeats") }
        val delayLine = plantUml.lines().indexOfFirst { it.contains("...gap...") }
        assertTrue(delayLine > lastLine, "a dangling occurrence ref must fall back to after the message's last occurrence, not be dropped; got:\n$plantUml")
    }

    @Test
    fun aHiddenDelayIsOmittedFromBothDialects() {
        val messages = listOf(message(id = "m1", label = "step1"))
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "shouldn't appear", visibility = Seq3Visibility.HIDDEN)
        val document = doc(messages).copy(delays = listOf(delay))

        assertFalse(document.toMermaid().contains("shouldn't appear"))
        assertFalse(document.toPlantUml().contains("shouldn't appear"))
    }

    @Test
    fun aDelayDoesNotConsumeACallNumberInEitherDialect() {
        val messages = listOf(
            message(id = "m1", label = "first"),
            message(id = "m2", label = "second"),
        )
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "a pause")
        val document = doc(messages).copy(delays = listOf(delay), showSequenceNumbers = true)

        val mermaid = document.toMermaid()
        // Mermaid escapes '#' (its own comment/directive delimiter) in every label it writes,
        // exactly like theSamePrefixIsProducedByTheLayoutRowLabelAndBothEmittedDialects above —
        // "[#1]" becomes "[#35;1]" in mermaid text, never a parity bug.
        assertTrue(mermaid.contains("[#35;1] first"), "got:\n$mermaid")
        assertTrue(mermaid.contains("[#35;2] second"), "the delay in between must not consume #2, leaving second stuck at #3; got:\n$mermaid")
        assertTrue(mermaid.contains("Note over A,B: a pause"), "the delay's own line must never itself carry a [#n] prefix; got:\n$mermaid")

        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains("[#1] first"), "got:\n$plantUml")
        assertTrue(plantUml.contains("[#2] second"), "got:\n$plantUml")
    }

    // ── WP2: task 7 positive coverage ───────────────────────────────────────────────────────────
    //
    // occurrenceScopedFragmentDoesNotWrapARepeatedSibling (above) fails on pristine HEAD (verified
    // in a clean worktree at 4cbd9dd3, before any WP1/WP2 change). Diagnosis: its fixture declares
    // NO Seq3Capture on the message's match, so occurrenceLabel (this file's own — captures.isEmpty
    // -> return labelTemplate verbatim) renders the IDENTICAL "label" text for both occurrences;
    // the occurrence's own `text` field ("first"/"second") is raw evidence, never substituted into
    // the rendered arrow by design (see occurrenceLabel's doc comment above). So:
    //   - `out.indexOf("first")` accidentally matches inside the FRAGMENT'S OWN LABEL ("only
    //     first"), not inside any occurrence's arrow — the assertion passes for the wrong reason.
    //   - `out.indexOf("second")` never matches anything (the word "second" is never emitted at
    //     all) and returns -1, which is what actually fails `second > close`.
    // The underlying fragment-scoping behavior is verified CORRECT below: `fragmentBounds`
    // resolves the occurrenceRefs-scoped fragment to exactly index 0 (entryId 1's emission), so the
    // repeated sibling (entryId 2) correctly renders outside the loop — this is (c), a test-fixture
    // defect, not (a) a fragment-span defect or (b) a repeat-mode emission defect. Left unedited
    // per this task's instruction to report rather than edit a wrong test to pass; this test proves
    // the real behavior using a fixture that actually differentiates its two occurrences' text.
    @Test
    fun occurrenceScopedFragmentCoversOnlyItsExactOccurrenceWhenOccurrencesRenderDistinctText() {
        val match = Seq3Match(tag = "A", template = "value={value}", captures = listOf(Seq3Capture("value", Seq3CaptureSource.NAMED_VALUE)))
        val msg = message(
            repeat = Seq3Repeat.EVERY,
            label = "value={value}",
            match = match,
            occurrences = listOf(occurrence(1, "value=1", mapOf("value" to "1")), occurrence(2, "value=2", mapOf("value" to "2"))),
        )
        val out = doc(
            listOf(msg),
            fragments = listOf(
                Seq3Fragment(
                    "exact",
                    Seq3FragmentKind.LOOP,
                    "only first",
                    messageIds = emptyList(),
                    occurrenceRefs = listOf(Seq3OccurrenceRef("m1", 1)),
                ),
            ),
        ).toMermaid()
        val open = out.indexOf("loop only first")
        val close = out.indexOf("    end\n", open)
        val first = out.indexOf("value=1")
        val second = out.indexOf("value=2")

        assertTrue(open >= 0 && close > open, "fragment must be balanced; got:\n$out")
        assertTrue(open < first && first < close, "the exact referenced occurrence must be inside the fragment; got:\n$out")
        assertTrue(second > close, "the repeated sibling occurrence must stay outside the fragment; got:\n$out")
    }

    // ── WP2: emitter ordinal order, actor keyword, resolved display name, hidden skip ──────────

    @Test
    fun participantOrderFollowsOrdinalNotDocumentListOrder() {
        // "B" has ordinal 0 (drawn first) despite being declared AFTER "A" (ordinal 1) in the
        // lifelines list — Seq3Layout sorts by ordinal, and the emitters must agree or an exported
        // participant order can silently disagree with the canvas (WP2's fix).
        val firstDrawn = Seq3Lifeline("B", "Lifeline B", setOf("B"), 0)
        val secondDrawn = Seq3Lifeline("A", "Lifeline A", setOf("A"), 1)
        val document = Seq3Document(lifelines = listOf(secondDrawn, firstDrawn), messages = listOf(message(from = "A", to = "B")))
        val out = document.toMermaid()

        val participantOrder = Regex("participant (\\w+) as").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(listOf("B", "A"), participantOrder, "participants must be emitted in ORDINAL order, not document-list order; got:\n$out")
    }

    @Test
    fun actorLifelineEmitsTheActorKeywordInBothDialects() {
        val actor = Seq3Lifeline("A", "User", setOf("A"), 0, kind = Seq3LifelineKind.ACTOR)
        val participant = Seq3Lifeline("B", "Server", setOf("B"), 1)
        val document = Seq3Document(lifelines = listOf(actor, participant), messages = listOf(message(from = "A", to = "B")))
        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()

        assertTrue(mermaid.contains("actor A as User"), "got:\n$mermaid")
        assertFalse(mermaid.contains("participant A as User"), "an ACTOR lifeline must not also emit as a participant; got:\n$mermaid")
        assertTrue(plantUml.contains("actor \"User\" as A"), "got:\n$plantUml")
    }

    @Test
    fun participantLabelUsesTheResolvedDisplayNameNotTheRawName() {
        val long = Seq3Lifeline("A", "com.mycompany.myapp.Example1", setOf("A"), 0, displaySegments = 1)
        val other = Seq3Lifeline("B", "Lifeline B", setOf("B"), 1)
        val document = Seq3Document(lifelines = listOf(long, other), messages = listOf(message(from = "A", to = "B")))
        val out = document.toMermaid()

        assertTrue(out.contains("participant A as Example1"), "got:\n$out")
        assertFalse(out.contains("com.mycompany.myapp.Example1"), "the header must show the resolved display name, not the raw dotted name; got:\n$out")
    }

    @Test
    fun hiddenFragmentIsOmittedFromTheEmittedText() {
        val hiddenFragment = Seq3Fragment("f1", Seq3FragmentKind.LOOP, "hidden loop", listOf("m1"), visibility = Seq3Visibility.HIDDEN)
        val out = doc(listOf(message()), fragments = listOf(hiddenFragment)).toMermaid()

        assertFalse(out.contains("loop"), "a hidden fragment must not open a block; got:\n$out")
    }

    @Test
    fun hiddenNoteIsOmittedFromTheEmittedText() {
        val hiddenNote = Seq3Note("n1", "hidden note text", listOf("m1"), visibility = Seq3Visibility.HIDDEN)
        val out = doc(listOf(message()), notes = listOf(hiddenNote)).toMermaid()

        assertFalse(out.contains("hidden note text"), "a hidden note must not render; got:\n$out")
    }

    // ── Item 9 (WP9 regression fix) — same rule as Seq3LayoutTest, must agree with it exactly ────

    @Test
    fun collapsedRowWithThreeOrFewerDistinctValuesShowsACompactSummary() {
        val occurrences = listOf(
            occurrence(1, "onScreenChanged: MEDIA", mapOf("screen" to "MEDIA")),
            occurrence(2, "onScreenChanged: HOME", mapOf("screen" to "HOME")),
            occurrence(3, "onScreenChanged: MEDIA", mapOf("screen" to "MEDIA")),
            occurrence(4, "onScreenChanged: HOME", mapOf("screen" to "HOME")),
        )
        val match = Seq3Match(tag = "A", template = "onScreenChanged: {screen}", captures = listOf(Seq3Capture("screen", Seq3CaptureSource.NAMED_VALUE)))
        val out = doc(
            listOf(
                message(
                    repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3, occurrences = occurrences,
                    match = match, label = "onScreenChanged: {screen}",
                ),
            ),
        ).toPlantUml()

        assertTrue(
            out.contains("onScreenChanged: MEDIA|onScreenChanged: HOME"),
            "a collapsed row with <=3 distinct values must show a compact A|B|C summary, not a raw {token}; got:\n$out",
        )
        assertFalse(out.contains("{screen}"), "got:\n$out")
    }

    @Test
    fun collapsedRowWithMoreThanThreeDistinctValuesKeepsTheRawTemplate() {
        val occurrences = (1..5).map { i -> occurrence(i, "onScreenChanged: V$i", mapOf("screen" to "V$i")) }
        val match = Seq3Match(tag = "A", template = "onScreenChanged: {screen}", captures = listOf(Seq3Capture("screen", Seq3CaptureSource.NAMED_VALUE)))
        val out = doc(
            listOf(
                message(
                    repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3, occurrences = occurrences,
                    match = match, label = "onScreenChanged: {screen}",
                ),
            ),
        ).toPlantUml()

        assertTrue(
            out.contains("onScreenChanged: {screen}"),
            "above 3 distinct values, the raw {token} template is the honest 'many different values' signal; got:\n$out",
        )
    }

    // ── WP10 (item 7): inline call numbering / timestamps ───────────────────────────────────────

    @Test
    fun showSequenceNumbersPrefixesEachDrawnCallAndSkipsHiddenMessages() {
        val document = doc(
            listOf(
                message("m1", label = "first"),
                message("m2", label = "hidden").copy(visibility = Seq3Visibility.HIDDEN),
                message("m3", label = "second"),
            ),
        ).copy(showSequenceNumbers = true)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()

        // Mermaid escapes '#' (its own entity-escape marker) in every label it writes, "[#1]"
        // included — see mermaidEscapesReservedCharactersAndTurnsNewlinesIntoBr; "#35;" is that
        // escape's literal replacement for '#'.
        assertTrue(mermaid.contains(": [#35;1] first"), "got:\n$mermaid")
        assertTrue(mermaid.contains(": [#35;2] second"), "a hidden message must not consume a number; got:\n$mermaid")
        assertFalse(mermaid.contains("hidden"), "a hidden message must not appear at all; got:\n$mermaid")
        assertTrue(plantUml.contains(": [#1] first"), "got:\n$plantUml")
        assertTrue(plantUml.contains(": [#2] second"), "got:\n$plantUml")
    }

    @Test
    fun collapsedRepeatEmissionTakesExactlyOneSequenceNumber() {
        val occurrences = (1..5).map { i -> occurrence(i, "repeated") }
        val document = doc(
            listOf(
                message("m1", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3, occurrences = occurrences, label = "repeated"),
                message("m2", label = "next"),
            ),
        ).copy(showSequenceNumbers = true)

        val out = document.toMermaid()

        // See showSequenceNumbersPrefixesEachDrawnCallAndSkipsHiddenMessages's own comment: mermaid
        // escapes '#' to "#35;" in every label.
        assertTrue(out.contains(": [#35;1] repeated ×5"), "the collapsed ×5 group must draw as ONE numbered call; got:\n$out")
        assertTrue(out.contains(": [#35;2] next"), "the message after a collapsed row must be #2, not #6; got:\n$out")
    }

    @Test
    fun showTimestampsPrefixesTheOccurrencesRawTimestamp() {
        val document = doc(listOf(message("m1", label = "hello"))).copy(showTimestamps = true)

        // PlantUML never escapes ':', so this is the literal, unescaped prefix — see this class's
        // own parity test for why Mermaid's escaped form ("#58;" in place of ':') is expected, not
        // a bug, and asserted separately there.
        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains(": [10:00:00.000] hello"), "got:\n$plantUml")

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains(": [10#58;00#58;00.000] hello"), "got:\n$mermaid")
    }

    @Test
    fun bothTogglesOffLeaveTheEmittedTextUnprefixed() {
        val out = doc(listOf(message("m1", label = "plain"))).toMermaid()

        assertTrue(out.contains(": plain"), "got:\n$out")
        assertFalse(out.contains("[#"), "got:\n$out")
    }

    // ── Parity: layout row label and both emitted dialects must be byte-identical (WP10's whole
    //    point is a single shared prefix helper — see Seq3LabelSummary.seq3PrefixedLabel). This
    //    document's messages are already in chronological/declaration order, so Seq3Layout's
    //    canvas-order numbering and Seq3Emitters' declaration-order numbering agree exactly — see
    //    prefixSeq3EmissionLabels' own doc for why that agreement isn't guaranteed in general. ────

    @Test
    fun theSamePrefixIsProducedByTheLayoutRowLabelAndBothEmittedDialects() {
        val occ1 = Seq3Occurrence(entryId = 1, timestampMillis = 1_000L, rawTimestamp = "10:00:01.000", pid = 0, tid = 0, level = 'I', text = "first")
        val occ2 = Seq3Occurrence(entryId = 2, timestampMillis = 2_000L, rawTimestamp = "10:00:02.000", pid = 0, tid = 0, level = 'I', text = "second")
        val messages = listOf(
            message("m1", occurrences = listOf(occ1), label = "first"),
            message("m2", occurrences = listOf(occ2), label = "second"),
        )
        val document = doc(messages).copy(showSequenceNumbers = true, showTimestamps = true)

        val layout = layoutSeq3(document, Seq3LayoutOptions(FixedWidthMetrics()))
        val layoutLabels = layout.rows.filterIsInstance<Seq3ArrowRow>().map { it.label }
        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()

        val expected = listOf("[#1] [10:00:01.000] first", "[#2] [10:00:02.000] second")
        assertEquals(expected, layoutLabels, "canvas row labels")
        // PlantUML's own escaping (backslash/quote/newline only) never touches this prefix, so the
        // text is byte-identical to the canvas row label — the direct proof the shared helper
        // (Seq3LabelSummary.seq3PrefixedLabel) produced the same string in both places.
        expected.forEach { prefixed ->
            assertTrue(plantUml.contains(": $prefixed"), "plantuml must carry the identical prefix; got:\n$plantUml")
        }
        // Mermaid escapes ':' (its own arrow-syntax delimiter) in EVERY label it writes — same
        // treatment an ordinary user label with a colon in it already gets (see
        // mermaidEscapesReservedCharactersAndTurnsNewlinesIntoBr above) — so a timestamp's colons
        // are escaped too. The prefix's CONTENT (same number, same timestamp text) still came from
        // the identical seq3PrefixedLabel call as the canvas row and PlantUML; only mermaid's
        // mandatory post-hoc escaping differs the raw bytes, which is dialect-correct, not a
        // parity bug.
        expected.forEach { prefixed ->
            // Order matters: escape '#' FIRST, exactly like mermaidEscape's own single left-to-right
            // pass over the raw text — escaping ':' first would corrupt the "#58;" it just wrote by
            // then also escaping ITS '#'.
            val mermaidEscaped = prefixed.replace("#", "#35;").replace(":", "#58;")
            assertTrue(mermaid.contains(": $mermaidEscaped"), "mermaid must carry the same prefix content, escaped; got:\n$mermaid")
        }
    }

    // ── Task 0 (round-2 corrections plan, WP11 prerequisite): canvas and exported text must
    //    agree on ROW ORDER, not just on the prefix string, for a document whose `messages` list
    //    is deliberately NOT already in timestamp order — e.g. reachable via
    //    `Seq3Command.MoveMessage`, or an authored message inserted with a `manualTimestampMillis`
    //    that disagrees with its list position. Before the shared `seq3ChronologicalOrder` fix,
    //    Seq3Emitters never re-sorted its own emissions at all, so this fixture would have drawn
    //    "earlier" before "later" on the canvas while emitting "later" (declaration order) first
    //    in Mermaid/PlantUML text, with a MISMATCHED `[#n]` on top of it. ─────────────────────────

    @Test
    fun canvasRowOrderAndEmittedTextOrderAgreeForAnOutOfOrderMessageList() {
        val laterOcc = Seq3Occurrence(entryId = 1, timestampMillis = 5_000L, rawTimestamp = "10:00:05.000", pid = 0, tid = 0, level = 'I', text = "later")
        val earlierOcc = Seq3Occurrence(entryId = 2, timestampMillis = 1_000L, rawTimestamp = "10:00:01.000", pid = 0, tid = 0, level = 'I', text = "earlier")
        // Deliberately NOT time-ordered: "mLater" (ts=5000) is declared FIRST, "mEarlier" (ts=1000)
        // SECOND — the reverse of true chronological order.
        val messages = listOf(
            message("mLater", occurrences = listOf(laterOcc), label = "later-thing"),
            message("mEarlier", occurrences = listOf(earlierOcc), label = "earlier-thing"),
        )
        val document = doc(messages).copy(showSequenceNumbers = true)

        val layout = layoutSeq3(document, Seq3LayoutOptions(FixedWidthMetrics()))
        val canvasOrder = layout.rows.sortedBy { it.y }.map { it.messageId }
        assertEquals(listOf("mEarlier", "mLater"), canvasOrder, "canvas must draw true chronological order, not declaration order")

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        val mermaidEarlierIdx = mermaid.indexOf("earlier-thing")
        val mermaidLaterIdx = mermaid.indexOf("later-thing")
        assertTrue(mermaidEarlierIdx in 0 until mermaidLaterIdx, "mermaid must emit the chronologically earlier arrow first; got:\n$mermaid")
        val plantUmlEarlierIdx = plantUml.indexOf("earlier-thing")
        val plantUmlLaterIdx = plantUml.indexOf("later-thing")
        assertTrue(plantUmlEarlierIdx in 0 until plantUmlLaterIdx, "plantuml must emit the chronologically earlier arrow first; got:\n$plantUml")

        // The call NUMBER must agree too, not just line order: #1 goes to the chronologically
        // FIRST row (mEarlier) in canvas, Mermaid, AND PlantUML alike.
        val layoutLabels = layout.rows.filterIsInstance<Seq3ArrowRow>().associateBy({ it.messageId }, { it.label })
        assertEquals("[#1] earlier-thing", layoutLabels.getValue("mEarlier"))
        assertEquals("[#2] later-thing", layoutLabels.getValue("mLater"))
        // Mermaid escapes '#' in every label (see aDelayDoesNotConsumeACallNumberInEitherDialect's
        // own comment for why "[#1]" becomes "[#35;1]" in mermaid text).
        assertTrue(mermaid.contains("[#35;1] earlier-thing"), "got:\n$mermaid")
        assertTrue(mermaid.contains("[#35;2] later-thing"), "got:\n$mermaid")
        assertTrue(plantUml.contains("[#1] earlier-thing"), "got:\n$plantUml")
        assertTrue(plantUml.contains("[#2] later-thing"), "got:\n$plantUml")
    }

    private class FixedWidthMetrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }
}
