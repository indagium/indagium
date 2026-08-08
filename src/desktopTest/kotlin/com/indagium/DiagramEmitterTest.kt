package com.indagium

import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramFrame
import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramNoteMark
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.toMermaid
import com.indagium.diagram.toPlantUml
import com.indagium.diagram.toSource
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emitter tests build SeqDiagram fixtures directly (not via buildSequenceDiagram — that's
 *  DiagramBuilderTest's job) so escaping/aliasing can be exercised in isolation. */
class DiagramEmitterTest {
    private val tagA = DiagramParticipant("A", "Tag A", ParticipantKind.TAG, tag = "A")
    private val tagB = DiagramParticipant("B", "Tag B", ParticipantKind.TAG, tag = "B")

    private fun msg(
        from: Int = 0,
        to: Int = 1,
        label: String,
        kind: MessageKind = MessageKind.CALL,
        repeatCount: Int = 1,
    ) = DiagramMessage(from, to, label, entryId = 1, ts = "10:00:00.000", level = LogLevel.I, kind = kind, repeatCount = repeatCount)

    // A real logcat-shaped message: a colliding-with-mermaid-arrow-syntax colon, a package name
    // (dots — never special), a Java exception with an embedded stack-trace newline, quotes.
    private val logcatShapedMessage =
        "onReceive: action=\"android.intent.action.BOOT_COMPLETED\" pkg=com.example.app; " +
            "extras=<Bundle[mParcelledData.dataSize=128]>\n\tat com.example.app.Receiver#onReceive(Receiver.java:42)"

    // ── Escaping: ; : # < > " ` and embedded newlines ───────────────────────────────────────

    @Test
    fun mermaidEscapesAllReservedCharactersAndTurnsEmbeddedNewlinesIntoBr() {
        val label = "weird: msg; with #hash <tag> \"quote\" `tick`\nsecond line"
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = label)),
        )

        val out = diagram.toMermaid()

        assertTrue(out.contains("#58;"), "colon must be escaped; got:\n$out")
        assertTrue(out.contains("#59;"), "semicolon must be escaped")
        assertTrue(out.contains("#35;"), "hash must be escaped")
        assertTrue(out.contains("#60;"), "'<' must be escaped")
        assertTrue(out.contains("#62;"), "'>' must be escaped")
        assertTrue(out.contains("#34;"), "double quote must be escaped")
        assertTrue(out.contains("#96;"), "backtick must be escaped")
        assertTrue(out.contains("<br/>"), "embedded newline must become <br/>")
        assertFalse(out.contains("<tag>"), "the raw, unescaped angle-bracket text must not survive")
        assertFalse(out.contains("\"quote\""), "the raw, unescaped quoted text must not survive")
        // Structural sanity: sequenceDiagram + 2 participant lines + 1 message line == 4 non-blank
        // lines — the embedded \n became "<br/>" text, not an actual second output line.
        assertEquals(4, out.trim().lines().size)
    }

    @Test
    fun mermaidHandlesARealWorldLogcatShapedMessageWithoutBreakingSyntax() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = logcatShapedMessage)),
        )

        val out = diagram.toMermaid()

        assertTrue(out.contains("A->>B:"), "the arrow itself must stay intact; got:\n$out")
        assertTrue(out.contains("<br/>"), "the stack-trace line break must become <br/>")
        assertTrue(out.contains("#58;"), "the message's own colon must be escaped")
        assertTrue(out.contains("#59;"), "the message's own semicolon must be escaped")
        assertFalse(out.contains("<Bundle"), "raw angle brackets from the Bundle dump must not survive unescaped")
    }

    @Test
    fun plantUmlEscapesQuotesBackslashesAndNewlines() {
        val label = "path C:\\logs \"crash.txt\"\nsecond line"
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = label)),
        )

        val out = diagram.toPlantUml()

        assertTrue(out.contains("\\\\logs"), "a literal backslash must be doubled; got:\n$out")
        assertTrue(out.contains("\\\"crash.txt\\\""), "double quotes must be backslash-escaped")
        assertTrue(out.contains("\\n"), "the embedded newline must become the literal two-char \\n token")
        assertFalse(out.contains("\nsecond line"), "no REAL newline should appear inside the message text")
    }

    @Test
    fun plantUmlHandlesARealWorldLogcatShapedMessageWithoutBreakingSyntax() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = logcatShapedMessage)),
        )

        val out = diagram.toPlantUml()

        assertTrue(out.contains("A -> B:"), "the arrow itself must stay intact; got:\n$out")
        assertTrue(out.contains("\\\"android.intent.action.BOOT_COMPLETED\\\""), "quotes in the message must be escaped")
        assertTrue(out.startsWith("@startuml"))
        assertTrue(out.trim().endsWith("@enduml"))
    }

    // ── Repeat suffix survives escaping ──────────────────────────────────────────────────────

    @Test
    fun repeatCountAppendsAMultiplicationSignSuffixThatSurvivesEscapingInBothDialects() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = "weird: retry", repeatCount = 4)),
        )

        assertTrue(diagram.toMermaid().contains("×4"))
        assertTrue(diagram.toPlantUml().contains("×4"))
    }

    // ── Participant alias sanitization + dedupe ─────────────────────────────────────────────

    @Test
    fun participantAliasesAreSanitizedToValidIdentifiersAndDedupedOnCollision() {
        val p1 = DiagramParticipant("Foo!", "Foo!", ParticipantKind.TAG, tag = "Foo!")
        val p2 = DiagramParticipant("Foo?", "Foo?", ParticipantKind.TAG, tag = "Foo?")
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(p1, p2)),
            participants = listOf(p1, p2),
            messages = listOf(msg(label = "hi")),
        )

        val out = diagram.toMermaid()
        val participantLines = out.lines().filter { it.trim().startsWith("participant ") }
        assertEquals(2, participantLines.size)
        val alias1 = participantLines[0].trim().removePrefix("participant ").substringBefore(' ')
        val alias2 = participantLines[1].trim().removePrefix("participant ").substringBefore(' ')
        assertTrue(alias1.matches(Regex("[A-Za-z0-9_]+")), "alias must be a valid identifier: $alias1")
        assertTrue(alias2.matches(Regex("[A-Za-z0-9_]+")), "alias must be a valid identifier: $alias2")
        assertTrue(alias1 != alias2, "two participants that sanitize to the same base must dedupe apart: $alias1 vs $alias2")
    }

    // ── actor vs participant keyword ─────────────────────────────────────────────────────────

    @Test
    fun actorParticipantsUseTheActorKeywordInBothDialects() {
        val actor = DiagramParticipant("user", "User", ParticipantKind.ACTOR)
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(actor, tagA)),
            participants = listOf(actor, tagA),
            messages = listOf(msg(label = "hi")),
        )

        assertTrue(diagram.toMermaid().lines().any { it.trim().startsWith("actor ") })
        assertTrue(diagram.toPlantUml().lines().any { it.trim().startsWith("actor ") })
    }

    // ── Frames: PlantUML nests group/end, Mermaid brackets with Note markers ────────────────

    @Test
    fun framesEmitAsNestedGroupBlocksInPlantUml() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = "one"), msg(label = "two")),
            frames = listOf(DiagramFrame(label = "My Group", colorArgb = null, firstMsg = 0, lastMsg = 1, depth = 0)),
        )

        val out = diagram.toPlantUml()
        val lines = out.lines()
        val groupIdx = lines.indexOfFirst { it.startsWith("group My Group") }
        val endIdx = lines.indexOfLast { it == "end" }
        assertTrue(groupIdx in 0 until endIdx, "group must open before both messages and close after them; got:\n$out")
    }

    @Test
    fun framesEmitAsOpenCloseNoteMarkersInMermaid() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = "one"), msg(label = "two")),
            frames = listOf(DiagramFrame(label = "My Group", colorArgb = null, firstMsg = 0, lastMsg = 1, depth = 0)),
        )

        val out = diagram.toMermaid()
        assertTrue(out.contains("▶"), "an open marker must precede the frame's first message")
        assertTrue(out.contains("◀"), "a close marker must follow the frame's last message")
        assertTrue(out.contains("My Group"))
    }

    // buildFrames computes frames independently per top-level SeqGroup, so two frames from
    // different SequenceDefs can legitimately CROSS (SeqComputer.assignParents only reparents on
    // full containment) rather than nest — this exercises exactly that shape: A=[0,10] and
    // B=[5,20], neither containing the other.
    @Test
    fun crossingFramesAreNormalizedIntoProperNestingInPlantUmlButLeftAloneInMermaid() {
        val messages = (0..20).map { msg(label = "m$it") }
        val frameA = DiagramFrame(label = "A", colorArgb = null, firstMsg = 0, lastMsg = 10, depth = 0)
        val frameB = DiagramFrame(label = "B", colorArgb = null, firstMsg = 5, lastMsg = 20, depth = 0)
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = messages,
            frames = listOf(frameA, frameB),
        )

        val plantUml = diagram.toPlantUml()
        val lines = plantUml.lines()

        // Balance/order sanity via an explicit stack — an 'end' with nothing open, or a group
        // left open at EOF, means the normalization broke nesting outright.
        val stack = ArrayDeque<String>()
        for (line in lines) {
            when {
                line.startsWith("group ") -> stack.addLast(line.removePrefix("group "))
                line == "end" -> {
                    assertTrue(stack.isNotEmpty(), "an 'end' with no open group — broken nesting; got:\n$plantUml")
                    stack.removeLast()
                }
            }
        }
        assertTrue(stack.isEmpty(), "every opened group must close; got:\n$plantUml")

        // The actual regression: B must be CLAMPED to close where A does (right after message
        // 10), not at its own original message 20 — proving the extent, not just the token
        // balance, was corrected.
        val groupAIdx = lines.indexOfFirst { it.startsWith("group A") }
        val groupBIdx = lines.indexOfFirst { it.startsWith("group B") }
        val endIndices = lines.withIndex().filter { it.value == "end" }.map { it.index }
        val m10Idx = lines.indexOfFirst { it.contains(": m10") }
        val m11Idx = lines.indexOfFirst { it.contains(": m11") }
        assertEquals(2, endIndices.size, "one end per frame; got:\n$plantUml")
        assertTrue(groupAIdx in 0 until groupBIdx, "A (the wider, earlier-starting frame) must open before B; got:\n$plantUml")
        assertTrue(groupBIdx < endIndices[0], "B must open before the first end; got:\n$plantUml")
        assertTrue(
            m10Idx < endIndices[0] && endIndices[0] < m11Idx,
            "B's close must land right after message 10 (clamped to A's own end), not message 20; got:\n$plantUml",
        )
        assertTrue(endIndices[1] > endIndices[0], "A's end (outer) must come after B's end (inner); got:\n$plantUml")

        // Mermaid is untouched: its Note markers use the RAW, un-clamped ranges, since standalone
        // order-independent lines have nothing to desync — see this file's header comment.
        val mermaidLines = diagram.toMermaid().lines()
        val mm19Idx = mermaidLines.indexOfFirst { it.contains(": m19") }
        val closeBIdx = mermaidLines.indexOfFirst { it.contains("◀ B") }
        assertTrue(closeBIdx > mm19Idx, "Mermaid's close marker for B must still sit near its ORIGINAL end (message 20), not clamped to message 10 like PlantUML")
    }

    // ── Error notes ───────────────────────────────────────────────────────────────────────────

    @Test
    fun errorNotesRenderAsNoteLinesInBothDialects() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = "boom")),
            notes = listOf(DiagramNoteMark(participantIdx = 1, afterMsg = 0, text = "crash!", isError = true)),
        )

        assertTrue(diagram.toMermaid().contains("Note over B: crash!"))
        assertTrue(diagram.toPlantUml().contains("note right of B: crash!"))
    }

    // ── toSource dispatches by dialect ───────────────────────────────────────────────────────

    @Test
    fun toSourceDefaultsToTheSpecsOwnDialectAndCanBeOverridden() {
        val diagram = SeqDiagram(
            spec = SeqDiagramSpec(dialect = DiagramDialect.PLANTUML, participants = listOf(tagA, tagB)),
            participants = listOf(tagA, tagB),
            messages = listOf(msg(label = "hi")),
        )

        assertTrue(diagram.toSource().startsWith("@startuml"), "default dialect (PLANTUML here) must be used")
        assertTrue(diagram.toSource(DiagramDialect.MERMAID).startsWith("sequenceDiagram"), "explicit override must win")
    }
}
