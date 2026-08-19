package com.indagium

import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Visibility
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
    fun everyFragmentKindEmitsItsNativeKeywordAndABalancedEnd() {
        Seq3FragmentKind.entries.forEach { kind ->
            val msg = message()
            val fragment = Seq3Fragment("f1", kind, "Retry", listOf("m1"))
            val out = doc(listOf(msg), fragments = listOf(fragment)).toMermaid()

            val keyword = kind.name.lowercase()
            assertTrue(out.contains("    $keyword Retry\n"), "expected '$keyword Retry' in:\n$out")
            assertTrue(out.contains("    end\n"), "expected a balanced 'end' in:\n$out")
        }
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
}
