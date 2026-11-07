package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3ManualActivation
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
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.seq3IndentSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The editor shows `seq3IndentSource(toSource(...))`; the importer must read it exactly like the
 *  unindented export. */
class Seq3SourceIndentImportTest {
    private fun document(): Seq3Document {
        fun occurrence(id: Int, time: Long, values: Map<String, String> = emptyMap()) =
            Seq3Occurrence(id, time, "00:00:${id.toString().padStart(2, '0')}", 1, 1, 'I', "row $id", values, elapsedMillis = time)
        return Seq3Document(
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
                Seq3Fragment("inner", Seq3FragmentKind.LOOP, "inner", listOf("every")),
                Seq3Fragment("group", Seq3FragmentKind.NEG, "forbidden", listOf("return")),
                Seq3Fragment("ref", Seq3FragmentKind.REF, "Details", listOf("firstlast")),
            ),
            notes = listOf(Seq3Note("note", "explain <both>", listOf("every", "return", "firstlast"))),
            delays = listOf(Seq3Delay("delay", "every", "wait", afterOccurrenceEntryId = 3)),
            stateInvariants = listOf(Seq3StateInvariant("state", "collapse", "s")),
            // Phase 1 (manual activation bars): a manual bar on C, drawn independent of
            // showActivations and always exported alongside the auto activate/deactivate lines —
            // `result` must keep it via `existing.copy(...)`, so an unchanged round trip still
            // matches on both the document and its re-derived source.
            manualActivations = listOf(Seq3ManualActivation("bar", lifelineId = "C", startMessageId = "tail")),
            showSequenceNumbers = true,
            showTimestamps = true,
            showElapsed = true,
            showActivations = true,
            messageLabelStyle = Seq3MessageLabelStyle.UML_SIGNATURE,
        )
    }

    @Test fun indentedSourceImportsToTheSameDocumentInBothDialects() {
        val document = document()
        Seq3Dialect.entries.forEach { dialect ->
            val plain = document.toSource(dialect)
            val indented = seq3IndentSource(plain, dialect)
            assertNotEquals(plain, indented, "the comprehensive document has nested content to indent: ${dialect.name}")
            assertTrue(indented.lines().any { it.startsWith("        ") }, dialect.name)
            val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(indented, dialect, document), dialect.name)
            val reference = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(plain, dialect, document), dialect.name)
            assertFalse(result.evidenceLoss, dialect.name)
            assertEquals(document, result.document, dialect.name)
            assertEquals(reference.document, result.document, dialect.name)
            assertEquals(reference.canonicalSource, result.canonicalSource, dialect.name)
        }
    }

    @Test fun anIndentedDraftIsNeitherDirtyStaleNorADiagnosticAndAppliesAsANoOp() {
        val root = createTempDirectory("indagium-seq3-indent").toFile()
        try {
            val state = AppState(
                File(root, "state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            ).also {
                it.tabs = listOf(mkTab("log", "sample.log", emptyList()))
                it.activateTab("log")
            }
            val id = requireNotNull(state.seq3Sessions.beginEmpty("log"))
            val view = requireNotNull(state.seq3Sessions.viewState(id))
            // The live path (which lets the source own the lifelines) already rejects create and
            // destroy rows for unindented source, so they stay out of this draft check.
            val full = document()
            val lifecycle = setOf("create", "destroy")
            val document = full.copy(
                messages = full.messages.filter { it.id !in lifecycle },
                fragments = full.fragments.map { it.copy(messageIds = it.messageIds.filter { id -> id !in lifecycle }) }
                    .filter { it.messageIds.isNotEmpty() },
            )
            assertTrue(state.seq3Sessions.applyCommand(id, Seq3Command.ReplaceDocument(document)))
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            Seq3Dialect.entries.forEach { dialect ->
                assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, dialect))
                val current = state.seq3Sessions.sessions.single { it.id == id }.document
                assertEquals(seq3IndentSource(current.toSource(dialect), dialect), view.sourceDraft, dialect.name)
                assertEquals(view.sourceDraft, view.sourceDraftCanonical, dialect.name)
                assertFalse(view.sourceDraftDirty, dialect.name)
                assertFalse(view.sourceDraftStale, dialect.name)
                val liveResult = state.seq3Sessions.applyLiveSourceDraft(id, view.sourceDraft, dialect)
                val live = assertIs<Seq3SourceImportResult.Success>(
                    liveResult, "${dialect.name}: ${(liveResult as? Seq3SourceImportResult.Failure)?.diagnostics}",
                )
                assertFalse(live.evidenceLoss, dialect.name)
                assertEquals(current, state.seq3Sessions.sessions.single { it.id == id }.document, dialect.name)
                assertTrue(view.sourceDiagnostics.isEmpty(), dialect.name)
                assertFalse(view.sourceDraftDirty, dialect.name)
                assertFalse(view.sourceDraftStale, dialect.name)
                assertEquals(view.sourceDraft, view.sourceDraftCanonical, dialect.name)
                val manual = assertIs<Seq3SourceImportResult.Success>(
                    state.seq3Sessions.importSource(id, view.sourceDraft, dialect), dialect.name,
                )
                assertFalse(manual.evidenceLoss, dialect.name)
                assertEquals(view.sourceDraft.trimEnd('\n'), manual.canonicalSource, dialect.name)
            }
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        } finally {
            root.deleteRecursively()
        }
    }
}
