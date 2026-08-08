package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.toMermaid
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SequenceDef
import com.indagium.ui.mkTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers buildSequenceDiagram's four arrow modes' worth of behavior plus range resolution —
 * see diagram/SeqDiagramBuilder.kt's own doc for the exact contract each of these exercises.
 */
class DiagramBuilderTest {
    // Every test below turns off showElapsed/seqGroupFrames/notesForErrors/collapseRepeats unless
    // it's the one test FOR that option — keeps label/message assertions free of prefix noise and
    // frame/note bookkeeping unrelated tests don't care about.
    private fun plainOptions(
        collapseRepeats: Boolean = false,
        maxMessages: Int = 120,
    ) = DiagramOptions(
        collapseRepeats = collapseRepeats,
        maxMessages = maxMessages,
        showElapsed = false,
        showTimestamps = false,
        seqGroupFrames = false,
        notesForErrors = false,
    )

    // ── TAG_TRANSITION: CALL on tag change, SELF on repeat ──────────────────────────────────

    @Test
    fun tagTransitionEmitsCallOnTagChangeAndSelfOnRepeatWithNoArrowForTheBootstrapEntry() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a1"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b1"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "B", "b2"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "A", "a2"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions()))

        assertEquals(2, diagram.participants.size, "A and B, auto-derived; ${diagram.participants}")
        val aIdx = diagram.participants.indexOfFirst { it.tag == "A" }
        val bIdx = diagram.participants.indexOfFirst { it.tag == "B" }
        assertEquals(
            3, diagram.messages.size,
            "entry 1 only bootstraps the starting lifeline — no arrow for it; ${diagram.messages}",
        )
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
        assertEquals(aIdx, diagram.messages[0].fromIdx)
        assertEquals(bIdx, diagram.messages[0].toIdx)
        assertEquals("b1", diagram.messages[0].label)
        assertEquals(MessageKind.SELF, diagram.messages[1].kind)
        assertEquals(bIdx, diagram.messages[1].fromIdx)
        assertEquals(bIdx, diagram.messages[1].toIdx)
        assertEquals(MessageKind.CALL, diagram.messages[2].kind)
        assertEquals(bIdx, diagram.messages[2].fromIdx)
        assertEquals(aIdx, diagram.messages[2].toIdx)
        assertEquals(4, diagram.scannedEntries)
    }

    // ── Repeat collapsing + digit/hex normalization ─────────────────────────────────────────

    @Test
    fun collapseRepeatsFoldsConsecutiveSameShapeMessagesAndNormalizesDigitsForComparison() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "A", "retry 1"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "A", "retry 2"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "A", "retry 10"),
                LogEntry(5, "10:00:00.400", LogLevel.I, "B", "done"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(collapseRepeats = true)))

        assertEquals(2, diagram.messages.size, "three normalized-identical SELF retries fold into one; ${diagram.messages}")
        val folded = diagram.messages[0]
        assertEquals(MessageKind.SELF, folded.kind)
        assertEquals(3, folded.repeatCount)
        assertEquals("retry 1", folded.label, "the FIRST occurrence's original (non-normalized) label is kept")
        assertEquals(2, folded.entryId, "the FIRST occurrence's entryId is kept")
        assertEquals(MessageKind.CALL, diagram.messages[1].kind)
        assertEquals(1, diagram.messages[1].repeatCount)
    }

    @Test
    fun collapseRepeatsDoesNotFoldNonAdjacentRepeatsAcrossAnInterveningDifferentMessage() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "A", "retry 1"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "A", "something else"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "A", "retry 2"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(collapseRepeats = true)))

        assertEquals(3, diagram.messages.size, "the two 'retry N' SELFs are not adjacent, so they must not fold")
        assertTrue(diagram.messages.all { it.repeatCount == 1 })
    }

    // ── Entry-point / exit-point actors ──────────────────────────────────────────────────────

    @Test
    fun entryPointActorOpensTheFirstArrowAndExitPointActorClosesTheLast() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a1"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b1"),
            ),
        )
        val spec = SeqDiagramSpec(
            participants = listOf(
                DiagramParticipant("user", "User", ParticipantKind.ACTOR, isEntryPoint = true),
                DiagramParticipant("server", "Server", ParticipantKind.ACTOR, isExitPoint = true),
            ),
            options = plainOptions(),
        )
        val diagram = buildSequenceDiagram(tab, spec)

        val userIdx = diagram.participants.indexOfFirst { it.id == "user" }
        val serverIdx = diagram.participants.indexOfFirst { it.id == "server" }
        val aIdx = diagram.participants.indexOfFirst { it.tag == "A" }
        val bIdx = diagram.participants.indexOfFirst { it.tag == "B" }
        assertEquals(3, diagram.messages.size, "entry-point CALL, A->B CALL, exit-point RETURN; ${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
        assertEquals(userIdx, diagram.messages[0].fromIdx)
        assertEquals(aIdx, diagram.messages[0].toIdx)
        assertEquals(bIdx, diagram.messages[1].toIdx)
        assertEquals(MessageKind.RETURN, diagram.messages[2].kind)
        assertEquals(bIdx, diagram.messages[2].fromIdx)
        assertEquals(serverIdx, diagram.messages[2].toIdx)
    }

    // ── SeqGroup frames, including one level of nesting ─────────────────────────────────────

    @Test
    fun seqGroupFramesCoverOneLevelOfNestingMatchingTheOwningSequenceDefsRange() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer start"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "Inner", "inner start"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "Inner", "inner work"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "Inner", "inner end"),
                LogEntry(5, "10:00:00.400", LogLevel.I, "Outer", "outer tail"),
                LogEntry(6, "10:00:00.500", LogLevel.I, "Outer", "outer end"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef(
                        "outer", "outer start", priority = 1, color = Color(0xFF112233.toInt()),
                        tag = "Outer", endMatchText = "outer end", endTag = "Outer",
                    ),
                    SequenceDef(
                        "inner", "inner start", priority = 2, color = Color(0xFF445566.toInt()),
                        tag = "Inner", endMatchText = "inner end", endTag = "Inner",
                    ),
                ),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions().copy(seqGroupFrames = true)))

        assertEquals(2, diagram.frames.size, "one outer frame + one nested inner frame; ${diagram.frames}")
        val outer = diagram.frames.first { it.depth == 0 }
        val inner = diagram.frames.first { it.depth == 1 }
        assertEquals("outer start", outer.label)
        assertEquals(0xFF112233.toInt(), outer.colorArgb, "roundToInt channel packing must round-trip a hand-picked ARGB literal exactly")
        assertEquals(0, outer.firstMsg)
        assertEquals(diagram.messages.lastIndex, outer.lastMsg, "the outer group spans the whole 5-message diagram")
        assertEquals("inner start", inner.label)
        assertEquals(0xFF445566.toInt(), inner.colorArgb)
        assertTrue(inner.firstMsg >= outer.firstMsg && inner.lastMsg <= outer.lastMsg, "the nested frame's range must sit inside the outer frame's")
    }

    // ── maxMessages truncation ────────────────────────────────────────────────────────────────

    @Test
    fun maxMessagesCapsOutputAndSetsTruncated() {
        val entries = (0 until 10).map { i -> LogEntry(i + 1, "10:00:00.%03d".format(i), LogLevel.I, "A", "m$i") }
        val tab = mkTab("t1", "app.log", entries)
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions(maxMessages = 3)),
        )

        assertEquals(3, diagram.messages.size)
        assertTrue(diagram.truncated)
    }

    @Test
    fun belowTheCapTruncatedStaysFalse() {
        val entries = (0 until 3).map { i -> LogEntry(i + 1, "10:00:00.%03d".format(i), LogLevel.I, "A", "m$i") }
        val tab = mkTab("t1", "app.log", entries)
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions(maxMessages = 10)),
        )

        assertTrue(!diagram.truncated)
    }

    // ── Time range, TS_UNKNOWN carry-forward, and the no-parseable-timestamp warning ────────

    @Test
    fun timeRangeCarriesForwardTheLastKnownTimestampForBriefFormatRows() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                // Brief/RAW format: ts == "" (TS_UNKNOWN) — inherits entry 1's 10:00:00.000.
                LogEntry(2, "", LogLevel.I, "A", "b"),
                LogEntry(3, "10:00:01.000", LogLevel.I, "A", "c"),
            ),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(range = DiagramRange.Time("10:00:00.000", "10:00:00.500"), options = plainOptions()),
        )

        assertEquals(2, diagram.scannedEntries, "entry 3's own ts is outside the range; entry 2 inherits entry 1's and is inside it")
        assertTrue(diagram.warnings.isEmpty())
    }

    @Test
    fun timeRangeWithNoParseableTimestampAnywhereInTheTabWarnsRatherThanSilentlyReturningEmpty() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "", LogLevel.I, "A", "a"),
                LogEntry(2, "", LogLevel.I, "A", "b"),
            ),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(range = DiagramRange.Time("10:00:00.000", "10:00:00.500"), options = plainOptions()),
        )

        assertTrue(diagram.messages.isEmpty())
        assertEquals(0, diagram.scannedEntries)
        assertTrue(diagram.warnings.isNotEmpty(), "a hard failure (no parseable ts anywhere) must be reported, not silently empty")
    }

    // resolveTimeRange resolves via a merged two-pointer walk over tab.logData/allVisible rather
    // than a boxed id->ms map (10M-line-scale allocation concern) — this test exercises exactly
    // the case that walk has to get right: a filtered-OUT row (tag B, excluded by the active
    // filter) that still carries a real timestamp forward to a LATER, TS_UNKNOWN, VISIBLE row,
    // while the filtered-out row itself never appears in the result despite its own timestamp
    // being inside the window.
    @Test
    fun timeRangeResolvesCorrectlyWithTsUnknownRowsInterleavedAmongFilteredAndVisibleRows() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                // Tag B is filtered out below, but its own real timestamp must still carry
                // forward into entry 3 — carry-forward runs over the WHOLE tab, not the view.
                LogEntry(2, "10:00:00.150", LogLevel.I, "B", "b"),
                // Brief/RAW-style row (ts == "", TS_UNKNOWN): inherits entry 2's 10:00:00.150,
                // even though entry 2 itself is invisible under the current filter.
                LogEntry(3, "", LogLevel.I, "A", "c"),
                // Real timestamp, but outside the range window below.
                LogEntry(4, "10:00:01.000", LogLevel.I, "A", "d"),
            ),
        ).copy(filter = Filter(activeTags = setOf("A")))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(range = DiagramRange.Time("10:00:00.000", "10:00:00.200"), options = plainOptions()),
        )

        assertEquals(
            2, diagram.scannedEntries,
            "entry 1 (own ts) and entry 3 (inherited from the filtered-out entry 2) are in window; entry 2 is invisible, entry 4 is out of window",
        )
        assertTrue(diagram.warnings.isEmpty())
        // TAG_TRANSITION: entry 1 only bootstraps (no arrow); entry 3, same tag, is a SELF —
        // its presence (and entryId) confirms the TS_UNKNOWN carry-forward actually included it.
        assertEquals(1, diagram.messages.size)
        assertEquals(3, diagram.messages[0].entryId)
        assertEquals("c", diagram.messages[0].label)
    }

    // ── Label flattening: \r must not survive to the emitter ────────────────────────────────

    @Test
    fun aCrlfMessageProducesAGenuinelySingleLineLabelWithNoLineBreakInMermaidOutput() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "line1\r\nline2")),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions()),
        )

        val label = diagram.messages.single().label
        assertFalse(label.contains('\r'), "a bare \\r must not survive collapseWhitespace")
        assertFalse(label.contains('\n'), "a bare \\n must not survive collapseWhitespace")
        assertEquals("line1 line2", label)
        // The regression this guards: mermaidEscape's \r\n/\r -> \n -> <br/> normalization used to
        // turn a surviving \r into a VISIBLE line break in Mermaid specifically (not PlantUML,
        // which only escapes \n) — i.e. a label that was supposedly already flattened would still
        // render differently per dialect. With collapseWhitespace fixed, no <br/> should appear.
        assertFalse(diagram.toMermaid().contains("<br/>"))
    }

    // ── RULES mode: named groups, template substitution, unknown-participant auto-actor,
    //    and fallthrough to TAG_TRANSITION behavior for an unmatched line ──────────────────

    @Test
    fun rulesModeSubstitutesNamedGroupsAutoCreatesUnknownActorsAndFallsThroughForUnmatchedLines() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "App sending to Server"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "Net", "unrelated line"),
            ),
        )
        val rule = DiagramMessageRule(
            id = "r1",
            pattern = "(?<from>\\w+) sending to (?<to>\\w+)",
            fromTemplate = "\${from}",
            toTemplate = "\${to}",
            labelTemplate = "send: \${msg}",
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(mode = ArrowMode.RULES, rules = listOf(rule), options = plainOptions()),
        )

        assertEquals(2, diagram.messages.size)
        val appIdx = diagram.participants.indexOfFirst { it.id == "App" }
        val serverIdx = diagram.participants.indexOfFirst { it.id == "Server" }
        val netIdx = diagram.participants.indexOfFirst { it.tag == "Net" }
        assertTrue(appIdx >= 0 && serverIdx >= 0, "unknown rule participants must be auto-created as actors")
        assertEquals(ParticipantKind.ACTOR, diagram.participants[appIdx].kind)
        assertEquals(appIdx, diagram.messages[0].fromIdx)
        assertEquals(serverIdx, diagram.messages[0].toIdx)
        assertEquals("send: App sending to Server", diagram.messages[0].label)
        assertEquals(
            2, diagram.warnings.count { it.contains("unknown participant") },
            "both 'App' and 'Server' are unrecognized — one warning each; ${diagram.warnings}",
        )
        // The second entry matches no enabled rule — falls through to TAG_TRANSITION behavior,
        // continuing from wherever the rule-driven message left `current` (Server's actor index).
        assertEquals(serverIdx, diagram.messages[1].fromIdx)
        assertEquals(netIdx, diagram.messages[1].toIdx)
        assertEquals("unrelated line", diagram.messages[1].label)
    }

    @Test
    fun rulesModeIgnoresDisabledRulesAndFallsThroughEntirelyWhenNoneAreEnabled() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "sending to X"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "sending to Y"),
            ),
        )
        val rule = DiagramMessageRule(
            id = "r1", pattern = "sending to (?<to>\\w+)", enabled = false,
            fromTemplate = "self", toTemplate = "\${to}", labelTemplate = "\${msg}",
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.RULES, rules = listOf(rule), options = plainOptions()))

        assertTrue(diagram.warnings.isEmpty(), "a disabled rule must never fire, so no unknown-participant warning either")
        assertEquals(1, diagram.messages.size, "entry 1 only bootstraps; entry 2 is the one tag-change CALL")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
    }

    // ── SeqGroupRef range resolves to exactly one group's swallowed entries ─────────────────

    @Test
    fun seqGroupRefRangeResolvesToExactlyThatGroupsSwallowedEntries() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "X", "before"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "Outer", "outer start"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "Outer", "outer end"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "X", "after"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef(
                        "outer", "outer start", priority = 1, color = Color.Red,
                        tag = "Outer", endMatchText = "outer end", endTag = "Outer",
                    ),
                ),
            ),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(range = DiagramRange.SeqGroupRef("sg_outer_2"), options = plainOptions()),
        )

        assertEquals(2, diagram.scannedEntries, "only the two entries the outer group swallows — never 'before'/'after'")
        assertTrue(diagram.participants.none { it.tag == "X" })
    }

    @Test
    fun unknownSeqGroupRefWarnsAndProducesAnEmptyDiagram() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a")))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(range = DiagramRange.SeqGroupRef("sg_missing_99"), options = plainOptions()))

        assertTrue(diagram.messages.isEmpty())
        assertTrue(diagram.warnings.isNotEmpty())
    }

    // ── LINE_PER_MESSAGE: one SELF per entry, no attempt to infer endpoints ─────────────────

    @Test
    fun linePerMessageEmitsOneSelfMessagePerEntry() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a1"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b1"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions()))

        assertEquals(2, diagram.messages.size)
        assertTrue(diagram.messages.all { it.kind == MessageKind.SELF && it.fromIdx == it.toIdx })
    }

    // ── notesForErrors ────────────────────────────────────────────────────────────────────────

    @Test
    fun errorAndAssertLevelEntriesProduceNoteMarks() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start"),
                LogEntry(2, "10:00:00.100", LogLevel.E, "B", "boom"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions().copy(notesForErrors = true)))

        assertEquals(1, diagram.notes.size)
        assertTrue(diagram.notes[0].isError)
        assertEquals(0, diagram.notes[0].afterMsg, "the single CALL message is at index 0")
    }

    @Test
    fun visibleViewRangeRespectsTheTabsCurrentFilter() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b"),
            ),
        ).copy(filter = Filter(activeTags = setOf("A")))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions()))

        assertEquals(1, diagram.scannedEntries, "the filter hides tag B entirely, so the diagram never sees it")
        assertNull(diagram.participants.firstOrNull { it.tag == "B" })
    }
}
