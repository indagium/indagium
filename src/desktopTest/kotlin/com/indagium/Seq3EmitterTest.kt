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
import com.indagium.diagram3.Seq3Operand
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

    // ── LOST / FOUND (WP9) ──────────────────────────────────────────────────────────────────
    //
    // A LOST/FOUND message also has a null `toLifelineId` (same as the needs-target/NOTE cases
    // just above) but is a RESOLVED, honest UML shape — never the "N messages need a target"
    // defect that null target usually means. PlantUML has real grammar for it (verified against
    // plantuml.com's own "Incoming and outgoing messages" section: `[o->` = found, `->o]` = lost).
    // Mermaid has no such primitive, so it keeps the stub-note fallback shape but must not say
    // "needs target" — that wording is exactly what WP9 exists to stop saying about these two.

    @Test
    fun lostMessageEmitsRealPlantUmlGateSyntax() {
        val lost = message(kind = Seq3Kind.LOST, to = null, label = "ping")
        val out = doc(listOf(lost)).toPlantUml()

        assertTrue(out.contains("A ->o]: ping"), "must use PlantUML's real lost-message gate syntax; got:\n$out")
        assertFalse(out.contains("needs target"), "a lost message is resolved, not a defect; got:\n$out")
    }

    @Test
    fun foundMessageEmitsRealPlantUmlGateSyntax() {
        val found = message(kind = Seq3Kind.FOUND, to = null, label = "tap")
        val out = doc(listOf(found)).toPlantUml()

        assertTrue(out.contains("[o-> A: tap"), "must use PlantUML's real found-message gate syntax; got:\n$out")
        assertFalse(out.contains("needs target"), "a found message is resolved, not a defect; got:\n$out")
    }

    @Test
    fun lostAndFoundMermaidFallbackDropsNeedsTargetWording() {
        val lost = message(kind = Seq3Kind.LOST, to = null, label = "ping")
        val found = message(id = "m2", kind = Seq3Kind.FOUND, to = null, label = "tap")
        val out = doc(listOf(lost, found)).toMermaid()

        assertTrue(out.contains("· lost"), "got:\n$out")
        assertTrue(out.contains("· found"), "got:\n$out")
        assertFalse(
            out.contains("needs target"),
            "WP9 exists specifically to stop calling a resolved lost/found message this; got:\n$out",
        )
    }

    @Test
    fun lostMessageIsNotRenderedAsAnOrdinaryArrow() {
        val lost = message(kind = Seq3Kind.LOST, to = null, label = "ping")
        val mermaid = doc(listOf(lost)).toMermaid()
        val plantUml = doc(listOf(lost)).toPlantUml()

        assertFalse(mermaid.contains("A->>B"), "a LOST message must never draw an ordinary arrow to B; got:\n$mermaid")
        assertFalse(plantUml.contains("A -> B"), "a LOST message must never draw an ordinary arrow to B; got:\n$plantUml")
        assertTrue(plantUml.contains("->o]"), "must use the real PlantUML lost-message gate syntax; got:\n$plantUml")
    }

    // ── CREATE / DESTROY (WP10) ─────────────────────────────────────────────────────────────
    //
    // Unlike LOST/FOUND above, CREATE/DESTROY are ordinary TARGETED arrows — a real `toLifelineId`
    // naming the constructed/destroyed lifeline (WP10's brief) — so every fixture below keeps the
    // default `to = "B"`, never forces it null.

    @Test
    fun createMessageEmitsRealCreateDirectiveInBothDialects() {
        val create = message(kind = Seq3Kind.CREATE, label = "ctor")
        val mermaid = doc(listOf(create)).toMermaid()
        val plantUml = doc(listOf(create)).toPlantUml()

        // Mermaid's real grammar (confirmed against mermaid-js's own sequenceDiagram.jison and its
        // documented example): `create participant <alias> as <name>`, on the line BEFORE the
        // message that creates it.
        assertTrue(mermaid.contains("create participant B as Lifeline B"), "got:\n$mermaid")
        assertTrue(mermaid.contains("A-->>B: ctor"), "a CREATE arrow reuses RETURN's dashed token (Seq3ArrowStyle); got:\n$mermaid")
        assertTrue(
            mermaid.indexOf("create participant B as Lifeline B") < mermaid.indexOf("A-->>B: ctor"),
            "Mermaid's create directive must precede the creating message; got:\n$mermaid",
        )
        // PlantUML's real convention (confirmed against plantuml.com's "Participant creation"
        // section): bare `create <alias>`, also before the message.
        assertTrue(plantUml.contains("create B"), "got:\n$plantUml")
        assertTrue(plantUml.contains("A --> B: ctor"), "got:\n$plantUml")
        assertTrue(
            plantUml.indexOf("create B") < plantUml.indexOf("A --> B: ctor"),
            "PlantUML's create directive must precede the creating message; got:\n$plantUml",
        )
    }

    @Test
    fun destroyMessageEmitsRealDestroyDirectiveInBothDialects() {
        val destroy = message(kind = Seq3Kind.DESTROY, label = "dtor")
        val mermaid = doc(listOf(destroy)).toMermaid()
        val plantUml = doc(listOf(destroy)).toPlantUml()

        // Mermaid's real grammar: `destroy <alias>` — no `as` clause — placed BEFORE the message
        // that destroys it (confirmed against the jison grammar's `'destroy' actor 'NEWLINE'`
        // production and mermaid-js's own worked example).
        assertTrue(mermaid.contains("destroy B"), "got:\n$mermaid")
        assertTrue(mermaid.contains("A->>B: dtor"), "a DESTROY arrow reuses CALL's plain token; got:\n$mermaid")
        assertTrue(
            mermaid.indexOf("destroy B") < mermaid.indexOf("A->>B: dtor"),
            "Mermaid's destroy directive must precede the destroying message; got:\n$mermaid",
        )
        // PlantUML's real convention (plantuml.com's "Lifeline Activation and Destruction"
        // section): `destroy <alias>` AFTER the message that destroys it — the OPPOSITE ordering
        // from Mermaid.
        assertTrue(plantUml.contains("destroy B"), "got:\n$plantUml")
        assertTrue(plantUml.contains("A -> B: dtor"), "got:\n$plantUml")
        assertTrue(
            plantUml.indexOf("destroy B") > plantUml.indexOf("A -> B: dtor"),
            "PlantUML's destroy directive must follow the destroying message; got:\n$plantUml",
        )
    }

    @Test
    fun createdLifelineIsNotAlsoDeclaredInMermaidsHeaderBlock() {
        // Hard constraint (WP10 brief): Mermaid errors on a participant that is both header-
        // declared AND later `create`d — the create grammar reuses the exact same
        // participant_statement, so declaring it twice is a literal redeclaration. Exact-line
        // comparison (not `contains`), because "create participant B as Lifeline B" itself
        // CONTAINS "participant B as Lifeline B" as a substring — a naive `contains` check on the
        // plain header line would false-pass even if the header block still wrongly declared it.
        val create = message(kind = Seq3Kind.CREATE, label = "ctor")
        val lines = doc(listOf(create)).toMermaid().lines().map { it.trim() }

        assertFalse(lines.contains("participant B as Lifeline B"), "B must not ALSO be declared in the header block; lines:\n$lines")
        assertTrue(lines.contains("create participant B as Lifeline B"), "B must be declared via the create directive instead; lines:\n$lines")
        // A never has a CREATE targeting it, so it keeps the ordinary header declaration.
        assertTrue(lines.contains("participant A as Lifeline A"), "an un-created lifeline must keep its ordinary header declaration; lines:\n$lines")
    }

    @Test
    fun createdLifelineIsNotAlsoDeclaredInPlantUmlsHeaderBlock() {
        // Structural parallelism with the Mermaid test above (WP10 brief: "Apply the same skip in
        // toPlantUml so the two stay structurally parallel") — PlantUML itself does not error on a
        // double declaration, but this package's emitters must not quietly diverge on WHEN a
        // participant's box first appears.
        val create = message(kind = Seq3Kind.CREATE, label = "ctor")
        val lines = doc(listOf(create)).toPlantUml().lines().map { it.trim() }

        assertFalse(lines.contains("participant \"Lifeline B\" as B"), "B must not ALSO be declared in the header block; lines:\n$lines")
        assertTrue(lines.contains("create B"), "B must be declared via the create directive instead; lines:\n$lines")
        assertTrue(lines.contains("participant \"Lifeline A\" as A"), "an un-created lifeline must keep its ordinary header declaration; lines:\n$lines")
    }

    @Test
    fun createAndDestroyMessagesNeverEmitJustABareArrowWithoutTheirOwnKeyword() {
        // Unlike a plain CALL, a CREATE/DESTROY message's whole meaning is "this row is when the
        // box appears/disappears" — losing the keyword line would silently degrade it back to an
        // indistinguishable ordinary arrow (exactly the LOST/FOUND regression
        // lostMessageIsNotRenderedAsAnOrdinaryArrow above guards against, for a different pair of
        // kinds and a different failure shape: here the arrow itself still draws, but the
        // create/destroy FACT would be lost without its own keyword line).
        val create = message(kind = Seq3Kind.CREATE, label = "ctor")
        val destroy = message(kind = Seq3Kind.DESTROY, label = "dtor")

        val createMermaid = doc(listOf(create)).toMermaid()
        val createPlantUml = doc(listOf(create)).toPlantUml()
        val destroyMermaid = doc(listOf(destroy)).toMermaid()
        val destroyPlantUml = doc(listOf(destroy)).toPlantUml()

        assertTrue(createMermaid.contains("create participant B"), "got:\n$createMermaid")
        assertTrue(createPlantUml.contains("create B"), "got:\n$createPlantUml")
        assertTrue(destroyMermaid.contains("destroy B"), "got:\n$destroyMermaid")
        assertTrue(destroyPlantUml.contains("destroy B"), "got:\n$destroyPlantUml")
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
        // GROUP, the four WP11 kinds (NEG/STRICT/CONSIDER/IGNORE), and WP17's REF are deliberately
        // excluded here. GROUP is not a UML operator at all; NEG/STRICT/CONSIDER/IGNORE ARE real
        // UML operators but Mermaid's grammar has no keyword for any of them either; REF (WP17) is
        // ALSO a real UML operator with no Mermaid keyword, so none of these six emits a bare
        // keyword in Mermaid — see groupEmitsRectAndNoteOverInMermaidButGroupInPlantUml,
        // negStrictConsiderAndIgnoreDegradeToRectAndNoteOverInMermaidWithTheOperatorWordPreserved,
        // and refFallsBackToRectAndNoteOverInMermaidAndNeverEmitsABareRefKeyword below, the
        // dedicated tests for their very different, per-dialect shape. (REF would ALSO fail this
        // test's PlantUML-shaped assumption even if it emitted a bare Mermaid keyword: its real
        // PlantUML syntax is `ref over A, B : label`, not `keyword label` closed by a plain `end`
        // — see refEmitsRealPlantUmlRefOverSyntaxWithNoClosingEnd — so it could never share this
        // loop's single assertion shape with the true "same shape in both dialects" kinds below.)
        val mermaidFallbackKinds = setOf(
            Seq3FragmentKind.GROUP,
            Seq3FragmentKind.NEG,
            Seq3FragmentKind.STRICT,
            Seq3FragmentKind.CONSIDER,
            Seq3FragmentKind.IGNORE,
            Seq3FragmentKind.REF,
        )
        (Seq3FragmentKind.entries - mermaidFallbackKinds).forEach { kind ->
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
    fun aBlankFragmentLabelEmitsTheBareKindKeywordNotTheKindWordDoubled() {
        // Both open-line call sites already prefix the kind word themselves
        // ("${kind.name.lowercase()} ${fragmentLabel(fragment)}"), so a blank label must not ALSO
        // fall back to the kind word inside fragmentLabel — that would double it into "loop loop".
        val fragment = Seq3Fragment("f1", Seq3FragmentKind.LOOP, "", listOf("m1"))
        val document = doc(listOf(message()), fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertFalse(mermaid.contains("loop loop"), "kind word must not be doubled for a blank label; got:\n$mermaid")
        assertTrue(mermaid.contains("    loop\n"), "a blank label must leave the bare keyword with no trailing separator; got:\n$mermaid")

        val plantUml = document.toPlantUml()
        assertFalse(plantUml.contains("loop loop"), "kind word must not be doubled for a blank label; got:\n$plantUml")
        assertTrue(plantUml.contains("loop\n"), "a blank label must leave the bare keyword with no trailing separator; got:\n$plantUml")
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
    fun negStrictConsiderAndIgnoreEmitTheirRealOperatorKeywordInPlantUml() {
        // WP11: PlantUML needs no per-kind special case for any of these four — `kind.name
        // .lowercase()` already produces PlantUML's own `neg`/`strict`/`consider`/`ignore`
        // keyword, the exact same shared path LOOP/ALT/OPT/PAR/BREAK already take.
        listOf(Seq3FragmentKind.NEG, Seq3FragmentKind.STRICT, Seq3FragmentKind.CONSIDER, Seq3FragmentKind.IGNORE).forEach { kind ->
            val fragment = Seq3Fragment("f1", kind, "Retry", listOf("m1"))
            val out = doc(listOf(message()), fragments = listOf(fragment)).toPlantUml()

            val keyword = kind.name.lowercase()
            assertTrue(out.contains("$keyword Retry\n"), "expected '$keyword Retry' in PlantUML:\n$out")
            assertTrue(out.contains("end\n"), "expected a balanced 'end' in PlantUML:\n$out")
        }
    }

    @Test
    fun negStrictConsiderAndIgnoreDegradeToRectAndNoteOverInMermaidWithTheOperatorWordPreserved() {
        // WP11: Mermaid's sequence-diagram grammar (loop/alt/else/opt/par/and/critical/option/
        // break/rect) has no keyword for any of these four real UML operators — a bare `neg`/
        // `strict`/`consider`/`ignore` is a Mermaid PARSE ERROR, exactly like the bare `group`
        // that already forced GROUP's own fallback, so all four route through that SAME `rect` +
        // `Note over` fallback. Unlike GROUP's note (label only — GROUP has no operator word worth
        // showing), the note here must ALSO carry the operator word: dropping it would erase the
        // one thing that made picking NEG/CONSIDER over LOOP/GROUP meaningful once Mermaid can no
        // longer say `neg`/`consider` itself. The negative assertions ARE the point of this test —
        // a bare fallback keyword is the exact parse error being prevented.
        listOf(Seq3FragmentKind.NEG, Seq3FragmentKind.STRICT, Seq3FragmentKind.CONSIDER, Seq3FragmentKind.IGNORE).forEach { kind ->
            val fragment = Seq3Fragment("f1", kind, "Retry", listOf("m1"))
            val out = doc(listOf(message()), fragments = listOf(fragment)).toMermaid()

            val keyword = kind.name.lowercase()
            assertFalse(out.contains("    $keyword Retry\n"), "bare '$keyword' is a Mermaid parse error; got:\n$out")
            assertFalse(out.contains("    $keyword\n"), "bare '$keyword' is a Mermaid parse error; got:\n$out")
            assertTrue(out.contains("    rect rgb("), "expected a 'rect rgb(...)' wrapper in Mermaid:\n$out")
            assertTrue(out.contains("    end\n"), "expected the rect to close with a balanced 'end' in Mermaid:\n$out")
            assertTrue(
                out.contains("    Note over A,B: $keyword Retry\n"),
                "expected the operator word plus label to survive into the Mermaid fallback note:\n$out",
            )
        }
    }

    // ── ref / InteractionUse (WP17) ─────────────────────────────────────────────────────────

    @Test
    fun refEmitsRealPlantUmlRefOverSyntaxWithNoClosingEnd() {
        // PlantUML's REAL `ref` syntax is `ref over A, B : label` — confirmed against
        // plantuml.com's own sequence-diagram documentation (PlantUML publishes no public formal
        // grammar file the way mermaid-js does, so its own docs are the best available primary
        // source for this — see Seq3FragmentKind.REF's own doc, the same rigour WP11 applied to
        // Mermaid's jison grammar for create/destroy). Unlike every other fragment kind, a real
        // `ref over` is a STANDALONE statement, never a block — so it takes no closing `end`;
        // writing one would be an unmatched, unparseable token. Both assertions are the point.
        val fragment = Seq3Fragment("f1", Seq3FragmentKind.REF, "Retry", listOf("m1"))
        val out = doc(listOf(message()), fragments = listOf(fragment)).toPlantUml()

        assertTrue(out.contains("ref over A,B : Retry\n"), "expected real PlantUML 'ref over A,B : Retry' syntax; got:\n$out")
        assertFalse(out.contains("end\n"), "'ref over' is a standalone PlantUML statement, never closed by 'end'; got:\n$out")
    }

    @Test
    fun refFallsBackToRectAndNoteOverInMermaidAndNeverEmitsABareRefKeyword() {
        // Mermaid's sequence-diagram grammar has no `ref` construct at all — a bare `ref Retry`
        // would be a Mermaid PARSE ERROR, exactly like the bare `group`/`neg` that already forced
        // GROUP's/WP11's own fallback, so REF joins that SAME `rect` + `Note over` fallback,
        // keeping the word 'ref' in the note text so the construct survives the degradation
        // (same reasoning as NEG/STRICT/CONSIDER/IGNORE, unlike GROUP which carries no operator
        // word at all — see MERMAID_FALLBACK_FRAGMENT_KINDS' own doc). The negative assertions
        // are the point: they are the exact Mermaid parse error this fallback prevents.
        val fragment = Seq3Fragment("f1", Seq3FragmentKind.REF, "Retry", listOf("m1"))
        val out = doc(listOf(message()), fragments = listOf(fragment)).toMermaid()

        assertFalse(out.contains("    ref Retry\n"), "bare 'ref' is a Mermaid parse error; got:\n$out")
        assertFalse(out.contains("    ref\n"), "bare 'ref' is a Mermaid parse error; got:\n$out")
        assertTrue(out.contains("    rect rgb("), "expected a 'rect rgb(...)' wrapper in Mermaid:\n$out")
        assertTrue(out.contains("    end\n"), "expected the rect to close with a balanced 'end' in Mermaid:\n$out")
        assertTrue(
            out.contains("    Note over A,B: ref Retry\n"),
            "expected the word 'ref' plus label to survive into the Mermaid fallback note:\n$out",
        )
    }

    @Test
    fun refWithElseOperandsEmitsNoDividerInEitherDialect() {
        // REF is not one of ALT/PAR/CRITICAL (SEQ3_OPERAND_FRAGMENT_KINDS/DIVIDER_FRAGMENT_KINDS'
        // own gate) — mirrors optAndLoopEmitNoDividerEvenWhenOperandsArePresent above for the same
        // "the kind gate, not an emptiness check, is what suppresses the divider" reason. A stray
        // elseOperands entry (e.g. left behind by a kind change away from ALT and back through
        // REF) is preserved on the fragment but must never be RENDERED for a kind UML gives no
        // 'else' branch to.
        val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
        val fragment = Seq3Fragment(
            "f1", Seq3FragmentKind.REF, "cond0", listOf("m1", "m2"),
            elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2")),
        )
        val document = doc(messages, fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertFalse(mermaid.contains("cond1"), "REF must emit no divider even though elseOperands is non-empty; got:\n$mermaid")

        val plantUml = document.toPlantUml()
        assertFalse(plantUml.contains("cond1"), "REF must emit no divider even though elseOperands is non-empty; got:\n$plantUml")
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

    // ── Fragment operand dividers (WP5) ─────────────────────────────────────────────────────────
    //
    // Seq3Fragment.label is already operand ZERO's guard (see that field's own doc), so every
    // fixture below puts operand zero's guard in the fragment's `label` and adds ONE more operand
    // via elseOperands to exercise the divider itself.

    /** Walks [out]'s emitted lines maintaining fragment-bracket depth (a fragment-open keyword —
     *  including Mermaid's `rect` for GROUP — increments it, a plain "end" decrements it) and
     *  asserts every divider line ("else "/"and "/"option ", or the bare keyword) is written
     *  strictly INSIDE an open bracket — never at depth 0, i.e. never after that bracket's own
     *  "end" — and that every bracket closes by the end of the document. Same reasoning as
     *  assertValidActivationSequence below: equal keyword COUNTS don't prove correct ORDER, which
     *  is exactly what THE TRAP (Seq3Emitters.kt's operandDividersByAnchor doc) can violate — a
     *  divider resolved against a fragment's raw, un-clamped bounds instead of its normalized
     *  Seq3Bracket.range can land past a crossing fragment's clamped end. */
    private fun assertValidFragmentBracketNesting(out: String) {
        val openKeywords = setOf("alt", "opt", "par", "critical", "break", "loop", "group", "rect")
        val dividerKeywords = setOf("else", "and", "option")
        var depth = 0
        out.lines().forEach { raw ->
            val line = raw.trim()
            val firstWord = line.substringBefore(' ')
            when {
                line == "end" -> {
                    assertTrue(depth > 0, "an 'end' with no open fragment bracket; got:\n$out")
                    depth--
                }
                firstWord in openKeywords && (line == firstWord || line.startsWith("$firstWord ")) -> depth++
                firstWord in dividerKeywords && (line == firstWord || line.startsWith("$firstWord ")) ->
                    assertTrue(
                        depth > 0,
                        "a divider line ('$line') must sit strictly inside an open fragment bracket, never after its 'end'; got:\n$out",
                    )
                else -> Unit
            }
        }
        assertEquals(0, depth, "every fragment bracket must close by the end of the document; got:\n$out")
    }

    @Test
    fun altWithOneElseOperandEmitsElseGuardBetweenTheTwoBranchesInBothDialects() {
        val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
        val fragment = Seq3Fragment(
            "f1", Seq3FragmentKind.ALT, "cond0", listOf("m1", "m2"),
            elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2")),
        )
        val document = doc(messages, fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        val altOpen = mermaid.indexOf("alt cond0")
        val elseLine = mermaid.indexOf("else cond1")
        val branch0 = mermaid.indexOf("branch0")
        val branch1 = mermaid.indexOf("branch1")
        val end = mermaid.indexOf("    end\n", elseLine)
        assertTrue(altOpen in 0 until branch0, "'alt' must open before the first branch; got:\n$mermaid")
        assertTrue(branch0 in 0 until elseLine, "the 'else' divider must come after the first branch; got:\n$mermaid")
        assertTrue(elseLine in 0 until branch1, "the 'else' divider must come before the second branch; got:\n$mermaid")
        assertTrue(branch1 in 0 until end, "the second branch must sit inside the bracket, before 'end'; got:\n$mermaid")
        assertValidFragmentBracketNesting(mermaid)

        val plantUml = document.toPlantUml()
        val altOpenP = plantUml.indexOf("alt cond0")
        val elseLineP = plantUml.indexOf("else cond1")
        val branch0P = plantUml.indexOf("branch0")
        val branch1P = plantUml.indexOf("branch1")
        val endP = plantUml.indexOf("end\n", elseLineP)
        assertTrue(altOpenP in 0 until branch0P, "got:\n$plantUml")
        assertTrue(branch0P in 0 until elseLineP, "got:\n$plantUml")
        assertTrue(elseLineP in 0 until branch1P, "got:\n$plantUml")
        assertTrue(branch1P in 0 until endP, "got:\n$plantUml")
        assertValidFragmentBracketNesting(plantUml)
    }

    @Test
    fun parEmitsAndInMermaidButElseInPlantUml() {
        // The whole point of this test: PAR is the one kind where the two dialects genuinely
        // disagree on the divider KEYWORD itself, not just on escaping — see
        // mermaidFragmentDividerLine/plantUmlFragmentDividerLine's shared header comment.
        val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
        val fragment = Seq3Fragment(
            "f1", Seq3FragmentKind.PAR, "cond0", listOf("m1", "m2"),
            elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2")),
        )
        val document = doc(messages, fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains("    and cond1\n"), "PAR's Mermaid divider must be 'and', not 'else'; got:\n$mermaid")
        assertFalse(mermaid.contains("else"), "got:\n$mermaid")
        assertValidFragmentBracketNesting(mermaid)

        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains("else cond1\n"), "PAR's PlantUML divider must be 'else' — PlantUML has no 'and'; got:\n$plantUml")
        assertFalse(plantUml.contains("\nand "), "got:\n$plantUml")
        assertValidFragmentBracketNesting(plantUml)
    }

    @Test
    fun criticalEmitsOptionInMermaidAndNoDividerAtAllInPlantUml() {
        val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
        val fragment = Seq3Fragment(
            "f1", Seq3FragmentKind.CRITICAL, "cond0", listOf("m1", "m2"),
            elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2")),
        )
        val document = doc(messages, fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains("    option cond1\n"), "CRITICAL's Mermaid divider must be 'option'; got:\n$mermaid")
        assertValidFragmentBracketNesting(mermaid)

        val plantUml = document.toPlantUml()
        assertFalse(
            plantUml.contains("cond1"),
            "PlantUML has NO divider syntax for CRITICAL at all — the second operand's guard must not appear anywhere; got:\n$plantUml",
        )
        assertTrue(
            plantUml.contains("branch0") && plantUml.contains("branch1"),
            "both messages must still be emitted, only the branch label is lost; got:\n$plantUml",
        )
        assertValidFragmentBracketNesting(plantUml)
    }

    @Test
    fun aDividerOnACrossingFragmentIsClampedAwayRatherThanEmittedAfterEnd() {
        // X spans m1..m2 (emission indices 0..1). Y spans m2..m4 (raw indices 1..3). X and Y
        // CROSS rather than nest (Y starts before X ends but ends after X does), so
        // normalizedBrackets clamps Y's bracket end down to X's own end (index 1) instead of Y's
        // raw end (index 3) — see Seq3Emitters.kt's normalizedBrackets/operandDividersByAnchor
        // "THE TRAP" doc. Y's operand anchors at m4 (raw index 3): past the CLAMPED range (1..1),
        // so its divider must be dropped, never emitted after Y's own (index-1) 'end'.
        val messages = (1..4).map { i -> message(id = "m$i", label = "step$i", occurrences = listOf(occurrence(i, "step$i"))) }
        val x = Seq3Fragment("x", Seq3FragmentKind.ALT, "xGuard0", listOf("m1", "m2"))
        val y = Seq3Fragment(
            "y", Seq3FragmentKind.ALT, "yGuard0", listOf("m2", "m3", "m4"),
            elseOperands = listOf(Seq3Operand("yOp", "yGuard1", startsAtMessageId = "m4")),
        )
        val document = doc(messages, fragments = listOf(x, y))

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains("yGuard0"), "sanity check: Y's own bracket (operand zero) must still open; got:\n$mermaid")
        assertFalse(
            mermaid.contains("yGuard1"),
            "the operand's anchor (m4, raw index 3) falls outside Y's CLAMPED range (1..1) once X and Y cross — " +
                "its divider must be dropped, not emitted after Y's 'end'; got:\n$mermaid",
        )
        assertValidFragmentBracketNesting(mermaid)

        val plantUml = document.toPlantUml()
        assertFalse(plantUml.contains("yGuard1"), "same clamp must hold in PlantUML; got:\n$plantUml")
        assertValidFragmentBracketNesting(plantUml)
    }

    @Test
    fun aDanglingOperandAnchorDropsItsDividerWithoutFailingTheEmission() {
        val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
        val fragment = Seq3Fragment(
            "f1", Seq3FragmentKind.ALT, "cond0", listOf("m1", "m2"),
            // Names no message in the document at all — the Seq3Delay dangling-anchor contract
            // (Seq3Operand's own doc) says this drops THIS divider, not the whole fragment/emission.
            elseOperands = listOf(Seq3Operand("op1", "ghostGuard", startsAtMessageId = "does-not-exist")),
        )
        val document = doc(messages, fragments = listOf(fragment))

        val mermaid = document.toMermaid()
        assertFalse(mermaid.contains("ghostGuard"), "a stale anchor must drop its divider silently; got:\n$mermaid")
        assertTrue(mermaid.contains("branch0") && mermaid.contains("branch1"), "the rest of the document must still emit normally; got:\n$mermaid")
        assertValidFragmentBracketNesting(mermaid)

        val plantUml = document.toPlantUml()
        assertFalse(plantUml.contains("ghostGuard"), "got:\n$plantUml")
        assertValidFragmentBracketNesting(plantUml)
    }

    @Test
    fun optAndLoopEmitNoDividerEvenWhenOperandsArePresent() {
        // UML gives OPT/LOOP exactly one operand (no 'else'/'and'/'option') — see
        // Seq3Fragment.elseOperands' own doc on why a stray entry (e.g. left behind by a kind
        // change away from ALT/PAR/CRITICAL and back) is preserved, not cleared, but must still
        // never be RENDERED for a kind UML doesn't allow it on.
        listOf(Seq3FragmentKind.OPT, Seq3FragmentKind.LOOP).forEach { kind ->
            val messages = listOf(message(id = "m1", label = "branch0"), message(id = "m2", label = "branch1"))
            val fragment = Seq3Fragment(
                "f1", kind, "cond0", listOf("m1", "m2"),
                elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2")),
            )
            val document = doc(messages, fragments = listOf(fragment))

            val mermaid = document.toMermaid()
            assertFalse(mermaid.contains("cond1"), "$kind must emit no divider even though elseOperands is non-empty; got:\n$mermaid")
            assertValidFragmentBracketNesting(mermaid)

            val plantUml = document.toPlantUml()
            assertFalse(plantUml.contains("cond1"), "$kind must emit no divider even though elseOperands is non-empty; got:\n$plantUml")
            assertValidFragmentBracketNesting(plantUml)
        }
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

    // ── WP15 Part 1: measured elapsed tag ───────────────────────────────────────────────────────

    @Test
    fun showElapsedFalseLeavesTheEmittedTextUnprefixedInBothDialects() {
        val occ1 = Seq3Occurrence(entryId = 1, timestampMillis = 1_000L, rawTimestamp = "10:00:01.000", pid = 0, tid = 0, level = 'I', text = "first")
        val occ2 = Seq3Occurrence(entryId = 2, timestampMillis = 1_500L, rawTimestamp = "10:00:01.500", pid = 0, tid = 0, level = 'I', text = "second")
        val messages = listOf(
            message("m1", occurrences = listOf(occ1), label = "first"),
            message("m2", occurrences = listOf(occ2), label = "second"),
        )
        // showElapsed defaults false — the default-off guarantee.
        val document = doc(messages)

        assertTrue(document.toMermaid().contains(": second"), "got:\n${document.toMermaid()}")
        assertFalse(document.toMermaid().contains("[+"), "got:\n${document.toMermaid()}")
        assertTrue(document.toPlantUml().contains(": second"), "got:\n${document.toPlantUml()}")
        assertFalse(document.toPlantUml().contains("[+"), "got:\n${document.toPlantUml()}")
    }

    @Test
    fun showElapsedTruePrefixesEachDrawnCallWithTheMeasuredGapInBothDialects() {
        val occ1 = Seq3Occurrence(entryId = 1, timestampMillis = 1_000L, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "first")
        val occ2 = Seq3Occurrence(entryId = 2, timestampMillis = 1_140L, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "second")
        val messages = listOf(
            message("m1", occurrences = listOf(occ1), label = "first"),
            message("m2", occurrences = listOf(occ2), label = "second"),
        )
        val document = doc(messages).copy(showElapsed = true)

        assertTrue(document.toMermaid().contains(": first"), "the first drawn row has no predecessor; got:\n${document.toMermaid()}")
        assertTrue(document.toMermaid().contains(": [+0.140] second"), "got:\n${document.toMermaid()}")
        assertTrue(document.toPlantUml().contains(": first"), "got:\n${document.toPlantUml()}")
        assertTrue(document.toPlantUml().contains(": [+0.140] second"), "got:\n${document.toPlantUml()}")
    }

    @Test
    fun nullTimestampNeighbourSuppressesTheElapsedTagInEmittedText() {
        // Rule 1, exercised through the emitted text rather than layout row geometry — see
        // Seq3LayoutTest's own test of the identical rule for the full reasoning.
        val occ1 = Seq3Occurrence(entryId = 1, timestampMillis = 1_000L, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "first")
        val occ2 = Seq3Occurrence(entryId = 2, timestampMillis = null, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "brief")
        val occ3 = Seq3Occurrence(entryId = 3, timestampMillis = 5_000L, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "third")
        val messages = listOf(
            message("m1", occurrences = listOf(occ1), label = "first"),
            message("m2", occurrences = listOf(occ2), label = "brief"),
            message("m3", occurrences = listOf(occ3), label = "third"),
        )
        val document = doc(messages).copy(showElapsed = true)

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains(": brief"), "m2's own timestamp is null: no tag; got:\n$mermaid")
        assertTrue(
            mermaid.contains(": third"),
            "must not reach back past the null m2 row to m1's real 1_000L timestamp; got:\n$mermaid",
        )
        assertFalse(mermaid.contains("[+"), "no elapsed tag should have been produced anywhere in this document; got:\n$mermaid")
    }

    @Test
    fun elapsedTagIsByteIdenticalAcrossTheLayoutRowLabelAndBothEmittedDialects() {
        // Same parity shape as theSamePrefixIsProducedByTheLayoutRowLabelAndBothEmittedDialects
        // above, extended to the elapsed tag — canvas, Mermaid and PlantUML all compose it through
        // the same shared seq3PrefixedLabel call (Seq3LabelSummary.kt), so they can never quietly
        // disagree about its content.
        val occ1 = Seq3Occurrence(entryId = 1, timestampMillis = 1_000L, rawTimestamp = "10:00:01.000", pid = 0, tid = 0, level = 'I', text = "first")
        val occ2 = Seq3Occurrence(entryId = 2, timestampMillis = 1_140L, rawTimestamp = "10:00:01.140", pid = 0, tid = 0, level = 'I', text = "second")
        val messages = listOf(
            message("m1", occurrences = listOf(occ1), label = "first"),
            message("m2", occurrences = listOf(occ2), label = "second"),
        )
        val document = doc(messages).copy(showElapsed = true)

        val layout = layoutSeq3(document, Seq3LayoutOptions(FixedWidthMetrics()))
        val layoutLabels = layout.rows.filterIsInstance<Seq3ArrowRow>().map { it.label }
        assertEquals(listOf("first", "[+0.140] second"), layoutLabels, "canvas row labels")

        assertTrue(document.toPlantUml().contains(": [+0.140] second"), "got:\n${document.toPlantUml()}")
        // Mermaid escapes '#'/':' but never '+' or '.', so the elapsed tag survives unescaped.
        assertTrue(document.toMermaid().contains(": [+0.140] second"), "got:\n${document.toMermaid()}")
    }

    // ── WP16: the collapsed row's own internal span ─────────────────────────────────────────────
    //
    // Mirrors Seq3LayoutTest's own WP16 section — see that file's header comment for the full
    // gap-vs-span reasoning; both dialects compose the tag through the same shared
    // seq3PrefixedLabel call (Seq3LabelSummary.kt), so they can never disagree on it either.

    private fun spannedOccurrences(count: Int, startMillis: Long, stepMillis: Long) =
        (0 until count).map { i ->
            Seq3Occurrence(entryId = i + 1, timestampMillis = startMillis + i * stepMillis, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "repeated")
        }

    @Test
    fun collapseAboveAboveThresholdEmitsTheInternalSpanInsteadOfTheGapInBothDialects() {
        val occs = spannedOccurrences(5, startMillis = 2_010L, stepMillis = 10L) // 2_010L .. 2_050L
        val messages = listOf(
            message("m1", occurrences = listOf(occurrence(0, "prev")), label = "prev", repeat = Seq3Repeat.EVERY),
            message("m2", occurrences = occs, label = "repeated", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3),
        )
        val document = doc(messages).copy(showElapsed = true)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        assertTrue(mermaid.contains(": [over 40ms] repeated"), "got:\n$mermaid")
        assertTrue(plantUml.contains(": [over 40ms] repeated"), "got:\n$plantUml")
        assertFalse(mermaid.contains("[+"), "gap and span must never both show; got:\n$mermaid")
        assertFalse(plantUml.contains("[+"), "gap and span must never both show; got:\n$plantUml")
    }

    @Test
    fun collapseAboveOccurrencesSharingOneTimestampEmitsAZeroishSpanNotACrashInBothDialects() {
        val occs = spannedOccurrences(5, startMillis = 3_000L, stepMillis = 0L) // every occurrence at the same instant
        val messages = listOf(message("m1", occurrences = occs, label = "repeated", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3))
        val document = doc(messages).copy(showElapsed = true)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        assertTrue(mermaid.contains(": [over 0ms] repeated"), "got:\n$mermaid")
        assertTrue(plantUml.contains(": [over 0ms] repeated"), "got:\n$plantUml")
    }

    @Test
    fun collapseAboveBelowThresholdEmitsOrdinaryGapTagsNotASpanInBothDialects() {
        val occs = spannedOccurrences(2, startMillis = 2_000L, stepMillis = 10L)
        val messages = listOf(
            message("m1", occurrences = listOf(occurrence(0, "prev")), label = "prev", repeat = Seq3Repeat.EVERY),
            message("m2", occurrences = occs, label = "repeated", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3),
        )
        val document = doc(messages).copy(showElapsed = true)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        assertFalse(mermaid.contains("over"), "below threshold, every occurrence draws its own row with an ordinary gap; got:\n$mermaid")
        assertFalse(plantUml.contains("over"), "got:\n$plantUml")
        assertTrue(mermaid.contains("[+"), "an ordinary gap tag must still show; got:\n$mermaid")
        assertTrue(plantUml.contains("[+"), "got:\n$plantUml")
    }

    @Test
    fun showElapsedFalseEmitsNeitherGapNorSpanForACollapsedRowAboveThresholdInBothDialects() {
        val occs = spannedOccurrences(5, startMillis = 2_010L, stepMillis = 10L)
        val messages = listOf(message("m1", occurrences = occs, label = "repeated", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3))
        // showElapsed defaults false — the default-off guarantee, reconfirmed for the span branch.
        val document = doc(messages)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        assertTrue(mermaid.contains(": repeated"), "got:\n$mermaid")
        assertFalse(mermaid.contains("[over") || mermaid.contains("[+"), "got:\n$mermaid")
        assertTrue(plantUml.contains(": repeated"), "got:\n$plantUml")
        assertFalse(plantUml.contains("[over") || plantUml.contains("[+"), "got:\n$plantUml")
    }

    @Test
    fun collapseAboveOccurrencesWithNullTimestampsEmitNoSpanTagInBothDialects() {
        val occs = (1..5).map { i ->
            Seq3Occurrence(entryId = i, timestampMillis = null, rawTimestamp = "", pid = 0, tid = 0, level = 'I', text = "repeated")
        }
        val messages = listOf(message("m1", occurrences = occs, label = "repeated", repeat = Seq3Repeat.COLLAPSE_ABOVE, repeatThreshold = 3))
        val document = doc(messages).copy(showElapsed = true)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        assertTrue(mermaid.contains(": repeated"), "neither endpoint has a real timestamp: no tag at all; got:\n$mermaid")
        assertFalse(mermaid.contains("[over") || mermaid.contains("[+"), "got:\n$mermaid")
        assertTrue(plantUml.contains(": repeated"), "got:\n$plantUml")
        assertFalse(plantUml.contains("[over") || plantUml.contains("[+"), "got:\n$plantUml")
    }

    // ── WP15 Part 2: no literal {slot} on a NOTE or unresolved-target row ───────────────────────

    @Test
    fun noteMessageWithCapturesNeverEmitsALiteralBraceTokenInEitherDialect() {
        val match = Seq3Match(tag = "A", template = "state={state}", captures = listOf(Seq3Capture("state", Seq3CaptureSource.NAMED_VALUE)))
        val note = message(
            kind = Seq3Kind.NOTE, to = null, label = "state={state}", match = match,
            occurrences = listOf(occurrence(1, "state=RUNNING", mapOf("state" to "RUNNING"))),
        )
        val mermaid = doc(listOf(note)).toMermaid()
        val plantUml = doc(listOf(note)).toPlantUml()

        assertFalse(mermaid.contains("{"), "got:\n$mermaid")
        assertTrue(mermaid.contains("RUNNING"), "got:\n$mermaid")
        assertFalse(plantUml.contains("{"), "got:\n$plantUml")
        assertTrue(plantUml.contains("RUNNING"), "got:\n$plantUml")
    }

    @Test
    fun unresolvedStubMessageWithCapturesNeverEmitsALiteralBraceTokenInEitherDialect() {
        val match = Seq3Match(tag = "A", template = "deviceKey={deviceKey}", captures = listOf(Seq3Capture("deviceKey", Seq3CaptureSource.NAMED_VALUE)))
        val unresolved = message(
            to = null, label = "deviceKey={deviceKey}", match = match,
            occurrences = listOf(occurrence(1, "deviceKey=abc123", mapOf("deviceKey" to "abc123"))),
        )
        val mermaid = doc(listOf(unresolved)).toMermaid()
        val plantUml = doc(listOf(unresolved)).toPlantUml()

        assertFalse(mermaid.contains("{"), "got:\n$mermaid")
        assertTrue(mermaid.contains("abc123"), "got:\n$mermaid")
        assertFalse(plantUml.contains("{"), "got:\n$plantUml")
        assertTrue(plantUml.contains("abc123"), "got:\n$plantUml")
    }

    // ── Activation bars (WP3) ───────────────────────────────────────────────────────────────────

    @Test
    fun everyActivateHasAMatchingDeactivateInBothDialects() {
        val messages = listOf(
            message(id = "m1", from = "A", to = "B", kind = Seq3Kind.CALL, label = "call1"),
            message(id = "m2", from = "B", to = "A", kind = Seq3Kind.RETURN, label = "return1"),
            // Deliberately unmatched: no RETURN ever closes this one. Seq3Activation.kt's rule 1
            // says an unmatched call still closes at a concrete fallback index rather than being
            // left open, specifically so this case cannot emit a lone "activate" with no
            // "deactivate" — which Mermaid rejects as a parse error.
            message(id = "m3", from = "A", to = "B", kind = Seq3Kind.CALL, label = "call2"),
        )
        val document = doc(messages).copy(showActivations = true)

        listOf(document.toMermaid() to "mermaid", document.toPlantUml() to "plantuml").forEach { (out, dialect) ->
            // Counting trap: the string "deactivate" contains "activate" as a substring, so a
            // naive `out.count("activate")` over-counts. Count whole LINES that OPEN with each
            // keyword instead — a "deactivate ..." line never starts with "activate ", so this
            // sidesteps the substring trap entirely rather than needing a subtraction.
            val activateCount = out.lines().count { it.trim().startsWith("activate ") }
            val deactivateCount = out.lines().count { it.trim().startsWith("deactivate ") }
            assertTrue(activateCount > 0, "$dialect: sanity check that activation lines were actually emitted; got:\n$out")
            assertEquals(
                deactivateCount, activateCount,
                "$dialect: every activate must have a matching deactivate (incl. unmatched calls); broken Mermaid otherwise; got:\n$out",
            )
        }
    }

    @Test
    fun aBalancedCallReturnPairEmitsActivateAfterTheCallAndDeactivateAfterTheReturnInBothDialects() {
        val messages = listOf(
            message(id = "m1", from = "A", to = "B", kind = Seq3Kind.CALL, label = "callit"),
            message(id = "m2", from = "B", to = "A", kind = Seq3Kind.RETURN, label = "returnit"),
        )
        val document = doc(messages).copy(showActivations = true)

        val mermaid = document.toMermaid()
        assertTrue(mermaid.contains("callit\n    activate B\n"), "activate must follow the call line; got:\n$mermaid")
        assertTrue(mermaid.contains("returnit\n    deactivate B\n"), "deactivate must follow the return line; got:\n$mermaid")

        val plantUml = document.toPlantUml()
        assertTrue(plantUml.contains("callit\nactivate B\n"), "activate must follow the call line; got:\n$plantUml")
        assertTrue(plantUml.contains("returnit\ndeactivate B\n"), "deactivate must follow the return line; got:\n$plantUml")
    }

    @Test
    fun showActivationsFalseEmitsNeitherKeywordInEitherDialect() {
        val messages = listOf(
            message(id = "m1", from = "A", to = "B", kind = Seq3Kind.CALL, label = "callit"),
            message(id = "m2", from = "B", to = "A", kind = Seq3Kind.RETURN, label = "returnit"),
        )
        // showActivations defaults false — this is the default-off guarantee that keeps every
        // pre-WP3 test's expected output byte-identical.
        val document = doc(messages)

        val mermaid = document.toMermaid()
        val plantUml = document.toPlantUml()
        // "activate" as a substring also rules out "deactivate" (which contains it), so one
        // check per dialect covers both keywords.
        assertFalse(mermaid.contains("activate"), "showActivations=false must emit no activation keyword at all; got:\n$mermaid")
        assertFalse(plantUml.contains("activate"), "showActivations=false must emit no activation keyword at all; got:\n$plantUml")
    }

    @Test
    fun deactivateBeforeActivateWhenASpanClosesAndAnotherOpensAtTheSameEmissionIndex() {
        val c = Seq3Lifeline("C", "Lifeline C", setOf("C"), 2)
        val messages = listOf(
            // B is never RETURNed to, so its span falls back (Seq3Activation.kt rule 1) to
            // closing at the LAST row that touches B — which is the very next message, because B
            // is also the CALLER there. That same row's CALL simultaneously opens a brand-new
            // span on C. So one emission index is both an endIndex (B closing) and a startIndex
            // (C opening) — appendActivationLines' own doc says deactivate must still be written
            // first, or the export would nest C's new bar inside B's just-closed one.
            message(id = "m1", from = "A", to = "B", kind = Seq3Kind.CALL, label = "toB"),
            message(id = "m2", from = "B", to = "C", kind = Seq3Kind.CALL, label = "toC"),
        )
        val document = Seq3Document(lifelines = listOf(a, b, c), messages = messages, showActivations = true)

        listOf(document.toMermaid() to "mermaid", document.toPlantUml() to "plantuml").forEach { (out, dialect) ->
            val deactivateIdx = out.indexOf("deactivate B")
            val activateIdx = out.indexOf("activate C")
            assertTrue(deactivateIdx >= 0, "$dialect: expected B's fallback-closed span; got:\n$out")
            assertTrue(activateIdx >= 0, "$dialect: expected C's freshly opened span; got:\n$out")
            assertTrue(
                deactivateIdx < activateIdx,
                "$dialect: closing an old bar must be written before opening a new one at the same emission index; got:\n$out",
            )
        }
    }

    /** Walks [out]'s emitted `activate`/`deactivate` lines in order, maintaining a per-alias open
     *  count exactly like Mermaid's own activation stack would, and asserts:
     *   - the count for any alias never goes negative (a `deactivate` with nothing open — the
     *     Mermaid parse failure `appendActivationLines`' doc exists to prevent), and
     *   - every alias that appeared ends back at zero (no bar left dangling open).
     *  Deliberately NOT keyword counting (see `everyActivateHasAMatchingDeactivateInBothDialects`
     *  just above): equal totals do not imply valid ORDER — a document can have exactly as many
     *  `deactivate` lines as `activate` lines and still open a `deactivate` before its matching
     *  `activate` exists, which is precisely the pre-fix bug this test is here to catch. */
    private fun assertValidActivationSequence(out: String, dialect: String) {
        val openCount = mutableMapOf<String, Int>()
        for (line in out.lines()) {
            val trimmed = line.trim()
            val activate = trimmed.startsWith("activate ")
            val deactivate = trimmed.startsWith("deactivate ")
            if (!activate && !deactivate) continue
            val alias = if (activate) trimmed.removePrefix("activate ") else trimmed.removePrefix("deactivate ")
            if (activate) {
                openCount[alias] = (openCount[alias] ?: 0) + 1
            } else {
                val depth = (openCount[alias] ?: 0) - 1
                assertTrue(
                    depth >= 0,
                    "$dialect: 'deactivate $alias' with nothing open on $alias — Mermaid tracks activation " +
                        "as a real stack and rejects exactly this; got:\n$out",
                )
                openCount[alias] = depth
            }
        }
        openCount.forEach { (alias, depth) ->
            assertEquals(0, depth, "$dialect: $alias ended with an unclosed activation bar (depth=$depth); got:\n$out")
        }
    }

    @Test
    fun emittedActivationsFormAValidOpenCloseSequenceInBothDialects() {
        val c = Seq3Lifeline("C", "Lifeline C", setOf("C"), 2)
        val d = Seq3Lifeline("D", "Lifeline D", setOf("D"), 3)
        val e = Seq3Lifeline("E", "Lifeline E", setOf("E"), 4)
        val messages = listOf(
            // Nested call/return pair: C's bar (opened+closed here) nests entirely inside B's.
            message(id = "m1", from = "A", to = "B", kind = Seq3Kind.CALL, label = "call1"),
            message(id = "m2", from = "B", to = "C", kind = Seq3Kind.CALL, label = "call2"),
            message(id = "m3", from = "C", to = "B", kind = Seq3Kind.RETURN, label = "return1"),
            message(id = "m4", from = "B", to = "A", kind = Seq3Kind.RETURN, label = "return2"),
            // Trailing unmatched call: nothing touches C afterward, so Seq3Activation.kt's rule 1
            // closes its span on its OWN row — the startIndex == endIndex case this test exists
            // for (this is the report's own "A->>C: unmatchedCall" example, verbatim).
            message(id = "m5", from = "A", to = "C", kind = Seq3Kind.CALL, label = "unmatchedCall"),
            // A span opened on an EARLIER row (D's) falls back to closing HERE, at the same
            // emission index where a DIFFERENT span (E's) opens — D is unmatched and this row is
            // its own last touch (it is the sender); E then never sees another row either, so E's
            // own span is itself zero-length. One index exercising both "close an older span,
            // then open a new one" and "open-then-immediately-close" together.
            message(id = "m6", from = "B", to = "D", kind = Seq3Kind.CALL, label = "call3"),
            message(id = "m7", from = "D", to = "E", kind = Seq3Kind.CALL, label = "call4"),
        )
        val document = Seq3Document(lifelines = listOf(a, b, c, d, e), messages = messages, showActivations = true)

        listOf(document.toMermaid() to "mermaid", document.toPlantUml() to "plantuml").forEach { (out, dialect) ->
            assertValidActivationSequence(out, dialect)
        }
    }

    private class FixedWidthMetrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }
}

