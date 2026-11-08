package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3ManualActivation
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.toSource
import com.indagium.ui.Seq3SourceLineKind
import com.indagium.ui.seq3IndentSource
import com.indagium.ui.seq3SourceLineLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Two interleaved manual activation bars (B from `one` to `four`, C from `two` to `three`), which the
 *  source writes as `activate` / `deactivate` lines around the rows. */
internal fun seq3ActivationTestDocument() = Seq3Document(
    title = "Activations",
    lifelines = listOf(
        Seq3Lifeline("A", "Alpha", setOf("A"), 0),
        Seq3Lifeline("B", "Beta", setOf("B"), 1),
        Seq3Lifeline("C", "Gamma", setOf("C"), 2),
    ),
    messages = listOf(
        Seq3Message("m1", Seq3Match("A", "one"), "A", "B", "one"),
        Seq3Message("m2", Seq3Match("B", "two"), "B", "C", "two"),
        Seq3Message("m3", Seq3Match("C", "three"), "C", "B", "three", Seq3Kind.RETURN),
        Seq3Message("m4", Seq3Match("B", "four"), "B", "A", "four", Seq3Kind.RETURN),
    ),
    manualActivations = listOf(
        Seq3ManualActivation("barB", "B", "m1", endMessageId = "m4"),
        Seq3ManualActivation("barC", "C", "m2", endMessageId = "m3"),
    ),
)

/** The importer reads the indented activation text exactly like the flat text, in both dialects. */
class Seq3SourceActivationImportTest {
    @Test
    fun generatedActivationsAreIndentedAndImportToTheSameDocumentWithNoDiagnostics() {
        val document = seq3ActivationTestDocument()
        Seq3Dialect.entries.forEach { dialect ->
            val plain = document.toSource(dialect)
            val indented = seq3IndentSource(plain, dialect)
            assertNotEquals(plain, indented, "the activations nest the rows: ${dialect.name}")

            // An `activate` is followed, before its `deactivate`, by a row indented deeper than it.
            val levels = seq3SourceLineLevels(indented, dialect)
            val rows = indented.split('\n')
            val activateAt = rows.indexOfFirst { it.trim().startsWith("activate ") }
            assertTrue(activateAt >= 0, dialect.name)
            assertEquals(Seq3SourceLineKind.OPENER, levels[activateAt].kind, dialect.name)
            val inner = rows.drop(activateAt + 1).first { it.isNotBlank() && !it.trim().startsWith("'") && !it.trim().startsWith("%%") }
            assertTrue(
                inner.length - inner.trimStart().length > rows[activateAt].length - rows[activateAt].trimStart().length,
                "a row inside an activation is deeper than its activate (${dialect.name}): $inner",
            )

            val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(indented, dialect, document), dialect.name)
            val reference = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(plain, dialect, document), dialect.name)
            assertFalse(result.evidenceLoss, dialect.name)
            assertEquals(document, result.document, dialect.name)
            assertEquals(reference.document, result.document, dialect.name)
            assertEquals(reference.canonicalSource, result.canonicalSource, dialect.name)
        }
    }

    @Test
    fun handIndentedInterleavedActivationsImportLikeTheFlatText() {
        val document = Seq3Document(title = "x")
        val flat = mapOf(
            Seq3Dialect.PLANTUML to listOf(
                "@startuml", "participant \"Alpha\" as A", "participant \"Beta\" as B", "participant \"Gamma\" as C",
                "A -> B: one", "activate B", "B -> C: two", "activate C", "C --> B: three", "deactivate B", "B --> A: four", "deactivate C",
                "@enduml",
            ),
            Seq3Dialect.MERMAID to listOf(
                "sequenceDiagram", "participant A as Alpha", "participant B as Beta", "participant C as Gamma",
                "A->>B: one", "activate B", "B->>C: two", "activate C", "C-->>B: three", "deactivate B", "B-->>A: four", "deactivate C",
            ),
        )
        flat.forEach { (dialect, lines) ->
            val plain = lines.joinToString("\n")
            val indented = seq3IndentSource(plain, dialect)
            assertNotEquals(plain, indented, dialect.name)
            // `deactivate B` closes B's level while C stays open: the row after it is still one level in.
            val rows = indented.split('\n')
            val four = rows.first { it.trim().endsWith(": four") }
            val deactivateB = rows.first { it.trim() == "deactivate B" }
            assertTrue(four.length - four.trimStart().length > deactivateB.length - deactivateB.trimStart().length, dialect.name)

            val flatResult = importSeq3Source(plain, dialect, document)
            val indentedResult = importSeq3Source(indented, dialect, document)
            val reference = assertIs<Seq3SourceImportResult.Success>(flatResult, "${dialect.name}: $flatResult")
            val result = assertIs<Seq3SourceImportResult.Success>(indentedResult, "${dialect.name}: $indentedResult")
            assertEquals(reference.document.messages.map { it.labelTemplate }, result.document.messages.map { it.labelTemplate }, dialect.name)

            // Hand-written rows get fresh ids on every import, so the bars are compared by position.
            fun bars(document: Seq3Document) = document.manualActivations.map { bar ->
                Triple(
                    document.lifelines.single { it.id == bar.lifelineId }.name,
                    document.messages.indexOfFirst { it.id == bar.startMessageId },
                    document.messages.indexOfFirst { it.id == bar.endMessageId },
                )
            }
            assertEquals(bars(reference.document), bars(result.document), dialect.name)
            assertEquals(2, result.document.manualActivations.size, dialect.name)
        }
    }

    @Test
    fun theMermaidArrowShorthandStillImportsFromTheEditorsIndentedText() {
        val document = Seq3Document(title = "x")
        val plain = listOf(
            "sequenceDiagram", "participant A as Alpha", "participant B as Beta",
            "A->>+B: call", "B-->>-A: done",
        ).joinToString("\n")
        val indented = seq3IndentSource(plain, Seq3Dialect.MERMAID)
        assertEquals(
            listOf("sequenceDiagram", "    participant A as Alpha", "    participant B as Beta", "    A->>+B: call", "    B-->>-A: done").joinToString("\n"),
            indented,
            "the shorthand does not nest",
        )
        val reference = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(plain, Seq3Dialect.MERMAID, document))
        val result = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(indented, Seq3Dialect.MERMAID, document))
        assertEquals(reference.document.manualActivations.size, result.document.manualActivations.size)
        assertEquals(reference.document.messages.map { it.labelTemplate }, result.document.messages.map { it.labelTemplate })
    }
}
