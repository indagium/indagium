package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.importSeq3Source
import com.indagium.ui.seq3IndentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The user's source with an activation that is opened before an `opt` and closed inside it, indented
 *  by the editor's rule (every closer at its opener), is read by the importer like the flat text. */
class Seq3SourceCrossingImportTest {
    private val flat = mapOf(
        Seq3Dialect.MERMAID to listOf(
            "sequenceDiagram",
            "title NewDiagram",
            "actor Alias as Name",
            "participant Alias_2 as Name",
            "participant Alias_3 as Name3",
            "Alias->>Alias_2: [#35;1] message label",
            "activate Alias",
            "activate Alias_2",
            "Alias-->>Alias_2: [#35;2] aadsd",
            "deactivate Alias",
            "opt condition",
            "Alias_3->>Alias_2: [#35;3] message label",
            "deactivate Alias_2",
            "activate Alias_2",
            "deactivate Alias_2",
            "Note over Alias_2,Alias_3: note text",
            "end",
        ),
        Seq3Dialect.PLANTUML to listOf(
            "@startuml",
            "title NewDiagram",
            "actor \"Name\" as Alias",
            "participant \"Name\" as Alias_2",
            "participant \"Name3\" as Alias_3",
            "Alias -> Alias_2: [#1] message label",
            "activate Alias",
            "activate Alias_2",
            "Alias --> Alias_2: [#2] aadsd",
            "deactivate Alias",
            "opt condition",
            "Alias_3 -> Alias_2: [#3] message label",
            "deactivate Alias_2",
            "activate Alias_2",
            "deactivate Alias_2",
            "note over Alias_2, Alias_3: note text",
            "end",
            "@enduml",
        ),
    )

    private fun import(source: String, dialect: Seq3Dialect): Seq3SourceImportResult.Success {
        val result = importSeq3Source(source, dialect, Seq3Document(title = "x"))
        return assertIs<Seq3SourceImportResult.Success>(result, "${dialect.name}: $result\n$source")
    }

    /** The canonical source without its identity-marker lines, whose ids are new on every import. */
    private fun withoutMarkers(source: String) = source.lines().filterNot { "indagium-seq3" in it }

    /** The document without the ids a hand-written source gets afresh on every import. */
    private fun shape(document: Seq3Document) = listOf(
        document.title,
        document.lifelines.map { Triple(it.id, it.name, it.kind) },
        document.messages.map { listOf(it.fromLifelineId, it.toLifelineId, it.labelTemplate, it.kind) },
        document.manualActivations.map { bar ->
            Triple(
                bar.lifelineId,
                document.messages.indexOfFirst { it.id == bar.startMessageId },
                document.messages.indexOfFirst { it.id == bar.endMessageId },
            )
        },
        document.fragments.map { listOf(it.kind, it.label, it.elseOperands.size, it.messageIds.size) },
        document.notes.size,
    )

    @Test
    fun theReindentedUserExampleImportsWithNoDiagnosticsLikeTheFlatText() {
        flat.forEach { (dialect, lines) ->
            val plain = lines.joinToString("\n")
            val indented = seq3IndentSource(plain, dialect)
            assertNotEquals(plain, indented, dialect.name)
            // The closers really are at their openers' indent now.
            val rows = indented.split('\n')
            fun indentOf(text: String, occurrence: Int) =
                rows.filter { it.trim() == text }[occurrence].let { it.length - it.trimStart().length }
            assertEquals(indentOf("activate Alias_2", 0), indentOf("deactivate Alias_2", 0), dialect.name)
            assertEquals(indentOf("activate Alias_2", 1), indentOf("deactivate Alias_2", 1), dialect.name)
            assertEquals(indentOf("opt condition", 0), indentOf("end", 0), dialect.name)

            val reference = import(plain, dialect)
            val result = import(indented, dialect)
            assertFalse(result.evidenceLoss, dialect.name)
            assertEquals(shape(reference.document), shape(result.document), dialect.name)
            assertEquals(withoutMarkers(reference.canonicalSource), withoutMarkers(result.canonicalSource), dialect.name)
            assertTrue(result.document.messages.size >= 3, dialect.name)
            // Importing what the editor shows again, then re-indenting it, is stable.
            assertEquals(indented, seq3IndentSource(indented, dialect), dialect.name)
        }
    }

    @Test
    fun theSourceAsTheEditorUsedToIndentItStillImportsToTheSameDocument() {
        val old = listOf(
            "sequenceDiagram",
            "    title NewDiagram",
            "    actor Alias as Name",
            "    participant Alias_2 as Name",
            "    participant Alias_3 as Name3",
            "    Alias->>Alias_2: [#35;1] message label",
            "    activate Alias",
            "        activate Alias_2",
            "            Alias-->>Alias_2: [#35;2] aadsd",
            "    deactivate Alias",
            "        opt condition",
            "            Alias_3->>Alias_2: [#35;3] message label",
            "    deactivate Alias_2",
            "        activate Alias_2",
            "        deactivate Alias_2",
            "        Note over Alias_2,Alias_3: note text",
            "    end",
        ).joinToString("\n")
        val reindented = seq3IndentSource(old, Seq3Dialect.MERMAID)
        assertNotEquals(old, reindented)
        assertEquals(shape(import(old, Seq3Dialect.MERMAID).document), shape(import(reindented, Seq3Dialect.MERMAID).document))
    }
}
