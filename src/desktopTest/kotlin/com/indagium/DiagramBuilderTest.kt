@file:Suppress("MaxLineLength")

package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramAuthoringMode
import com.indagium.diagram.DiagramCallOverride
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramMessageOverride
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramParticipantRepresentation
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramResolvedTrace
import com.indagium.diagram.DiagramRuleCaptureBinding
import com.indagium.diagram.DiagramRuleEndpoint
import com.indagium.diagram.DiagramSourceEnrichment
import com.indagium.diagram.DiagramSourceInteraction
import com.indagium.diagram.DiagramTraceEvent
import com.indagium.diagram.LabelSource
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualDiagramRepeatPresentation
import com.indagium.diagram.ManualFragmentKind
import com.indagium.diagram.ManualOperationVisibility
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.MessageOriginKey
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.SourceTraceMode
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.buildManualMessageQueue
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.diagramParticipantCandidates
import com.indagium.diagram.manualDocumentFromDiagram
import com.indagium.diagram.manualMessageBucketId
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
    fun defaultsEnableSafeHandoffsAndUseBothLabelSources() {
        assertTrue(DiagramOptions().threadHandoffArrows)
        assertEquals(LabelSource.BOTH, DiagramOptions().labelSource)

        val tab = mkTab("labels", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "message")))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = DiagramOptions()), resolveLabel = { "run()" })
        assertTrue(diagram.messages.single().label.contains("run() — message"), "${diagram.messages}")
    }

    @Test
    fun tokenCorrelationDrawsOnlyAdjacentCrossLifelineCall() {
        val token = "0123456789abcdef"
        val tab = mkTab("tokens", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "requestId=$token start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "request_id=\"$token\" finish"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "A", "unmatched"),
        ))

        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = false)))

        assertEquals(listOf(MessageKind.CALL, MessageKind.CALL, MessageKind.SELF), diagram.messages.map { it.kind })
        assertEquals(MessageEvidence.CORRELATION_TOKEN, diagram.messages[1].evidence)
        assertTrue(diagram.messages.all { it.primary })
    }

    @Test
    fun threadHandoffTakesPrecedenceOverSharedToken() {
        val token = "0123456789abcdef"
        val tab = mkTab("precedence", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "requestId=$token start", pid = 7, tid = 11),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "traceId=$token finish", pid = 7, tid = 11),
        ))

        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))

        assertEquals(MessageEvidence.THREAD_HANDOFF, diagram.messages[1].evidence)
    }

    @Test
    fun tokenCorrelationRequiresParsedTimestampAndDifferentLifelines() {
        val token = "0123456789abcdef"
        val sameTag = mkTab("same", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "requestId=$token start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "A", "requestId=$token finish"),
        ))
        val badTimestamp = mkTab("bad", "app.log", listOf(
            LogEntry(1, "unknown", LogLevel.I, "A", "requestId=$token start"),
            LogEntry(2, "unknown", LogLevel.I, "B", "requestId=$token finish"),
        ))

        assertEquals(MessageKind.SELF, buildSequenceDiagram(sameTag, SeqDiagramSpec(options = plainOptions(false))).messages[1].kind)
        assertEquals(MessageKind.SELF, buildSequenceDiagram(badTimestamp, SeqDiagramSpec(options = plainOptions(false))).messages[1].kind)
    }

    @Test
    fun signalRankingKeepsHighErrorTemplateTagAheadOfNoisyTag() {
        val entries = buildList {
            repeat(20) { add(LogEntry(it + 1, "10:00:00.%03d".format(it), LogLevel.I, "Noisy", "heartbeat")) }
            add(LogEntry(100, "10:00:01.000", LogLevel.E, "Signal", "failure one"))
            add(LogEntry(101, "10:00:01.010", LogLevel.E, "Signal", "failure two"))
            add(LogEntry(102, "10:00:01.020", LogLevel.W, "Signal", "retrying"))
        }
        val candidates = diagramParticipantCandidates(mkTab("ranking", "app.log", entries), SeqDiagramSpec())
            .associateBy { it.tag }

        assertEquals(DiagramParticipantRepresentation.SHOW, candidates.getValue("Signal").representation)
        assertTrue(candidates.getValue("Signal").signalScore > candidates.getValue("Noisy").signalScore)
    }

    @Test
    fun threadPeerBonusKeepsCorrelatedHighVolumeTagsOffTheOtherLifeline() {
        // Regression test: a tag doing high-volume, low-diversity logging (a real worker/service
        // tag genuinely part of a pid/tid call chain) used to lose signalScore ranking to several
        // distinct-error/template "noise" tags, get merged into the shared "Other" lifeline together
        // with its actual thread-handoff partner, and the correlated line's THREAD_HANDOFF CALL
        // silently degraded into a same-lifeline SELF (fromIdx == toIdx once both landed on "Other").
        val entries = buildList {
            var id = 1
            var ms = 0
            // 8 tags that would outrank A/B on signalScore alone without the pid/tid-peer bonus: two
            // distinct-message error entries each -> 4*2 + 2*2 + min(2, 10) = 14.
            repeat(8) { i ->
                val tag = "Noise$i"
                add(LogEntry(id++, "09:00:00.%03d".format(ms++), LogLevel.E, tag, "err-a"))
                add(LogEntry(id++, "09:00:00.%03d".format(ms++), LogLevel.E, tag, "err-b"))
            }
            // A: 10 same-message, no-error entries -> high volume, low diversity (signalScore = 12
            // without the peer bonus, below every "Noise" tag's 14). The last one is A's half of the
            // real pid/tid handoff.
            repeat(9) { add(LogEntry(id++, "09:00:01.%03d".format(ms++), LogLevel.I, "A", "poll")) }
            add(LogEntry(id++, "09:00:02.000", LogLevel.I, "A", "poll", pid = 7, tid = 42))
            // B's first entry: same real thread, 50ms later - the one line that actually proves A
            // and B are part of the same call chain.
            add(LogEntry(id++, "09:00:02.050", LogLevel.I, "B", "poll", pid = 7, tid = 42))
            repeat(9) { add(LogEntry(id++, "09:00:03.%03d".format(ms++), LogLevel.I, "B", "poll")) }
        }
        val tab = mkTab("thread-peer-bonus", "app.log", entries)

        val candidates = diagramParticipantCandidates(tab, SeqDiagramSpec()).associateBy { it.tag }
        assertTrue(
            candidates.getValue("A").signalScore > candidates.getValue("Noise0").signalScore,
            "${candidates["A"]} vs ${candidates["Noise0"]}",
        )
        assertEquals(DiagramParticipantRepresentation.SHOW, candidates.getValue("A").representation)
        assertEquals(DiagramParticipantRepresentation.SHOW, candidates.getValue("B").representation)

        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions(threadHandoffArrows = true)))
        assertTrue(
            diagram.messages.any { it.evidence == MessageEvidence.THREAD_HANDOFF },
            "expected a THREAD_HANDOFF call between A and B, got ${diagram.messages}",
        )
    }

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

        assertEquals(3, diagram.participants.size, "A and B plus the transient Caller, auto-derived; ${diagram.participants}")
        val callerIdx = diagram.participants.indexOfFirst { it.label == "Caller" }
        val aIdx = diagram.participants.indexOfFirst { it.tag == "A" }
        val bIdx = diagram.participants.indexOfFirst { it.tag == "B" }
        assertEquals(
            4, diagram.messages.size,
            "no bootstrap suppression anymore — every entry with a resolvable lifeline emits; ${diagram.messages}",
        )
        assertEquals(1, diagram.messages.count { it.kind == MessageKind.CALL })
        assertTrue(diagram.messages.drop(1).all { it.kind == MessageKind.SELF }, "only the transient caller opening is a CALL; ${diagram.messages}")
        assertTrue(diagram.messages.all { it.evidence == MessageEvidence.LOG })
        assertEquals(listOf(callerIdx, bIdx, bIdx, aIdx), diagram.messages.map { it.fromIdx })
        assertEquals(listOf("a1", "b1", "b2", "a2"), diagram.messages.map { it.label })
        assertEquals(4, diagram.scannedEntries)
        assertTrue(diagram.warnings.none { it.contains("No correlated interactions found") }, "Caller opening is evidenced; ${diagram.warnings}")
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
        assertEquals(1, diagram.messages.count { it.kind == MessageKind.CALL }, "only Caller -> A is a CALL; ${diagram.messages}")
        assertEquals(2, diagram.messages.count { it.kind == MessageKind.SELF })
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
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            participants = listOf(
                DiagramParticipant("A", "A", ParticipantKind.TAG, tag = "A"),
                DiagramParticipant("B", "B", ParticipantKind.TAG, tag = "B"),
            ),
            mode = ArrowMode.LINE_PER_MESSAGE,
            options = plainOptions(collapseRepeats = true),
        ))

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

        assertTrue(diagram.participants.none { it.label == "Caller" })
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
    fun maxMessagesNeverDropsPrimaryLogEvidence() {
        val entries = (0 until 10).map { i -> LogEntry(i + 1, "10:00:00.%03d".format(i), LogLevel.I, "A", "m$i") }
        val tab = mkTab("t1", "app.log", entries)
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions(maxMessages = 3)),
        )

        assertEquals(10, diagram.messages.size)
        assertEquals((1..10).toSet(), diagram.primaryEntryIds)
        assertTrue(diagram.truncated, "the cap records skipped optional enrichment, never omitted primary rows")
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
            SeqDiagramSpec(range = DiagramRange.Time("10:00:00.000", "10:00:00.500"), mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions()),
        )

        assertEquals(2, diagram.scannedEntries, "entry 3's own ts is outside the range; entry 2 inherits entry 1's and is inside it")
        // Both scanned entries share tag A, so both are uncorrelated SELF events — the range
        // resolution itself found no problem (no "unparseable timestamp"/"bad bound" warning),
        // just the expected "nothing to correlate" notice.
        assertTrue(diagram.warnings.isEmpty(), "${diagram.warnings}")
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
            SeqDiagramSpec(
                range = DiagramRange.Time("10:00:00.000", "10:00:00.200"), mode = ArrowMode.LINE_PER_MESSAGE,
                options = plainOptions().copy(includeRowsHiddenByFilter = false),
            ),
        )

        assertEquals(
            2, diagram.scannedEntries,
            "entry 1 (own ts) and entry 3 (inherited from the filtered-out entry 2) are in window; entry 2 is invisible, entry 4 is out of window",
        )
        assertTrue(diagram.warnings.isEmpty(), "${diagram.warnings}")
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
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(options = plainOptions().copy(includeRowsHiddenByFilter = false)))

        assertEquals(1, diagram.scannedEntries, "the filter hides tag B entirely, so the diagram never sees it")
        assertNull(diagram.participants.firstOrNull { it.tag == "B" })
    }

    @Test
    fun explicitSelectionCanIncludeRowsHiddenByTheActiveFilter() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "B", "b"),
            ),
        ).copy(filter = Filter(activeTags = setOf("A")))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(range = DiagramRange.Ids(1, 2), options = plainOptions()),
        )
        assertEquals(2, diagram.scannedEntries)
        assertTrue(diagram.participants.any { it.tag == "B" })
    }

    @Test
    fun exactSelectedIdsDoNotIncludeUnselectedRowsBetweenTheirBounds() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "unselected"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "A", "last"),
        ))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                range = DiagramRange.Ids(1, 3, selectedIds = setOf(1, 3)),
                options = plainOptions(collapseRepeats = false),
            ),
        )
        assertEquals(2, diagram.scannedEntries)
        assertEquals(listOf(1, 3), diagram.messages.map { it.entryId })
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
        // tag change; hidden C still produces no arrow at all. The transient Caller is the one
        // legitimate opening CALL.
        assertEquals(3, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages.first().kind)
        assertTrue(diagram.messages.drop(1).all { it.kind == MessageKind.SELF })
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
        assertEquals(3, grouped.participants.size)
        assertEquals("App", grouped.participants.first().label)
        assertEquals(3, grouped.coverage.shownEntries + grouped.coverage.groupedEntries)
        // A and B both merge into the SAME "app" component, and C has no correlation evidence
        // either — the only CALL is the transient Caller opening, never a guessed tag transition.
        assertEquals(3, grouped.messages.size, "${grouped.messages}")
        assertEquals(MessageKind.CALL, grouped.messages.first().kind)
        assertTrue(grouped.messages.drop(1).all { it.kind == MessageKind.SELF })

        val hidden = buildSequenceDiagram(tab, SeqDiagramSpec(components = listOf(component), options = plainOptions()))
        assertEquals(2, hidden.participants.size)
        assertEquals(1, hidden.coverage.hiddenEntries)
    }

    @Test
    fun mirroredActorRelaysNonSelfComponentEdgesThroughTheMirroredComponent() {
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
        // The transient Caller opens the inferred flow; the actor relays and the handoff remain
        // structural messages around the primary log rows.
        assertEquals(4, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
        assertEquals(MessageEvidence.LOG, diagram.messages[0].evidence)
        assertEquals(2, diagram.messages.count { it.evidence == MessageEvidence.ACTOR_MIRROR })
        assertEquals(MessageEvidence.THREAD_HANDOFF, diagram.messages.last().evidence)
        assertEquals("a", diagram.participants[diagram.messages.last().fromIdx].id)
        assertEquals("b", diagram.participants[diagram.messages.last().toIdx].id)
    }

    @Test
    fun manualCallOverrideRewritesTheExactGeneratedEdge() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke"),
        ))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("a", "A", setOf("A")),
                    DiagramComponent("b", "B", setOf("B")),
                ),
                callOverrides = listOf(DiagramCallOverride(1, 0, "a", "b")),
                options = plainOptions(),
            ),
        )
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.CALL, diagram.messages.single().kind)
        assertEquals(MessageEvidence.MANUAL_OVERRIDE, diagram.messages.single().evidence)
        assertEquals("a", diagram.participants[diagram.messages.single().fromIdx].id)
        assertEquals("b", diagram.participants[diagram.messages.single().toIdx].id)
    }

    @Test
    fun actorCanMirrorMultipleComponents() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "start", pid = 7, tid = 42),
            LogEntry(2, "10:00:00.100", LogLevel.I, "B", "handoff", pid = 7, tid = 42),
        ))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
            actors = listOf(DiagramActor("client", "Client", mirrorComponentIds = setOf("a", "b"))),
            options = plainOptions(threadHandoffArrows = true),
        ))
        assertEquals(3, diagram.messages.count { it.evidence == MessageEvidence.ACTOR_MIRROR })
    }

    @Test
    fun uniquelyResolvedSourceEnrichmentPromotesSelfToCallAndKeepsDeclaredReturn() {
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
        // The source relationship is stronger evidence than the fallback SELF, so it supplies the
        // actual component endpoints even though same-thread handoff inference is disabled.
        assertEquals(
            listOf(MessageEvidence.SOURCE_INFERRED, MessageEvidence.SOURCE_INFERRED),
            diagram.messages.map { it.evidence },
        )
        assertEquals(MessageKind.CALL, diagram.messages.first().kind)
        assertEquals("a", diagram.participants[diagram.messages.first().fromIdx].id)
        assertEquals("b", diagram.participants[diagram.messages.first().toIdx].id)
        assertEquals("invoke", diagram.messages.first().label)
        assertEquals(MessageKind.RETURN, diagram.messages.last().kind)
        assertEquals("String", diagram.messages.last().label)
        assertEquals(setOf(1), diagram.primaryEntryIds)
        assertTrue(diagram.messages.first().primary)
        assertFalse(diagram.messages.last().primary)
        assertEquals(1, diagram.activationSpans.size)
        assertTrue(diagram.toMermaid().contains("-->>"))
    }

    @Test
    fun sourceCallWithoutObservedReturnKeepsActivationOpenToTheEndOfTheWindow() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "B", "work started"),
        ))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("a", "A", setOf("A")),
                    DiagramComponent("b", "B", setOf("B")),
                ),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(),
            ),
            resolveSourceInteractions = { entry ->
                if (entry.id == 1) listOf(DiagramSourceInteraction("a", "b", "B.work()")) else emptyList()
            },
        )

        assertEquals(listOf(MessageKind.CALL, MessageKind.SELF), diagram.messages.map { it.kind })
        assertEquals(1, diagram.activationSpans.size)
        val span = diagram.activationSpans.single()
        assertEquals("b", diagram.participants[span.participantIdx].id)
        assertEquals(0, span.startMessage)
        assertEquals(1, span.endMessage)
        assertTrue(diagram.warnings.none { it.contains("No correlated interactions found") })
    }

    @Test
    fun sourceActivationClosesWhenCallerProducesTheNextObservableEvent() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "B", "work started"),
            LogEntry(3, "10:00:00.020", LogLevel.I, "A", "invoke complete"),
        ))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("a", "A", setOf("A")),
                    DiagramComponent("b", "B", setOf("B")),
                ),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(),
            ),
            resolveSourceInteractions = { entry ->
                if (entry.id == 1) listOf(DiagramSourceInteraction("a", "b", "B.work()")) else emptyList()
            },
        )

        val span = diagram.activationSpans.single()
        assertEquals(0, span.startMessage)
        assertEquals(2, span.endMessage)
    }

    @Test
    fun ambiguousSourceInteractionsDoNotReplaceTheFallbackSelf() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke")))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("a", "A", setOf("A")),
                    DiagramComponent("b", "B", setOf("B")),
                    DiagramComponent("c", "C", setOf("C")),
                ),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(),
            ),
            resolveSourceInteractions = {
                listOf(
                    DiagramSourceInteraction("a", "b", "B.work()"),
                    DiagramSourceInteraction("a", "c", "C.work()"),
                )
            },
        )

        assertEquals(MessageKind.CALL, diagram.messages.first().kind)
        assertEquals(MessageEvidence.LOG, diagram.messages.first().evidence)
    }

    @Test
    fun consecutiveAndroidStackFramesInferCallerToCalleeEdgesAndIgnoreUnmatchedRuns() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "    at com.example.Callee.work(Callee.kt:10)"),
            LogEntry(2, "10:00:00.010", LogLevel.E, "AndroidRuntime", "    at com.example.Caller.handle(Caller.kt:20)"),
            LogEntry(3, "10:00:00.020", LogLevel.E, "AndroidRuntime", "not a frame"),
            LogEntry(4, "10:00:00.030", LogLevel.E, "AndroidRuntime", "    at com.example.Other.run(Other.java:30)"),
        ))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            components = listOf(
                DiagramComponent("caller", "Caller", emptySet(), sourceOwnerTypes = setOf("com.example.Caller")),
                DiagramComponent("callee", "Callee", emptySet(), sourceOwnerTypes = setOf("com.example.Callee")),
                DiagramComponent("other", "Other", emptySet(), sourceOwnerTypes = setOf("com.example.Other")),
            ),
            options = plainOptions(),
        ))

        assertEquals(1, diagram.messages.size, "a non-frame breaks the consecutive evidence run")
        val message = diagram.messages.single()
        assertEquals(MessageKind.CALL, message.kind)
        assertEquals(MessageEvidence.SOURCE_INFERRED, message.evidence)
        assertEquals(2, message.entryId, "the caller frame owns the caller-to-callee evidence")
        assertEquals("caller", diagram.participants[message.fromIdx].id)
        assertEquals("callee", diagram.participants[message.toIdx].id)
        assertTrue(message.label.contains("Callee.work(Callee.kt:10)"))
    }

    @Test
    fun identicalAdjacentStackFramesProduceConservativeRecursiveSelfEvidence() {
        val tab = mkTab("t1", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "at com.example.Tree.walk(Tree.kt:10)"),
            LogEntry(2, "10:00:00.010", LogLevel.E, "AndroidRuntime", "at com.example.Tree.walk(Tree.kt:11)"),
        ))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            components = listOf(DiagramComponent(
                "tree", "Tree", emptySet(), sourceOwnerTypes = setOf("com.example.Tree"),
            )),
            options = plainOptions(),
        ))

        val message = diagram.messages.single()
        assertEquals(MessageKind.SELF, message.kind)
        assertEquals(MessageEvidence.SOURCE_INFERRED, message.evidence)
        assertEquals(message.fromIdx, message.toIdx)
        assertEquals(2, message.entryId)
    }

    @Test
    fun sourceReturnIsSuppressedWhenSelectedRangeHasLaterTerminalFailureMarker() {
        listOf("FATAL EXCEPTION: main", "ActivityManager: ANR in com.example", "java.lang.StackOverflowError").forEach { marker ->
            val tab = mkTab("t1", "app.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "invoke"),
                LogEntry(2, "10:00:00.010", LogLevel.E, "AndroidRuntime", marker),
            ))
            val diagram = buildSequenceDiagram(
                tab,
                SeqDiagramSpec(
                    components = listOf(
                        DiagramComponent("a", "A", setOf("A")),
                        DiagramComponent("b", "B", emptySet()),
                    ),
                    options = plainOptions(),
                ),
                resolveSourceInteractions = {
                    listOf(DiagramSourceInteraction("a", "b", "B.work()", returnLabel = "String"))
                },
            )

            assertEquals(listOf(MessageKind.CALL), diagram.messages.map { it.kind }, "marker=$marker")
        }
    }

    @Test
    fun sourceEnrichmentBoundsResolverWorkWhenRuntimeOutputReachesTheMessageCap() {
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
        assertEquals(maxMessages, resolverCalls, "source resolution may replace fallback self events but remains bounded")
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
    fun aPartialSourceTraceStillLetsThreadHandoffsDrawACallInsteadOfCollapsingToSelfOnly() {
        // Regression test for a bug where enabling "Use verified source trace" together with
        // "Include same-thread handoffs" silently stopped changing the diagram at all: any
        // non-empty (but incomplete) resolved trace used to take over message generation
        // entirely and skip evidence-flow, so options.threadHandoffArrows was never read and
        // every row fell back to a same-lifeline SELF the moment the source trace could not
        // fully cover the selected range — which is the common case, not the exception.
        val tab = mkTab(
            "partial-trace-handoff", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "a", pid = 7, tid = 42),
                LogEntry(2, "10:00:00.050", LogLevel.I, "B", "b", pid = 7, tid = 42),
            ),
        )
        // A trace that resolved entry 1 only: non-empty (sourceTraceUsable) but not a full
        // projection over both represented entries (not sourceTraceComplete), and it contributes
        // no calls of its own — isolating whether evidence-flow's own handoff detection still runs.
        val partialTrace = DiagramResolvedTrace(events = listOf(DiagramTraceEvent(entryId = 1, ownerType = "A")))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(threadHandoffArrows = true),
            ),
            resolveTrace = { partialTrace },
        )

        assertEquals(SourceTraceMode.PARTIAL_SOURCE_TRACE, diagram.traceMode)
        val handoff = diagram.messages.singleOrNull { it.entryId == 2 }
        assertEquals(MessageKind.CALL, handoff?.kind, "${diagram.messages}")
        assertEquals(MessageEvidence.THREAD_HANDOFF, handoff?.evidence, "${diagram.messages}")
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
    fun showSelfMessagesFalseDoesNotHidePrimaryLogEvidence() {
        val tab = mkTab(
            "t1", "app.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "outer start", pid = 7, tid = 42),
                // Error-level SELF entry stays primary even when supplemental self structure is hidden.
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

        assertEquals(3, diagram.messages.size, "${diagram.messages}")
        assertEquals(setOf(1, 2, 3), diagram.primaryEntryIds)
        assertEquals(listOf(MessageKind.CALL, MessageKind.SELF, MessageKind.CALL), diagram.messages.map { it.kind })
        assertTrue(diagram.messages.all { it.primary })

        assertEquals(1, diagram.notes.size, "${diagram.notes}")
        assertEquals(1, diagram.notes[0].afterMsg)

        assertEquals(1, diagram.frames.size, "${diagram.frames}")
        assertEquals(0, diagram.frames[0].firstMsg)
        assertEquals(2, diagram.frames[0].lastMsg)
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

        // The transient Caller opening survives as log evidence; both SOURCE_INFERRED messages
        // (CALL + RETURN) are dropped by the option.
        assertEquals(1, diagram.messages.size, "${diagram.messages}")
        assertEquals(MessageKind.CALL, diagram.messages[0].kind)
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
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            participants = listOf(DiagramParticipant("A", "A", ParticipantKind.TAG, tag = "A")),
            mode = ArrowMode.LINE_PER_MESSAGE,
            options = plainOptions(collapseRepeats = true),
        ))
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.SELF, diagram.messages[0].kind)
        assertEquals(2, diagram.messages[0].repeatCount)
    }

    @Test
    fun manualDocumentIsSourceIndependentAndPreservesEvidenceLifelineAndInteractionOrder() {
        val tab = mkTab("manual", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "B", "ignored"),
            LogEntry(3, "10:00:00.020", LogLevel.I, "B", "second"),
        ))
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
            lifelineOrder = listOf("b", "a"),
            manualDocument = ManualDiagramDocument(
                interactions = listOf(
                    ManualDiagramInteraction("later", setOf(1), "a", "b", operation = "later", order = 20),
                    ManualDiagramInteraction("earlier", setOf(3), "b", "a", operation = "send", order = 10),
                ),
                groups = listOf(ManualDiagramGroup("g", "Flow", listOf("later", "earlier"))),
                notes = listOf(ManualDiagramNote("n", "a", "later", "done")),
                activations = listOf(ManualDiagramActivation("act", "a", "earlier", "later")),
            ),
        )
        val diagram = buildSequenceDiagram(tab, spec, resolveTrace = { error("manual mode must not resolve source") })

        assertTrue(diagram.participants.none { it.label == "Caller" })
        assertEquals(listOf("b", "a"), diagram.participants.map { it.id })
        assertEquals(listOf(3, 1), diagram.messages.map { it.entryId })
        assertEquals(listOf("send()", "later()"), diagram.messages.map { it.label })
        assertEquals(setOf(1, 3), diagram.primaryEntryIds)
        assertEquals(1, diagram.frames.size)
        assertEquals(1, diagram.notes.size)
        assertEquals(1, diagram.activationSpans.size)
    }

    @Test
    fun manualInteractionRendersFromPersistedAnchorWhenCurrentRangeLacksItsSourceRow() {
        val tab = mkTab("manual-anchor", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "original source"),
            LogEntry(2, "10:00:01.000", LogLevel.D, "B", "current range row"),
        ))
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            range = DiagramRange.Ids(from = 2, to = 2, selectedIds = setOf(2)),
            components = listOf(
                DiagramComponent("a", "A", setOf("A")),
                DiagramComponent("b", "B", setOf("B")),
            ),
            manualDocument = ManualDiagramDocument(interactions = listOf(
                ManualDiagramInteraction(
                    id = "anchored",
                    sourceEntryIds = setOf(1),
                    fromParticipantId = "a",
                    toParticipantId = "b",
                    operation = "persisted",
                    renderAnchorTs = "09:59:59.123",
                    renderAnchorLevel = LogLevel.E,
                ),
            )),
        )

        val diagram = buildSequenceDiagram(tab, spec)

        assertEquals(1, diagram.messages.size)
        assertEquals(1, diagram.messages.single().entryId)
        assertEquals("09:59:59.123", diagram.messages.single().ts)
        assertEquals(LogLevel.E, diagram.messages.single().level)
        assertEquals(setOf(1), diagram.messages.single().representedEntryIds)
    }

    @Test
    fun anchoredManualInteractionWithoutSourceIdsStillRenders() {
        val tab = mkTab("manual-anchor-empty", "app.log", listOf(
            LogEntry(2, "10:00:01.000", LogLevel.D, "B", "current range row"),
        ))
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = listOf(
                DiagramComponent("a", "A", setOf("A")),
                DiagramComponent("b", "B", setOf("B")),
            ),
            manualDocument = ManualDiagramDocument(interactions = listOf(
                ManualDiagramInteraction(
                    id = "anchor-without-source",
                    sourceEntryIds = emptySet(),
                    fromParticipantId = "a",
                    toParticipantId = "b",
                    operation = "persisted",
                    renderAnchorTs = "09:59:59.123",
                    renderAnchorLevel = LogLevel.W,
                ),
            )),
        )

        val diagram = buildSequenceDiagram(tab, spec)

        assertEquals(1, diagram.messages.size)
        assertEquals(0, diagram.messages.single().entryId)
        assertEquals("09:59:59.123", diagram.messages.single().ts)
        assertEquals(LogLevel.W, diagram.messages.single().level)
        assertTrue(diagram.messages.single().representedEntryIds.isEmpty())
    }

    @Test
    fun sourceTraceReceivesOnlyRowsRepresentedByConfiguredLifelines() {
        val tab = mkTab("projection", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "represented"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "Hidden", "must not poison trace"),
        ))
        var resolvedEntryIds = emptyList<Int>()
        buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                components = listOf(DiagramComponent("a", "A", setOf("A"))),
                sourceEnrichment = DiagramSourceEnrichment(enabled = true),
                options = plainOptions(),
            ),
            resolveTrace = { entries ->
                resolvedEntryIds = entries.map { it.id }
                DiagramResolvedTrace()
            },
        )

        assertEquals(listOf(1), resolvedEntryIds)
    }

    @Test
    fun originAddressedOverrideEditsAndDisablesInferredMessagesWithoutLegacyOrdinals() {
        val tab = mkTab("overrides", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "raw")))
        val base = SeqDiagramSpec(
            components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
            sourceEnrichment = DiagramSourceEnrichment(enabled = false),
            options = plainOptions(),
            messageOverrides = listOf(
                DiagramMessageOverride(
                    origin = MessageOriginKey(entryId = 1, generatedOrdinal = 0),
                    fromParticipantId = "a", toParticipantId = "b", label = "edited",
                ),
            ),
        )
        val edited = buildSequenceDiagram(tab, base)
        assertEquals("edited", edited.messages.single().label)
        assertEquals(listOf("a", "b"), edited.messages.single().let { listOf(edited.participants[it.fromIdx].id, edited.participants[it.toIdx].id) })

        val disabled = buildSequenceDiagram(tab, base.copy(messageOverrides = listOf(
            DiagramMessageOverride(MessageOriginKey(1, generatedOrdinal = 0), enabled = false),
        )))
        assertTrue(disabled.messages.isEmpty())
    }

    @Test
    fun collapsedAndMirroredMessagesRetainAllStableOrigins() {
        val tab = mkTab("origins", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "same"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "same"),
        ))
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.LINE_PER_MESSAGE, options = plainOptions(collapseRepeats = true)))
        assertEquals(1, diagram.messages.size)
        assertEquals(setOf(1, 2), diagram.messages.single().originKeys.mapTo(mutableSetOf()) { it.entryId })
        val manual = manualDocumentFromDiagram(diagram)
        assertEquals(2, manual.interactions.size, "collapsed occurrences must remain independently editable")
        assertEquals(1, manual.interactions.map { it.groupKey }.toSet().size)
    }

    @Test
    fun manualBuilderGroupsCollapseIntoOneRepeatedArrowAndUnusedLifelinesReappear() {
        val tab = mkTab("manual-groups", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "same id=1"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "same id=2"),
        ))
        val components = listOf(
            DiagramComponent("a", "A", setOf("A")),
            DiagramComponent("b", "B", setOf("B")),
            DiagramComponent("c", "C", setOf("C")),
        )
        val first = ManualDiagramInteraction("one", setOf(1), "a", "b", operation = "send", groupKey = "send")
        val second = ManualDiagramInteraction("two", setOf(2), "a", "b", operation = "send", groupKey = "send", order = 1)
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = components,
            lifelineOrder = listOf("c", "a", "b"),
            manualDocument = ManualDiagramDocument(interactions = listOf(first, second)),
        )
        val diagram = buildSequenceDiagram(tab, spec)
        assertEquals(listOf("a", "b"), diagram.participants.map { it.id })
        // Same groupKey bucket collapses into one arrow (Stage 1b) instead of drawing both
        // occurrences separately; the collapsed message reports both entries as evidence.
        assertEquals(1, diagram.messages.size)
        assertEquals(2, diagram.messages.single().repeatCount)
        assertEquals(setOf(1, 2), diagram.messages.single().representedEntryIds)
        assertEquals("group:send", diagram.messages.single().manualGroupKey)

        val reappeared = buildSequenceDiagram(tab, spec.copy(manualDocument = spec.manualDocument.copy(
            interactions = listOf(first, second.copy(toParticipantId = "c")),
        )))
        assertEquals(listOf("c", "a", "b"), reappeared.participants.map { it.id })
        assertEquals("+send()", buildSequenceDiagram(tab, spec.copy(manualDocument = spec.manualDocument.copy(
            interactions = listOf(first.copy(visibility = ManualOperationVisibility.PUBLIC)),
        ))).messages.single().label)
    }

    @Test
    fun manualRepeatPresentationNeverCollapsesInterleavedEvidenceAndKeepsFirstAndLastBoundaries() {
        val tab = mkTab("manual-repeat-presentations", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "middle"),
            LogEntry(3, "10:00:00.020", LogLevel.I, "A", "last"),
        ))
        val components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B")))
        fun grouped(id: String, entryId: Int, order: Long) =
            ManualDiagramInteraction(id, setOf(entryId), "a", "b", operation = "send", groupKey = "send", order = order)
        fun build(document: ManualDiagramDocument) = buildSequenceDiagram(
            tab, SeqDiagramSpec(authoringMode = DiagramAuthoringMode.MANUAL, components = components, manualDocument = document),
        )

        val contiguous = listOf(grouped("first", 1, 0), grouped("middle", 2, 1), grouped("last", 3, 2))
        assertEquals(3, build(ManualDiagramDocument(contiguous, repeatPresentation = ManualDiagramRepeatPresentation.EVERY_OCCURRENCE)).messages.size)
        val firstAndLast = build(ManualDiagramDocument(contiguous, repeatPresentation = ManualDiagramRepeatPresentation.FIRST_AND_LAST))
        assertEquals(listOf(1, 3), firstAndLast.messages.map { it.entryId })

        val interleaved = listOf(grouped("first", 1, 0), ManualDiagramInteraction("other", setOf(2), "a", "b", operation = "other", order = 1), grouped("last", 3, 2))
        assertEquals(listOf(1, 2, 3), build(ManualDiagramDocument(interleaved)).messages.map { it.entryId })
    }

    @Test
    fun targetlessManualInteractionRemainsEvidenceBackedWithoutCreatingALifeline() {
        val tab = mkTab("targetless", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "A", "queued request"),
        ))
        val interaction = ManualDiagramInteraction(
            id = "needs-target", sourceEntryIds = setOf(1), fromParticipantId = "a", toParticipantId = null,
            label = "queued request", groupKey = "queue",
        )
        val diagram = buildSequenceDiagram(tab, SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = listOf(
                DiagramComponent("a", "A", setOf("A")),
                DiagramComponent("b", "B", setOf("B")),
            ),
            manualDocument = ManualDiagramDocument(interactions = listOf(interaction)),
        ))

        assertEquals(listOf("a"), diagram.participants.map { it.id })
        assertEquals(1, diagram.messages.size)
        assertTrue(diagram.messages.single().targetless)
        assertEquals(MessageKind.CALL, diagram.messages.single().kind)
        assertEquals("needs-target", diagram.messages.single().originKeys.single().manualInteractionId)
        // "group:" prefixed — this is the bucket-id fix: manualGroupKey now always matches
        // manualMessageBucketId/ManualMessageQueueRow.id for a grouped interaction (previously it
        // was the bare groupKey, which silently broke row<->canvas identity).
        assertEquals("group:queue", diagram.messages.single().manualGroupKey)
        assertEquals(manualMessageBucketId(interaction), diagram.messages.single().manualGroupKey)
    }

    @Test
    fun hiddenTargetlessManualInteractionStaysOffCanvasWithoutBecomingARealMessage() {
        val tab = mkTab("hidden-targetless", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "A", "background poll"),
        ))
        val interaction = ManualDiagramInteraction(
            id = "hidden-needs-target",
            sourceEntryIds = setOf(1),
            fromParticipantId = "a",
            toParticipantId = null,
            label = "background poll",
            enabled = false,
        )

        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                authoringMode = DiagramAuthoringMode.MANUAL,
                components = listOf(DiagramComponent("a", "A", setOf("A"))),
                manualDocument = ManualDiagramDocument(interactions = listOf(interaction)),
            ),
        )

        assertTrue(diagram.messages.isEmpty(), "hidden targetless evidence must not render a stub or arrow")
    }

    @Test
    fun everyMessageQueueRowIdMatchesTheBuiltDiagramsManualGroupKey() {
        // Regression guard for the bucket-id mismatch bug: the panel's row id
        // (groupManualMessageQueueRows) and the canvas's DiagramMessage.manualGroupKey
        // (buildManualMessages) must always agree for the same group, since ArrowHit.groupKey is
        // compared against ManualMessageQueueRow.id to drive row<->canvas selection.
        val tab = mkTab("bucket-parity", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "one"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "two"),
            LogEntry(3, "10:00:00.020", LogLevel.I, "A", "three"),
        ))
        val components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B")))
        val grouped1 = ManualDiagramInteraction("g1", setOf(1), "a", "b", operation = "send", groupKey = "send", order = 0)
        val grouped2 = ManualDiagramInteraction("g2", setOf(2), "a", "b", operation = "send", groupKey = "send", order = 1)
        val solo = ManualDiagramInteraction("solo", setOf(3), "a", "b", operation = "ping", order = 2)
        val document = ManualDiagramDocument(interactions = listOf(grouped1, grouped2, solo))
        val spec = SeqDiagramSpec(authoringMode = DiagramAuthoringMode.MANUAL, components = components, manualDocument = document)

        val diagram = buildSequenceDiagram(tab, spec)
        val queueRowIds = buildManualMessageQueue(document).rows.map { it.id }.toSet()
        val diagramGroupKeys = diagram.messages.mapNotNull { it.manualGroupKey }.toSet()

        assertEquals(queueRowIds, diagramGroupKeys)
        assertEquals(setOf(manualMessageBucketId(grouped1), manualMessageBucketId(solo)), diagramGroupKeys)
        assertEquals(2, diagram.messages.size, "the grouped pair collapses into one arrow, the solo message stays separate")
    }

    @Test
    fun manualFragmentKindPrefixesTheFrameLabelAndOldDocumentsDefaultToCustom() {
        val tab = mkTab("fragment-kind", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
        ))
        val components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B")))
        val interaction = ManualDiagramInteraction("m", setOf(1), "a", "b", operation = "send")

        fun diagramWithKind(kind: ManualFragmentKind?) = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                authoringMode = DiagramAuthoringMode.MANUAL,
                components = components,
                manualDocument = ManualDiagramDocument(
                    interactions = listOf(interaction),
                    groups = listOf(
                        if (kind == null) {
                            ManualDiagramGroup("g", "Retry", listOf("m"))
                        } else {
                            ManualDiagramGroup("g", "Retry", listOf("m"), kind = kind)
                        },
                    ),
                ),
            ),
        )

        assertEquals("Retry", diagramWithKind(null).frames.single().label, "default/CUSTOM keeps the free-text label verbatim")
        assertEquals("loop Retry", diagramWithKind(ManualFragmentKind.LOOP).frames.single().label)
        assertEquals("alt Retry", diagramWithKind(ManualFragmentKind.ALT).frames.single().label)
        assertEquals("opt Retry", diagramWithKind(ManualFragmentKind.OPT).frames.single().label)
        assertEquals("par Retry", diagramWithKind(ManualFragmentKind.PAR).frames.single().label)
    }

    @Test
    fun manualPresentationKeepsAllInteractionsAndStructuralLabels() {
        val tab = mkTab("manual-presentation", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "second"),
        ))
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = listOf(
                DiagramComponent("a", "A", setOf("A")),
                DiagramComponent("b", "B", setOf("B")),
            ),
            options = plainOptions(maxMessages = 1).copy(labelMaxChars = 8),
            manualDocument = ManualDiagramDocument(interactions = listOf(
                ManualDiagramInteraction("first", setOf(1), "a", "b", operation = "long-operation", order = 0),
                ManualDiagramInteraction("second", setOf(2), "a", "b", operation = "second", order = 1),
            )),
        )

        val diagram = buildSequenceDiagram(tab, spec)

        assertEquals(2, diagram.messages.size)
        assertFalse(diagram.truncated)
        assertTrue(diagram.messages.first().label.length <= 8)
    }

    @Test
    fun manualRepeatsDoNotCollapseAcrossAnEqualOrderInterleavedOccurrence() {
        val tab = mkTab("equal-order-interleaving", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "first"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "A", "middle"),
            LogEntry(3, "10:00:00.002", LogLevel.I, "A", "last"),
        ))
        val document = ManualDiagramDocument(interactions = listOf(
            ManualDiagramInteraction("b", setOf(1), "a", "b", operation = "send", groupKey = "send", order = 0),
            ManualDiagramInteraction("a", setOf(2), "a", "b", operation = "other", order = 0),
            ManualDiagramInteraction("c", setOf(3), "a", "b", operation = "send", groupKey = "send", order = 0),
        ))
        val diagram = buildSequenceDiagram(
            tab,
            SeqDiagramSpec(
                authoringMode = DiagramAuthoringMode.MANUAL,
                components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
                manualDocument = document,
            ),
        )

        assertEquals(listOf(1, 2, 3), diagram.messages.map { it.entryId })
        assertEquals(listOf(1, 1, 1), diagram.messages.map { it.repeatCount })
    }

    @Test
    fun originOverrideFansOutAcrossACollapsedMessageAndLifelineOrderRemapsInferredOutput() {
        val tab = mkTab("remap", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "same"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "A", "same"),
        ))
        val spec = SeqDiagramSpec(
            mode = ArrowMode.LINE_PER_MESSAGE,
            lifelineOrder = listOf("b", "a"),
            components = listOf(DiagramComponent("a", "A", setOf("A")), DiagramComponent("b", "B", setOf("B"))),
            options = plainOptions(collapseRepeats = true),
        )
        val ordered = buildSequenceDiagram(tab, spec)
        assertEquals(listOf("b", "a"), ordered.participants.map { it.id })
        assertEquals("a", ordered.participants[ordered.messages.single().fromIdx].id)
        assertTrue(ordered.messages.single().originKeys.isNotEmpty(), "every primary log event receives a stable origin")

        val disabled = buildSequenceDiagram(tab, spec.copy(messageOverrides = listOf(
            DiagramMessageOverride(ordered.messages.single().originKeys.first { it.entryId == 2 }, enabled = false),
        )))
        assertTrue(disabled.messages.isEmpty(), "an override matched through a collapsed origin set removes that rendered interaction: ${disabled.messages}")
    }

    @Test
    fun lifelineOrderChangesOnlyPresentationAndNeverReordersManualMessageEvidence() {
        val tab = mkTab("lifeline-order", "app.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "request"),
            LogEntry(2, "10:00:00.010", LogLevel.I, "B", "reply"),
        ))
        val spec = SeqDiagramSpec(
            authoringMode = DiagramAuthoringMode.MANUAL,
            components = listOf(
                DiagramComponent("a", "A", setOf("A")),
                DiagramComponent("b", "B", setOf("B")),
                DiagramComponent("c", "C", setOf("C")),
            ),
            lifelineOrder = listOf("c", "b", "a"),
            manualDocument = ManualDiagramDocument(interactions = listOf(
                ManualDiagramInteraction("first", setOf(1), "a", "b", operation = "request", order = 0),
                ManualDiagramInteraction("second", setOf(2), "b", "c", operation = "reply", order = 1),
            )),
        )

        val diagram = buildSequenceDiagram(tab, spec)

        assertEquals(listOf("c", "b", "a"), diagram.participants.map { it.id })
        assertEquals(listOf(1, 2), diagram.messages.map { it.entryId })
        assertEquals(listOf(setOf(1), setOf(2)), diagram.messages.map { it.representedEntryIds })
        assertEquals(
            listOf("a" to "b", "b" to "c"),
            diagram.messages.map { message ->
                diagram.participants[message.fromIdx].id to diagram.participants[message.toIdx].id
            },
        )
    }

    @Test
    fun typedRuleEndpointsNeverCreateActorsUnlessTheRuleExplicitlyDeclaresOne() {
        val tab = mkTab("rules", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "to remote")))
        val unresolved = DiagramMessageRule(
            id = "typed", pattern = "to (?<peer>\\w+)", fromTemplate = "", toTemplate = "", labelTemplate = "typed",
            fromEndpoint = DiagramRuleEndpoint.CurrentEntry,
            toEndpoint = DiagramRuleEndpoint.CapturedValue("peer", listOf(DiagramRuleCaptureBinding("other", "b"))),
        )
        val noActor = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.RULES, rules = listOf(unresolved), options = plainOptions()))
        assertTrue(noActor.participants.none { it.id == "remote" })
        assertTrue(noActor.messages.isEmpty())

        val explicit = unresolved.copy(toEndpoint = DiagramRuleEndpoint.ExplicitActor("remote", "Remote"))
        val withActor = buildSequenceDiagram(tab, SeqDiagramSpec(mode = ArrowMode.RULES, rules = listOf(explicit), options = plainOptions()))
        assertTrue(withActor.participants.any { it.id == "remote" })
        assertEquals("typed", withActor.messages.single().label)
        assertEquals("typed", withActor.messages.single().originKeys.single().ruleId)
    }
}
