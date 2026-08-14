package com.indagium

import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
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

    // ── Notes ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aNoteSpanningASelectionRendersAfterTheLastReferencedMessage() {
        val messages = (1..2).map { i -> message(id = "m$i", label = "step$i", occurrences = listOf(occurrence(i, "step$i"))) }
        val note = Seq3Note("n1", "watch out here", listOf("m1", "m2"))
        val out = doc(messages, notes = listOf(note)).toMermaid()

        assertTrue(out.contains("Note over A,B: watch out here"), "got:\n$out")
        assertTrue(out.indexOf("step2") < out.indexOf("watch out here"), "the note must trail its last referenced message")
    }
}
