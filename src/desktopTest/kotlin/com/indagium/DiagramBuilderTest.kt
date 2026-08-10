package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramParticipantRepresentation
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramSourceEnrichment
import com.indagium.diagram.DiagramSourceInteraction
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.diagramParticipantCandidates
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
        threadHandoffArrows: Boolean = false,
        showSelfMessages: Boolean = true,
        showSourceInferred: Boolean = true,
    ) = DiagramOptions(
        collapseRepeats = collapseRepeats,
        maxMessages = maxMessages,
        showElapsed = false,
        showTimestamps = false,
        seqGroupFrames = false,
        notesForErrors = false,
        threadHandoffArrows = threadHandoffArrows,
        showSelfMessages = showSelfMessages,
        showSourceInferred = showSourceInferred,
    )

    // ── EVIDENCE_FLOW: SELF unless there is real evidence of a correlation ──────────────────

    @Test
    fun evidenceFlowEmitsSelfEventsWhenNothingCorrelatesAdjacentLines() {
        // This is the direct regression test for the reported bug: three (here four) adjacent
        // lines from unrelated tags must never render as guessed CALLs just because the tag
        // changed — every one of them becomes a SELF event on its own lifeline instead.
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
            4, diagram.messages.size,
            "no bootstrap suppression anymore — every entry with a resolvable lifeline emits; ${diagram.messages}",
        )
        assertTrue(diagram.messages.all { it.kind == MessageKind.SELF }, "no correlation exists, so nothing may be a CALL; ${diagram.messages}")
        assertTrue(diagram.messages.all { it.evidence == MessageEvidence.LOG })
        assertEquals(listOf(aIdx, bIdx, bIdx, aIdx), diagram.messages.map { it.fromIdx })
        assertEquals(listOf("a1", "b1", "b2", "a2"), diagram.messages.map { it.label })
        assertEquals(4, diagram.scannedEntries)
        assertTrue(
            diagram.warnings.any { it.contains("No correlated interactions found") },
            "an all-self diagram must say so, or the feature reads as broken; ${diagram.warnings}",
        )
    }

    @Test
    fun threeUnrelatedAdjacentTagsProduceThreeSelfEventsAndZeroCalls() {
        // Same regression as above, phrased exactly as the task's own reported bug: tags A, B, C
        // adjacent in the log must never read as "A called B, B called C".
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "C", "c"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions()))

        assertEquals(3, diagram.messages.size)
        assertEquals(0, diagram.messages.count { it.kind == MessageKind.CALL }, "zero CALLs; ${diagram.messages}")
        assertEquals(3, diagram.messages.count { it.kind == MessageKind.SELF })
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

        // Under EVIDENCE_FLOW every entry is its own SELF event (no bootstrap suppression, no
        // guessed CALL on the A->B tag change) — three folds into: "start" alone, the three
        // normalized-identical "retry N" SELFs folded into one, then "done" alone.
        assertEquals(3, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.SELF, diagram.messages[0].kind)
        assertEquals("start", diagram.messages[0].label)
        assertEquals(1, diagram.messages[0].repeatCount)
        val folded = diagram.messages[1]
        assertEquals(MessageKind.SELF, folded.kind)
        assertEquals(3, folded.repeatCount)
        assertEquals("retry 1", folded.label, "the FIRST occurrence's original (non-normalized) label is kept")
        assertEquals(2, folded.entryId, "the FIRST occurrence's entryId is kept")
        assertEquals(MessageKind.SELF, diagram.messages[2].kind)
        assertEquals("done", diagram.messages[2].label)
        assertEquals(1, diagram.messages[2].repeatCount)
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

        // All four entries share tag A, so all four are SELF events with no bootstrap suppression
        // — the two 'retry N' SELFs are not adjacent (an unrelated "something else" sits between
        // them), so they must not fold, and neither does "start" with anything.
        assertEquals(4, diagram.messages.size, "${diagram.messages}")
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
        assertEquals(3, diagram.messages.size, "entry-point CALL, B's own SELF event, exit-point RETURN; ${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
        assertEquals(userIdx, diagram.messages[0].fromIdx)
        assertEquals(aIdx, diagram.messages[0].toIdx)
        // A->B is a bare tag change with no correlation evidence, so it is B's own SELF event now
        // (not a guessed CALL) — the entry-point actor's CALL above is the only "real" arrow in.
        assertEquals(MessageKind.SELF, diagram.messages[1].kind)
        assertEquals(bIdx, diagram.messages[1].fromIdx)
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
        // Both scanned entries share tag A, so both are uncorrelated SELF events — the range
        // resolution itself found no problem (no "unparseable timestamp"/"bad bound" warning),
        // just the expected "nothing to correlate" notice.
        assertEquals(1, diagram.warnings.size, "${diagram.warnings}")
        assertTrue(diagram.warnings.single().contains("No correlated interactions found"))
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
        assertEquals(1, diagram.warnings.size, "${diagram.warnings}")
        assertTrue(diagram.warnings.single().contains("No correlated interactions found"))
        // EVIDENCE_FLOW: both entries share tag A, so both are their own SELF event — no bootstrap
        // suppression anymore. Entry 3's presence (and entryId) confirms the TS_UNKNOWN
        // carry-forward actually included it.
        assertEquals(2, diagram.messages.size)
        assertEquals(1, diagram.messages[0].entryId)
        assertEquals("a", diagram.messages[0].label)
        assertEquals(3, diagram.messages[1].entryId)
        assertEquals("c", diagram.messages[1].label)
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
    //    and fallthrough to evidence-only (SELF) behavior for an unmatched line ─────────────

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
        // The second entry matches no enabled rule — falls through to the same evidence-only
        // decision EVIDENCE_FLOW uses (emitEvidenceMessage): no correlation, so it is Net's own
        // SELF event, never a guessed CALL from wherever the rule-driven message left `current`.
        assertEquals(MessageKind.SELF, diagram.messages[1].kind)
        assertEquals(netIdx, diagram.messages[1].fromIdx)
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
        // No bootstrap suppression, and RULES' own fallthrough never guesses a CALL either — both
        // entries are their own SELF event.
        assertEquals(2, diagram.messages.size, "${diagram.messages}")
        assertTrue(diagram.messages.all { it.kind == MessageKind.SELF })
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
        // Entry 1 ("start") is its own SELF event at index 0 (no bootstrap suppression); the
        // error-level "boom" entry is its own SELF event at index 1.
        assertEquals(1, diagram.notes[0].afterMsg, "the error entry's own SELF message is at index 1")
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

    @Test
    fun participantCandidatesAndCoverageUseTheResolvedRangeAndPreserveOtherVsHide() {
        val tab = mkTab(
            "t1", "app.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                LogEntry(2, "10:00:00.100", LogLevel.E, "B", "b"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "C", "c"),
                LogEntry(4, "10:00:00.300", LogLevel.I, "B", "b again"),
                LogEntry(5, "10:00:00.400", LogLevel.I, "D", "outside"),
            ),
        )
        val spec = SeqDiagramSpec(
            range = DiagramRange.Ids(1, 4),
            participants = listOf(
                DiagramParticipant("A", "A", ParticipantKind.TAG, tag = "A"),
                DiagramParticipant("B", "B", ParticipantKind.TAG, tag = "B", representation = DiagramParticipantRepresentation.OTHER),
                DiagramParticipant("C", "C", ParticipantKind.TAG, tag = "C", representation = DiagramParticipantRepresentation.HIDE),
            ),
            options = plainOptions(),
        )

        val candidates = diagramParticipantCandidates(tab, spec).associateBy { it.tag }
        assertEquals(2, candidates.getValue("B").entryCount)
        assertEquals(1, candidates.getValue("B").errorCount)
        assertEquals(DiagramParticipantRepresentation.HIDE, candidates.getValue("C").representation)
        assertNull(candidates["D"], "candidate discovery must not leak rows outside the Id range")

        val diagram = buildSequenceDiagram(tab, spec)
        assertEquals(4, diagram.coverage.scannedEntries)
        assertEquals(1, diagram.coverage.shownEntries)
        assertEquals(2, diagram.coverage.groupedEntries)
        assertEquals(1, diagram.coverage.hiddenEntries)
        assertTrue(diagram.participants.any { it.label == "Other" })
        assertFalse(diagram.participants.any { it.tag == "B" || it.tag == "C" })
        // A's own SELF event, Other's own SELF event (entry 2, grouped), Other's own SELF event
        // again (entry 4, grouped) — no bootstrap suppression, no guessed CALL for the A->Other
        // tag change; hidden C still produces no arrow at all.
        assertEquals(3, diagram.messages.size, "${diagram.messages}")
        assertTrue(diagram.messages.all { it.kind == MessageKind.SELF })
    }

    @Test
    fun candidatesCarryTheDistinctPidsThatLoggedEachTagInRange() {
        val tab = mkTab(
            "t1", "app.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 100),
                LogEntry(2, "10:00:00.100", LogLevel.I, "A", "a", pid = 200),
                LogEntry(3, "10:00:00.200", LogLevel.I, "B", "b", pid = 300),
                LogEntry(4, "10:00:00.300", LogLevel.I, "A", "a", pid = 100),
                LogEntry(5, "10:00:00.400", LogLevel.I, "A", "a", pid = 1),
                LogEntry(6, "10:00:00.500", LogLevel.I, "A", "a", pid = 2),
                LogEntry(7, "10:00:00.600", LogLevel.I, "A", "a", pid = 3),
                LogEntry(8, "10:00:00.700", LogLevel.I, "A", "a", pid = 4),
                LogEntry(9, "10:00:00.800", LogLevel.I, "A", "a", pid = 5),
                LogEntry(10, "10:00:00.900", LogLevel.I, "A", "a", pid = 6),
            ),
        )
        val spec = SeqDiagramSpec(range = DiagramRange.Ids(1, 10), options = plainOptions())

        val candidates = diagramParticipantCandidates(tab, spec).associateBy { it.tag }
        assertEquals(setOf(300), candidates.getValue("B").pids)
        // Nine distinct pids logged tag A (100, 200, 1..6) but the field caps at
        // MAX_CANDIDATE_PIDS = 8 rather than growing unbounded over a 10M-line range.
        assertEquals(8, candidates.getValue("A").pids.size, "distinct A pids: ${candidates.getValue("A").pids}")
    }

    @Test
    fun componentsMergeTagsAndGlobalUnmappedPolicyControlsOther() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "merged"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "C", "unmapped"),
        ))
        val component = DiagramComponent("app", "App", setOf("A", "B"))
        val grouped = buildSequenceDiagram(tab, SeqDiagramSpec(
            components = listOf(component),
            unmappedTagPolicy = UnmappedTagPolicy.GROUP_AS_OTHER,
            options = plainOptions(),
        ))
        assertEquals(2, grouped.participants.size)
        assertEquals("App", grouped.participants.first().label)
        assertEquals(3, grouped.coverage.shownEntries + grouped.coverage.groupedEntries)
        // A and B both merge into the SAME "app" component, and C has no correlation evidence
        // either — every one of the three entries is its own SELF event, never a guessed CALL.
        assertEquals(3, grouped.messages.size, "${grouped.messages}")
        assertTrue(grouped.messages.all { it.kind == MessageKind.SELF })

        val hidden = buildSequenceDiagram(tab, SeqDiagramSpec(components = listOf(component), options = plainOptions()))
        assertEquals(1, hidden.participants.size)
        assertEquals(1, hidden.coverage.hiddenEntries)
    }

    @Test
    fun mirroredActorDuplicatesOnlyNonSelfComponentEdgesAdjacentToOriginal() {
        // A same-thread handoff is real evidence of a CALL from A to B — needed here so there is a
        // non-self edge for the mirrored actor to duplicate at all (see DiagramActor's own updated
        // doc: EVIDENCE_FLOW's whole point is that a component with no EVIDENCED arrow touching it
        // has nothing to mirror, which is correct-by-construction, not a bug).
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start", pid = 7, tid = 42),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "handoff", pid = 7, tid = 42),
        ))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
            actors = listOf(DiagramActor("client", "Client", "a")),
            options = plainOptions(threadHandoffArrows = true),
        ))
        // Entry 1 is ALSO its own SELF event now (no bootstrap suppression) ahead of the handoff
        // CALL and its mirror.
        assertEquals(3, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.SELF, diagram.messages[0].kind)
        assertEquals(MessageKind.CALL, diagram.messages[1].kind)
        assertEquals(MessageEvidence.THREAD_HANDOFF, diagram.messages[1].evidence)
        assertEquals(MessageEvidence.ACTOR_MIRROR, diagram.messages[2].evidence)
        assertEquals(diagram.messages[1].toIdx, diagram.messages[2].toIdx)
    }

    @Test
    fun enabledSourceEnrichmentAddsDashedEvidenceBackedCallAndDeclaredReturn() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke")))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(),
            ),
            resolveSourceInteractions = { listOf(DiagramSourceInteraction("a", "b", "B.work()", "String")) },
        )
        // The single scanned entry is ALSO its own uncorrelated SELF event now (no bootstrap
        // suppression) — evidence LOG — ahead of the two source-inferred runtime messages.
        assertEquals(
            listOf(MessageEvidence.LOG, MessageEvidence.SOURCE_INFERRED, MessageEvidence.SOURCE_INFERRED),
            diagram.messages.map { it.evidence },
        )
        assertEquals(MessageKind.RETURN, diagram.messages.last().kind)
        assertEquals("String", diagram.messages.last().label)
        assertEquals(1, diagram.activationSpans.size)
        assertTrue(diagram.toMermaid().contains("-->>"))
    }

    @Test
    fun sourceEnrichmentStopsResolverWorkOnceRuntimeOutputReachesTheMessageCap() {
        val maxMessages = 12
        val tab = mkTab(
            "t1", "app.log",
            (1..10_000).map { id ->
                LogEntry(id, "10:00:00.000", LogLevel.I, "A", "unique source site $id")
            },
        )
        var resolverCalls = 0
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(DiagramComponent("a", "A", setOf("A"))),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(maxMessages = maxMessages),
            ),
            resolveSourceInteractions = {
                resolverCalls++
                listOf(DiagramSourceInteraction("a", "remote", "should not be resolved"))
            },
        )

        assertTrue(resolverCalls <= maxMessages, "source resolver must remain bounded by output capacity")
        assertEquals(0, resolverCalls, "runtime interactions already consume the available output budget")
        assertTrue(diagram.truncated)
        assertTrue(diagram.warnings.any { it.contains("Source enrichment") })
    }

    // ── A matched rule remains genuine evidence ──────────────────────────────────────────────

    @Test
    fun aMatchedRuleStillProducesCallWithRuleEvidence() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "App sending to Server")))
        val rule = DiagramMessageRule(
            id = "r1", pattern = "(?<from>\\w+) sending to (?<to>\\w+)",
            fromTemplate = "\${from}", toTemplate = "\${to}", labelTemplate = "\${msg}",
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.RULES, rules = listOf(rule), options = plainOptions()))

        val message = diagram.messages.single()
        assertEquals(MessageKind.CALL, message.kind)
        assertEquals(MessageEvidence.RULE, message.evidence)
    }

    // ── Thread-handoff correlation (opt-in) ──────────────────────────────────────────────────

    @Test
    fun sameThreadHandoffYieldsCallOnlyWhenTheOptionIsOnAndSelfWhenOff() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 7, tid = 42),
                LogEntry(2, "10:00:00.050", LogLevel.I, "B", "b", pid = 7, tid = 42),
            ),
        )
        val on = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertEquals(MessageKind.CALL, on.messages[1].kind, "${on.messages}")
        assertEquals(MessageEvidence.THREAD_HANDOFF, on.messages[1].evidence)

        val off = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = false)))
        assertEquals(MessageKind.SELF, off.messages[1].kind, "${off.messages}")
    }

    @Test
    fun zeroPidAndTidNeverCorrelateEvenWithTheOptionOn() {
        // LogEntry.pid/tid both default to 0 — brief/RAW logcat carries neither. Without this
        // guard, an entire such log would correlate into one fake thread, reproducing the exact
        // bug this mode exists to remove.
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                LogEntry(2, "10:00:00.050", LogLevel.I, "B", "b"),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertEquals(MessageKind.SELF, diagram.messages[1].kind, "${diagram.messages}")
    }

    @Test
    fun aDifferentTidWithTheSamePidNeverCorrelates() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 7, tid = 42),
                LogEntry(2, "10:00:00.050", LogLevel.I, "B", "b", pid = 7, tid = 43),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertEquals(MessageKind.SELF, diagram.messages[1].kind, "${diagram.messages}")
    }

    @Test
    fun aTimestampGapBeyondTheBoundNeverCorrelates() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 7, tid = 42),
                // A full second later — far past the ~250ms bound.
                LogEntry(2, "10:00:01.000", LogLevel.I, "B", "b", pid = 7, tid = 42),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertEquals(MessageKind.SELF, diagram.messages[1].kind, "${diagram.messages}")
    }

    @Test
    fun aThreadHandoffCallYieldsNoActivationSpan() {
        // buildActivationSpans pairs a CALL with a later mirrored RETURN of matching evidence — a
        // handoff CALL has no return and correctly yields no activation, unlike a source-enriched
        // CALL/RETURN pair (see enabledSourceEnrichmentAddsDashedEvidenceBackedCallAndDeclaredReturn).
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 7, tid = 42),
                LogEntry(2, "10:00:00.050", LogLevel.I, "B", "b", pid = 7, tid = 42),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertTrue(diagram.messages.any { it.evidence == MessageEvidence.THREAD_HANDOFF })
        assertTrue(diagram.activationSpans.isEmpty(), "${diagram.activationSpans}")
    }

    // ── Message filters (Part 6): index remapping must never desync notes/frames ─────────────

    @Test
    fun showSelfMessagesFalsePreservesErrorNoteAnchorsAndSeqGroupFrameBracketsOnSurvivingMessages() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "outer start", pid = 7, tid = 42),
                // Error-level SELF entry — will itself be dropped by showSelfMessages=false, so its
                // note must fall forward onto the next SURVIVING message (the handoff CALL below).
                LogEntry(2, "10:00:00.050", LogLevel.E, "A", "mid", pid = 7, tid = 42),
                LogEntry(3, "10:00:00.100", LogLevel.I, "B", "outer end", pid = 7, tid = 42),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef(
                        "outer", "outer start", priority = 1, color = Color.Red,
                        tag = "A", endMatchText = "outer end", endTag = "B",
                    ),
                ),
            ),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                options = plainOptions(threadHandoffArrows = true).copy(seqGroupFrames = true, notesForErrors = true, showSelfMessages = false),
            ),
        )

        // Only the handoff CALL (A->B) survives; both SELF entries (including the error one) are
        // dropped.
        assertEquals(1, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
        assertEquals(MessageEvidence.THREAD_HANDOFF, diagram.messages[0].evidence)

        assertEquals(1, diagram.notes.size, "${diagram.notes}")
        assertEquals(0, diagram.notes[0].afterMsg, "the error entry's dropped SELF must fall forward onto the surviving CALL")

        assertEquals(1, diagram.frames.size, "${diagram.frames}")
        assertEquals(0, diagram.frames[0].firstMsg)
        assertEquals(0, diagram.frames[0].lastMsg)
    }

    @Test
    fun showSourceInferredFalsePreservesSeqGroupFrameBracketsOnSurvivingMessages() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "outer start")),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef(
                        "outer", "outer start", priority = 1, color = Color.Blue,
                        tag = "A", endMatchText = "outer start", endTag = "A",
                    ),
                ),
            ),
        )
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions().copy(seqGroupFrames = true, showSourceInferred = false),
            ),
            resolveSourceInteractions = { listOf(DiagramSourceInteraction("a", "b", "B.work()", "String")) },
        )

        // The entry's own SELF event survives; both SOURCE_INFERRED messages (CALL + RETURN) are
        // dropped, so nothing in the diagram carries that evidence anymore.
        assertEquals(1, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.SELF, diagram.messages[0].kind)
        assertTrue(diagram.messages.none { it.evidence == MessageEvidence.SOURCE_INFERRED })
        assertEquals(1, diagram.frames.size, "${diagram.frames}")
        assertEquals(0, diagram.frames[0].firstMsg)
        assertEquals(0, diagram.frames[0].lastMsg)
    }

    @Test
    fun collapseRepeatsStillFoldsGenuinelyIdenticalConsecutiveSelfMessages() {
        // Regression guard for collapseRepeats' sameInteraction predicate after adding the `kind`
        // comparison (this codebase currently never produces a CALL and a SELF sharing the same
        // (fromIdx, toIdx) — SELF is fromIdx==toIdx by construction everywhere messages are built
        // — so the new comparison is defensive rather than presently observable; this at least
        // confirms it didn't accidentally stop same-kind repeats from folding).
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "same", pid = 7, tid = 42),
                LogEntry(2, "10:00:00.050", LogLevel.I, "A", "same", pid = 7, tid = 42),
            ),
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(collapseRepeats = true)))
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.SELF, diagram.messages[0].kind)
        assertEquals(2, diagram.messages[0].repeatCount)
    }
}
