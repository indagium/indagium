package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3MessageLabelStyle
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Operand
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.Seq3StateInvariant
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.toSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Seq3SourceImportTest {
    private fun document() = Seq3Document(
        title = "Original",
        lifelines = listOf(Seq3Lifeline("A", "Alpha", setOf("A"), 0), Seq3Lifeline("B", "Beta", setOf("B"), 1)),
        messages = listOf(
            Seq3Message("m1", Seq3Match("A", "ping"), "A", "B", "ping", occurrences = listOf(Seq3Occurrence(1, 1, "00:00:00", 1, 1, 'I', "ping"))),
        ),
    )

    @Test fun mermaidRoundTripRetainsEvidenceAndIdentity() {
        val before = document()
        val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(before.toSource(Seq3Dialect.MERMAID), Seq3Dialect.MERMAID, before))
        assertEquals(before.messages.single().occurrences, result.document.messages.single().occurrences)
        assertEquals("m1", result.document.messages.single().id)
        assertEquals(Seq3Kind.CALL, result.document.messages.single().kind)
        assertFalse(result.evidenceLoss)
        assertTrue(result.canonicalSource.contains("%% indagium-seq3:v1"))
    }

    @Test fun plantUmlMarkedEditUpdatesOnlyEditableMessageFields() {
        val before = document()
        val edited = before.toSource(Seq3Dialect.PLANTUML).replace("A -> B: ping", "A -> B: pong")
        val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(edited, Seq3Dialect.PLANTUML, before))
        assertEquals("pong", result.document.messages.single().labelTemplate)
        assertEquals(before.messages.single().occurrences, result.document.messages.single().occurrences)
    }

    @Test fun invalidSourceDoesNotProducePartialDocument() {
        val result = importSeq3Source("sequenceDiagram\n    made up grammar", Seq3Dialect.MERMAID, document())
        assertIs<Seq3SourceImportResult.Failure>(result)
    }

    @Test fun unknownVersionAndDuplicateMarkersFailAtomically() {
        val before = document()
        val source = before.toSource(Seq3Dialect.MERMAID)
        val unknownVersion = source.replaceFirst("indagium-seq3:v1", "indagium-seq3:v9")
        val versionFailure = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(unknownVersion, Seq3Dialect.MERMAID, before),
        )
        assertTrue(versionFailure.diagnostics.single().line > 0)
        val marker = source.lineSequence().first { it.contains("indagium-seq3:v1") }
        val duplicate = source.replaceFirst(marker, "$marker\n$marker")
        val duplicateFailure = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(duplicate, Seq3Dialect.MERMAID, before),
        )
        assertTrue(duplicateFailure.diagnostics.any { it.line > 0 && it.message.contains("Marker") })
        assertEquals(before, document(), "the pure importer must not mutate its input")
    }

    @Test fun markedEvidenceReorderAndInconsistentRepeatedEditFailAtomically() {
        val twoMessages = document().copy(messages = listOf(
            document().messages.single(),
            Seq3Message("m2", Seq3Match("A", "pong"), "A", "B", "pong"),
        ))
        val lines = twoMessages.toSource(Seq3Dialect.MERMAID).lines().toMutableList()
        val first = lines.indexOfFirst { it.contains("A->>B: ping") }
        val second = lines.indexOfFirst { it.contains("A->>B: pong") }
        val firstBlock = lines.subList(first - 1, first + 1).toList()
        val secondBlock = lines.subList(second - 1, second + 1).toList()
        lines[first - 1] = secondBlock[0]
        lines[first] = secondBlock[1]
        lines[second - 1] = firstBlock[0]
        lines[second] = firstBlock[1]
        val reordered = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(lines.joinToString("\n"), Seq3Dialect.MERMAID, twoMessages),
        )
        assertTrue(reordered.diagnostics.any { it.line > 0 && it.message.contains("reordered") })

        val repeated = document().copy(messages = listOf(
            Seq3Message(
                "m1", Seq3Match("A", "value {n}", listOf(Seq3Capture("n", Seq3CaptureSource.AUTHOR))),
                "A", "B", "value {n}", repeat = Seq3Repeat.EVERY,
                occurrences = listOf(
                    Seq3Occurrence(1, 1, "00", 1, 1, 'I', "one", mapOf("n" to "one")),
                    Seq3Occurrence(2, 2, "00", 1, 1, 'I', "two", mapOf("n" to "two")),
                ),
            ),
        ))
        val inconsistent = repeated.toSource(Seq3Dialect.MERMAID).replace("value one", "edited")
        val inconsistentFailure = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(inconsistent, Seq3Dialect.MERMAID, repeated),
        )
        assertTrue(inconsistentFailure.diagnostics.any { it.line > 0 && it.message.contains("inconsistent") })
        assertEquals("value {n}", repeated.messages.single().labelTemplate)
    }

    @Test fun unmarkedArrowIsAuthoredAndCandidateBoundsAreCheckedBeforeSuccess() {
        val authored = importSeq3Source(
            "sequenceDiagram\n    participant A as Alpha\n    participant B as Beta\n    A->>B: authored",
            Seq3Dialect.MERMAID,
            Seq3Document(),
        )
        val authoredSuccess = assertIs<Seq3SourceImportResult.Success>(authored)
        assertEquals(Seq3Authoring.EDITED, authoredSuccess.document.messages.single().authoring)

        val oversized = buildString {
            append("sequenceDiagram\n")
            repeat(129) { index -> append("    participant p$index as p$index\n") }
        }
        val boundsFailure = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(oversized, Seq3Dialect.MERMAID, Seq3Document()),
        )
        assertTrue(boundsFailure.diagnostics.single().line > 0)
        assertTrue(boundsFailure.diagnostics.single().message.contains("too many lifelines"))
    }

    @Test fun unmarkedLifecycleAndSelfStatementsBecomeAuthoredKinds() {
        val existing = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("A", "Alpha", setOf("A"), 0),
                Seq3Lifeline("B", "Beta", setOf("B"), 1),
            ),
        )
        val mermaidCreate = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source(
                "sequenceDiagram\n    create participant B as Beta\n    A->>B: spawn",
                Seq3Dialect.MERMAID,
                existing,
            ),
        )
        assertEquals(Seq3Kind.CREATE, mermaidCreate.document.messages.single().kind)

        val plantDestroy = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source("@startuml\nA -> B: stop\ndestroy B\n@enduml", Seq3Dialect.PLANTUML, existing),
        )
        assertEquals(Seq3Kind.DESTROY, plantDestroy.document.messages.single().kind)

        val self = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source("sequenceDiagram\n    A->>A: recurse", Seq3Dialect.MERMAID, existing),
        )
        assertEquals(Seq3Kind.SELF, self.document.messages.single().kind)
        assertTrue(self.document.messages.all { it.authoring == Seq3Authoring.EDITED })
    }

    @Test fun removingMarkedEvidenceRequestsConfirmationInsteadOfSilentlyDroppingIt() {
        val before = document()
        val result = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source("sequenceDiagram\n    participant A as Alpha\n    participant B as Beta", Seq3Dialect.MERMAID, before),
        )
        assertTrue(result.evidenceLoss)
        assertTrue(result.document.messages.isEmpty(), "preview must carry the actual confirmed-removal candidate without mutating the input")
        assertEquals(1, before.messages.size)
    }

    @Test fun sourceMarkersCarryVersionedIdentity() {
        val source = document().toSource(Seq3Dialect.MERMAID)
        assertTrue(source.contains("%% indagium-seq3:v1 "))
        assertTrue(source.contains("\"m1\"").not(), "identity payload must be base64url, not visible JSON")
    }

    @Test fun occurrenceLabelsAreNotMistakenForInconsistentRepeatedEdits() {
        val repeated = document().copy(messages = listOf(
            Seq3Message(
                "m1", Seq3Match("A", "value {n}", listOf(Seq3Capture("n", Seq3CaptureSource.AUTHOR))), "A", "B", "value {n}",
                repeat = com.indagium.diagram3.Seq3Repeat.EVERY,
                occurrences = listOf(
                    Seq3Occurrence(1, 1, "00", 1, 1, 'I', "value one", mapOf("n" to "one")),
                    Seq3Occurrence(2, 2, "00", 1, 1, 'I', "value two", mapOf("n" to "two")),
                ),
            ),
        ))
        val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(repeated.toSource(Seq3Dialect.MERMAID), Seq3Dialect.MERMAID, repeated))
        assertEquals("value {n}", result.document.messages.single().labelTemplate)
    }

    @Test fun markedDocumentStructuresRetainIdsAndAcceptTextEdits() {
        val before = document().copy(
            fragments = listOf(Seq3Fragment("f", Seq3FragmentKind.LOOP, "again", listOf("m1"))),
            notes = listOf(Seq3Note("n", "explain", listOf("m1"))),
            delays = listOf(Seq3Delay("d", "m1", "wait")),
        )
        val edited = before.toSource(Seq3Dialect.PLANTUML)
            .replace("loop again", "loop once more")
            .replace("note over A,B: explain", "note over A,B: changed")
            .replace("...wait...", "...later...")
        val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(edited, Seq3Dialect.PLANTUML, before))
        assertEquals("once more", result.document.fragments.single().label)
        assertEquals("changed", result.document.notes.single().text)
        assertEquals("later", result.document.delays.single().label)
    }

    @Test fun lifecycleMarkersBindToArrowsInBothDialects() {
        val before = document().copy(messages = listOf(
            Seq3Message("create", Seq3Match("A", "spawn"), "A", "B", "spawn", kind = Seq3Kind.CREATE,
                occurrences = listOf(Seq3Occurrence(1, 1, "00", 1, 1, 'I', "spawn"))),
            Seq3Message("destroy", Seq3Match("A", "stop"), "A", "B", "stop", kind = Seq3Kind.DESTROY,
                occurrences = listOf(Seq3Occurrence(2, 2, "00", 1, 1, 'I', "stop"))),
        ))
        Seq3Dialect.entries.forEach { dialect ->
            val result = assertIs<Seq3SourceImportResult.Success>(
                importSeq3Source(before.toSource(dialect), dialect, before),
            )
            assertEquals(listOf(Seq3Kind.CREATE, Seq3Kind.DESTROY), result.document.messages.map { it.kind }, dialect.name)
        }
    }

    @Test fun removingVisibleMarkedStructuresDoesNotRestoreThem() {
        val before = document().copy(
            fragments = listOf(Seq3Fragment("fragment", Seq3FragmentKind.LOOP, "again", listOf("m1"))),
            notes = listOf(Seq3Note("note", "explain", listOf("m1"))),
            delays = listOf(Seq3Delay("delay", "m1", "wait")),
        )
        val emitted = before.toSource(Seq3Dialect.MERMAID).lines()
        val stripped = buildList {
            emitted.forEachIndexed { index, line ->
                val statement = line.trim()
                if (statement == "sequenceDiagram") add(line)
                if (statement.startsWith("participant ")) {
                    add(emitted[index - 1])
                    add(line)
                }
                if (statement.contains("->>")) {
                    add(emitted[index - 1])
                    add(line)
                }
            }
        }.joinToString("\n")

        val result = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source(stripped, Seq3Dialect.MERMAID, before),
        )
        assertTrue(result.document.fragments.isEmpty())
        assertTrue(result.document.notes.isEmpty())
        assertTrue(result.document.delays.isEmpty())
        assertFalse(result.evidenceLoss)
    }

    @Test fun markedLifecycleAndSelfEditsDeriveKindsAndRejectViolations() {
        val call = document()
        Seq3Dialect.entries.forEach { dialect ->
            val lines = call.toSource(dialect).lines().toMutableList()
            val declaration = lines.indexOfFirst { it.contains("participant") && it.contains("Beta") }
            lines.removeAt(declaration)
            lines.removeAt(declaration - 1)
            val arrow = lines.indexOfFirst { it.contains("ping") && it.contains(":") }
            val create = if (dialect == Seq3Dialect.MERMAID) {
                "    create participant B as Beta"
            } else {
                "create B"
            }
            lines.add(arrow - 1, create)
            val imported = assertIs<Seq3SourceImportResult.Success>(
                importSeq3Source(lines.joinToString("\n"), dialect, call),
            )
            assertEquals(Seq3Kind.CREATE, imported.document.messages.single().kind)
        }

        val self = call.copy(messages = listOf(
            call.messages.single().copy(toLifelineId = "A", kind = Seq3Kind.SELF),
        ))
        val selfEdited = self.toSource(Seq3Dialect.MERMAID).replace("A->>A: ping", "A->>B: ping")
        val selfImport = assertIs<Seq3SourceImportResult.Success>(
            importSeq3Source(selfEdited, Seq3Dialect.MERMAID, self),
        )
        assertEquals(Seq3Kind.CALL, selfImport.document.messages.single().kind)

        val two = call.copy(messages = listOf(
            call.messages.single(),
            Seq3Message("later", Seq3Match("A", "later"), "A", "B", "later"),
        ))
        val invalidLines = two.toSource(Seq3Dialect.MERMAID).lines().toMutableList()
        val later = invalidLines.indexOfFirst { it.contains("A->>B: later") }
        invalidLines.add(later - 1, "    create participant B as Beta")
        val violation = assertIs<Seq3SourceImportResult.Failure>(
            importSeq3Source(invalidLines.joinToString("\n"), Seq3Dialect.MERMAID, two),
        )
        assertTrue(violation.diagnostics.any { it.line > 0 && it.message.contains("Lifecycle violation") })
    }

    @Test fun comprehensiveVisibleDocumentIsSemanticallyStableInBothDialects() {
        fun occurrence(id: Int, time: Long, values: Map<String, String> = emptyMap()) =
            Seq3Occurrence(id, time, "00:00:${id.toString().padStart(2, '0')}", 1, 1, 'I', "row $id", values, elapsedMillis = time)
        val document = Seq3Document(
            title = "Build: <all> #1",
            lifelines = listOf(
                Seq3Lifeline("A", "actor.A", setOf("A"), 0, kind = Seq3LifelineKind.ACTOR),
                Seq3Lifeline("B", "service.B", setOf("B"), 1),
                Seq3Lifeline("C", "worker.C", setOf("C"), 2),
            ),
            messages = listOf(
                Seq3Message(
                    "create", Seq3Match("A", "spawn"), "A", "B", "spawn", Seq3Kind.CREATE,
                    repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(1, 1)),
                ),
                Seq3Message(
                    "every",
                    Seq3Match("A", "call {n}", listOf(Seq3Capture("n", Seq3CaptureSource.AUTHOR))),
                    "A", "B", "call {n}", repeat = Seq3Repeat.EVERY,
                    occurrences = listOf(
                        occurrence(2, 2, mapOf("n" to "one")),
                        occurrence(3, 3, mapOf("n" to "two")),
                    ),
                ),
                Seq3Message("return", Seq3Match("B", "ok"), "B", "A", "ok", Seq3Kind.RETURN, repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(4, 4))),
                Seq3Message("firstlast", Seq3Match("A", "fan"), "A", "C", "fan", repeat = Seq3Repeat.FIRST_LAST,
                    occurrences = listOf(occurrence(5, 5), occurrence(6, 6), occurrence(7, 7))),
                Seq3Message(
                    "collapse",
                    Seq3Match("C", "state {s}", listOf(Seq3Capture("s", Seq3CaptureSource.AUTHOR))),
                    "C", "A", "state {s}", repeat = Seq3Repeat.COLLAPSE_ABOVE,
                    occurrences = listOf(
                        occurrence(8, 8, mapOf("s" to "warm")),
                        occurrence(9, 9, mapOf("s" to "hot")),
                        occurrence(10, 10, mapOf("s" to "hot")),
                        occurrence(11, 11, mapOf("s" to "hot")),
                    ),
                ),
                Seq3Message(
                    "destroy", Seq3Match("A", "stop"), "A", "B", "stop", Seq3Kind.DESTROY,
                    repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(12, 12)),
                ),
                Seq3Message("tail", Seq3Match("A", "finish"), "A", "C", "finish", repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(13, 13))),
            ),
            fragments = listOf(
                Seq3Fragment(
                    "alt", Seq3FragmentKind.ALT, "fast", listOf("every", "return"),
                    elseOperands = listOf(Seq3Operand("alt-op", "slow", "return")),
                ),
                Seq3Fragment(
                    "par", Seq3FragmentKind.PAR, "parallel", listOf("firstlast", "collapse"),
                    elseOperands = listOf(Seq3Operand("par-op", "other", "collapse")),
                ),
                Seq3Fragment(
                    "critical", Seq3FragmentKind.CRITICAL, "lock", listOf("destroy", "tail"),
                    elseOperands = listOf(Seq3Operand("critical-op", "retry", "tail")),
                ),
                Seq3Fragment("group", Seq3FragmentKind.NEG, "forbidden", listOf("return")),
                Seq3Fragment("ref", Seq3FragmentKind.REF, "Details", listOf("firstlast")),
            ),
            notes = listOf(Seq3Note("note", "explain <both>", listOf("every", "return", "firstlast"))),
            delays = listOf(Seq3Delay("delay", "every", "wait", afterOccurrenceEntryId = 3)),
            stateInvariants = listOf(Seq3StateInvariant("state", "collapse", "s")),
            showSequenceNumbers = true,
            showTimestamps = true,
            showElapsed = true,
            showActivations = true,
            messageLabelStyle = Seq3MessageLabelStyle.UML_SIGNATURE,
        )
        Seq3Dialect.entries.forEach { dialect ->
            val source = document.toSource(dialect)
            val result = assertIs<Seq3SourceImportResult.Success>(
                importSeq3Source(source, dialect, document),
                dialect.name,
            )
            assertFalse(result.evidenceLoss, dialect.name)
            assertEquals(document, result.document, dialect.name)
            assertEquals(result.canonicalSource, result.document.toSource(dialect).trimEnd('\n'), dialect.name)
        }
    }
}
