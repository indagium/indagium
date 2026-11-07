package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Operand
import com.indagium.diagram3.Seq3SourceFix
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.toSource
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.SourceSnippetField
import com.indagium.ui.mkTab
import com.indagium.ui.seq3SourceBlockSnippet
import com.indagium.ui.seq3IndentSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The importer's grammar for hand-typed source: accepted forms, typed dividers, and diagnostics. */
class Seq3SourceGrammarTest {
    private val mermaid = Seq3Dialect.MERMAID
    private val plant = Seq3Dialect.PLANTUML

    private fun source(dialect: Seq3Dialect, vararg body: String): String = when (dialect) {
        Seq3Dialect.MERMAID -> (listOf("sequenceDiagram") + body.map { "    $it" }).joinToString("\n")
        Seq3Dialect.PLANTUML -> (listOf("@startuml") + body + "@enduml").joinToString("\n")
    }

    private fun arrow(dialect: Seq3Dialect, from: String, to: String, label: String) =
        if (dialect == mermaid) "$from->>$to: $label" else "$from -> $to: $label"

    private fun ok(
        source: String,
        dialect: Seq3Dialect,
        existing: Seq3Document = Seq3Document(),
        live: Boolean = false,
    ) = assertIs<Seq3SourceImportResult.Success>(importSeq3Source(source, dialect, existing, live), source)

    private fun rejected(
        source: String,
        dialect: Seq3Dialect,
        existing: Seq3Document = Seq3Document(),
        live: Boolean = false,
    ) = assertIs<Seq3SourceImportResult.Failure>(importSeq3Source(source, dialect, existing, live), source).diagnostics

    private val declarations = arrayOf("participant A", "participant B")

    // -- Accepted forms ---------------------------------------------------------------------

    @Test fun plantUmlParticipantFormsAreAccepted() {
        val result = ok(
            source(plant, "participant Alice", "actor Bob", "participant Carol as C", "participant \"Dave X\" as D",
                "Alice -> Bob : hi", "Bob --> Alice : ok", "C ->> D:async"),
            plant,
        )
        val lifelines = result.document.lifelines
        assertEquals(listOf("Alice", "Bob", "C", "D"), lifelines.map { it.id })
        assertEquals(listOf("Alice", "Bob", "Carol", "Dave X"), lifelines.map { it.name })
        assertEquals(Seq3LifelineKind.ACTOR, lifelines[1].kind)
        assertEquals(listOf("hi", "ok", "async"), result.document.messages.map { it.labelTemplate })
        assertEquals(listOf(Seq3Kind.CALL, Seq3Kind.RETURN, Seq3Kind.ASYNC), result.document.messages.map { it.kind })
    }

    @Test fun mermaidParticipantAndArrowFormsAreAccepted() {
        val result = ok(
            source(mermaid, "participant A", "actor B", "participant C as Carol", "A ->> B : hi", "B-->>A: ok",
                "A -) C:async", "A->>A: self"),
            mermaid,
        )
        assertEquals(listOf("A", "B", "C"), result.document.lifelines.map { it.id })
        assertEquals(listOf("A", "B", "Carol"), result.document.lifelines.map { it.name })
        assertEquals(Seq3LifelineKind.ACTOR, result.document.lifelines[1].kind)
        assertEquals(listOf("hi", "ok", "async", "self"), result.document.messages.map { it.labelTemplate })
        assertEquals(Seq3Kind.SELF, result.document.messages.last().kind)
    }

    @Test fun aParticipantNamedLikeAKeywordIsAMessageNotABlock() {
        Seq3Dialect.entries.forEach { dialect ->
            val arrow = if (dialect == mermaid) "->>" else "->"
            val result = ok(
                source(dialect, "participant Loop", "participant Partner", "participant Group",
                    "Loop $arrow Partner : x", "Partner $arrow Group : y"),
                dialect,
            )
            assertTrue(result.document.fragments.isEmpty(), dialect.name)
            assertEquals(listOf("x", "y"), result.document.messages.map { it.labelTemplate }, dialect.name)
        }
        // Without a colon there is no message either: the line is reported, not read as `loop`.
        val diagnostics = rejected(source(plant, "participant Loop", "participant B", "Loop -> B"), plant)
        assertEquals(listOf(4), diagnostics.map { it.line })
        assertTrue(diagnostics.single().message.startsWith("Not supported yet"), diagnostics.toString())
    }

    @Test fun mermaidActivationShorthandBecomesActivationBars() {
        val result = ok(
            source(mermaid, "participant A", "participant B", "A->>+B: start", "B-->>-A: done"),
            mermaid,
        )
        val bar = result.document.manualActivations.single()
        assertEquals("B", bar.lifelineId)
        val (start, done) = result.document.messages
        assertEquals(start.id, bar.startMessageId)
        assertEquals(done.id, bar.endMessageId)
        assertFalse(result.document.showActivations)
    }

    @Test fun notesAcceptCaseSpacingAndMultipleParticipants() {
        Seq3Dialect.entries.forEach { dialect ->
            listOf("Note over A, B : explain", "NOTE OVER A,B: explain", "note over A ,B:explain").forEach { note ->
                val result = ok(
                    source(dialect, *declarations, arrow(dialect, "A", "B", "ping"), note),
                    dialect,
                )
                assertEquals("explain", result.document.notes.single().text, "$dialect: $note")
            }
        }
    }

    @Test fun autonumberTurnsOnMessageNumbering() {
        Seq3Dialect.entries.forEach { dialect ->
            val result = ok(
                source(dialect, "autonumber", *declarations, arrow(dialect, "A", "B", "ping")),
                dialect,
            )
            assertTrue(result.document.showSequenceNumbers, dialect.name)
            val plain = ok(source(dialect, *declarations, arrow(dialect, "A", "B", "ping")), dialect)
            assertFalse(plain.document.showSequenceNumbers, dialect.name)
            val args = rejected(source(dialect, "autonumber 10", *declarations), dialect)
            assertEquals(2, args.single().line, dialect.name)
            assertEquals("Not supported yet: autonumber 10", args.single().message, dialect.name)
        }
    }

    // -- Lines that used to vanish ----------------------------------------------------------

    @Test fun everyFormerlySilentLineNowFailsOnItsOwnLine() {
        val plantLines = listOf(
            "participant Foo order 10",
            "note left of A: x",
            "activate A #red",
            "...",
            "Loop -> B",
            "hnote over A: x",
            "end note",
        )
        plantLines.forEach { line ->
            val diagnostics = rejected(source(plant, "participant A", "participant B", line), plant)
            assertEquals(listOf(4), diagnostics.map { it.line }, line)
            assertTrue(diagnostics.single().message.startsWith("Not supported yet: "), "$line: ${diagnostics.single().message}")
        }
        val mermaidLines = listOf(
            "Note left of A: x",
            "activate A #red",
            "box Aqua group",
            "links A: {}",
            "A->B: open arrow",
        )
        mermaidLines.forEach { line ->
            val diagnostics = rejected(source(mermaid, "participant A", "participant B", line), mermaid)
            assertEquals(listOf(4), diagnostics.map { it.line }, line)
            assertTrue(diagnostics.single().message.startsWith("Not supported yet: "), "$line: ${diagnostics.single().message}")
        }
        val garbage = rejected(source(mermaid, "participant A", "made up grammar"), mermaid)
        assertEquals(listOf(3), garbage.map { it.line })
        assertEquals("Unrecognized statement: made up grammar", garbage.single().message)
    }

    @Test fun anUnmarkedMermaidRectIsReportedAndItsEndClosesItAlone() {
        val diagnostics = rejected(
            source(
                mermaid, "participant A", "participant B", "loop x", "A->>B: m", "rect rgb(1,2,3)", "A->>B: n", "end", "end",
            ),
            mermaid,
        )
        assertEquals(1, diagnostics.size, diagnostics.toString())
        assertEquals(6, diagnostics.single().line)
        assertTrue(diagnostics.single().message.startsWith("Not supported yet: rect"))
    }

    @Test fun secondDiagramBlockIsReportedOnItsLine() {
        val mermaidDiagnostics = rejected("sequenceDiagram\n    participant A\nsequenceDiagram\n    participant B", mermaid)
        assertEquals(listOf(3), mermaidDiagnostics.map { it.line })
        assertEquals("Only one diagram per source.", mermaidDiagnostics.single().message)
        val plantDiagnostics = rejected("@startuml\nparticipant A\n@enduml\n@startuml\nparticipant B\n@enduml", plant)
        assertEquals(listOf(4), plantDiagnostics.map { it.line })
        assertEquals("Only one diagram per source.", plantDiagnostics.single().message)
    }

    @Test fun anEmptyBlockAsksForAMessageOnItsOpeningLine() {
        Seq3Dialect.entries.forEach { dialect ->
            val diagnostics = rejected(source(dialect, *declarations, "critical lock", "end"), dialect)
            assertEquals(listOf(4), diagnostics.map { it.line }, dialect.name)
            assertEquals("Add a message inside critical.", diagnostics.single().message, dialect.name)
        }
    }

    @Test fun anOversizedDiagramReportsItsRowCount() {
        val label = "x".repeat(300)
        val rows = (1..2000).joinToString("\n") { "    A->>B: $label$it" }
        val diagnostics = rejected("sequenceDiagram\n    participant A\n    participant B\n$rows", mermaid)
        val single = diagnostics.single()
        assertEquals("Too many rows for one diagram (2000).", single.message)
        assertEquals(2003, single.line)
    }

    // -- The undeclared-participant fix -----------------------------------------------------

    @Test fun anUndeclaredParticipantNamesItselfAndCarriesAFixThatWorks() {
        // Mermaid: after the last declaration, copying its indent.
        val mermaidText = "sequenceDiagram\n    participant A as A\n    A->>Pay: x"
        val mermaidDiagnostic = rejected(mermaidText, mermaid).single()
        assertEquals(3, mermaidDiagnostic.line)
        assertEquals("Participant 'Pay' isn't declared.", mermaidDiagnostic.message)
        val mermaidFix = assertNotNull(mermaidDiagnostic.fix)
        assertEquals(Seq3SourceFix("Add participant Pay", 3, "    participant Pay as Pay"), mermaidFix)

        val plantText = "@startuml\nparticipant \"A\" as A\nA -> Pay : x\n@enduml"
        val plantFix = assertNotNull(rejected(plantText, plant).single().fix)
        assertEquals(Seq3SourceFix("Add participant Pay", 3, "participant \"Pay\" as Pay"), plantFix)

        // Nothing declared yet: after the title, and both endpoints are reported.
        val bare = "sequenceDiagram\n    title T\n    X->>Y: x"
        val bareDiagnostics = rejected(bare, mermaid)
        assertEquals(listOf("Participant 'X' isn't declared.", "Participant 'Y' isn't declared."), bareDiagnostics.map { it.message })
        assertEquals(3, bareDiagnostics.first().fix?.insertLine)

        listOf(Triple(mermaidText, mermaid, mermaidFix), Triple(plantText, plant, plantFix)).forEach { (text, dialect, fix) ->
            val lines = text.lines().toMutableList()
            lines.add(fix.insertLine - 1, fix.text)
            ok(lines.joinToString("\n"), dialect)
        }
    }

    // -- Typed dividers ---------------------------------------------------------------------

    private val dividerCases = listOf(
        Triple(mermaid, "alt" to Seq3FragmentKind.ALT, "else"),
        Triple(mermaid, "par" to Seq3FragmentKind.PAR, "and"),
        Triple(mermaid, "critical" to Seq3FragmentKind.CRITICAL, "option"),
        Triple(plant, "alt" to Seq3FragmentKind.ALT, "else"),
        Triple(plant, "par" to Seq3FragmentKind.PAR, "else"),
        Triple(plant, "critical" to Seq3FragmentKind.CRITICAL, "else"),
    )

    private fun dividedSource(dialect: Seq3Dialect, keyword: String, divider: String) = source(
        dialect, *declarations, "$keyword first", arrow(dialect, "A", "B", "one"),
        "$divider second", arrow(dialect, "A", "B", "two"), divider, arrow(dialect, "A", "B", "three"), "end",
    )

    @Test fun typedDividersBuildOperandsAndRoundTripInBothDialects() {
        dividerCases.forEach { (dialect, opener, divider) ->
            val label = "$dialect ${opener.first}"
            val first = ok(dividedSource(dialect, opener.first, divider), dialect)
            val fragment = first.document.fragments.single()
            assertEquals(opener.second, fragment.kind, label)
            assertEquals("first", fragment.label, label)
            assertEquals(listOf("second", ""), fragment.elseOperands.map { it.guard }, label)
            val rows = first.document.messages.associateBy { it.labelTemplate }
            assertEquals(
                listOf(rows.getValue("two").id, rows.getValue("three").id),
                fragment.elseOperands.map { it.startsAtMessageId },
                label,
            )
            assertEquals(3, fragment.messageIds.size, label)

            val canonical = first.document.toSource(dialect)
            val again = ok(canonical, dialect, first.document)
            assertEquals(first.document, again.document, "$label: canonical source")
            val indented = ok(seq3IndentSource(canonical, dialect), dialect, first.document)
            assertEquals(first.document, indented.document, "$label: indented source")
            assertEquals(canonical.trimEnd('\n'), first.canonicalSource, label)
        }
    }

    @Test fun dividersThatTheEmittersCouldNotWriteAreDiagnosed() {
        // Wrong word for the kind and dialect.
        val mermaidElseInPar = rejected(
            source(mermaid, *declarations, "par x", "A->>B: m", "else y", "A->>B: n", "end"), mermaid,
        ).single()
        assertEquals(6, mermaidElseInPar.line)
        assertEquals("Mermaid divides a par block with 'and', not 'else'.", mermaidElseInPar.message)
        val plantAnd = rejected(
            source(plant, *declarations, "par x", "A -> B: m", "and y", "A -> B: n", "end"), plant,
        ).single()
        assertEquals("PlantUML divides a par block with 'else', not 'and'.", plantAnd.message)
        val inLoop = rejected(
            source(plant, *declarations, "loop x", "A -> B: m", "else y", "A -> B: n", "end"), plant,
        ).single()
        assertEquals(6, inLoop.line)
        assertTrue(inLoop.message.contains("loop"), inLoop.message)
        // No block to divide.
        val outside = rejected(source(plant, *declarations, "else x", "A -> B: m"), plant).single()
        assertEquals(4, outside.line)
        // A branch needs a row above it, a row below it, and no block opening in between.
        val first = rejected(
            source(plant, *declarations, "alt x", "else y", "A -> B: m", "end"), plant,
        ).single()
        assertEquals(5, first.line)
        val last = rejected(
            source(plant, *declarations, "alt x", "A -> B: m", "else y", "end"), plant,
        ).single()
        assertEquals(6, last.line)
        assertTrue(last.message.startsWith("Add a message below this 'else'"), last.message)
        val blockRight = rejected(
            source(plant, *declarations, "alt x", "A -> B: m", "else y", "loop z", "A -> B: n", "end", "end"), plant,
        ).single()
        assertEquals(6, blockRight.line)
        assertTrue(blockRight.message.startsWith("Not supported yet"), blockRight.message)
    }

    @Test fun aMarkedDividerFollowedByATypedRowIsAccepted() {
        // A marked divider used to fail when the next row was a hand-typed arrow.
        Seq3Dialect.entries.forEach { dialect ->
            val before = ok(dividedSource(dialect, "alt", "else"), dialect).document
            val lines = before.toSource(dialect).lines().toMutableList()
            val twoRow = lines.indexOfFirst { it.contains(": two") }
            lines[twoRow] = lines[twoRow].replace(": two", ": typed")
            lines.removeAt(twoRow - 1) // its marker, so the row is a new unmarked one
            val result = ok(lines.joinToString("\n"), dialect, before)
            val fragment = result.document.fragments.single()
            val typed = result.document.messages.single { it.labelTemplate == "typed" }
            assertEquals(typed.id, fragment.elseOperands.first().startsAtMessageId, dialect.name)
        }
    }

    private fun markedAltDocument(withOperand: Boolean): Seq3Document {
        fun occurrence(id: Int) = Seq3Occurrence(id, id.toLong(), "00:00:0$id", 1, 1, 'I', "row $id")
        fun message(id: String, n: Int, label: String) =
            Seq3Message(id, Seq3Match("A", label), "A", "B", label, occurrences = listOf(occurrence(n)))
        return Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "Alpha", setOf("A"), 0), Seq3Lifeline("B", "Beta", setOf("B"), 1)),
            messages = listOf(message("m1", 1, "one"), message("m2", 2, "two"), message("m3", 3, "three")),
            fragments = listOf(
                Seq3Fragment(
                    "alt", Seq3FragmentKind.ALT, "first", listOf("m1", "m2", "m3"),
                    elseOperands = if (withOperand) listOf(Seq3Operand("op", "second", "m2")) else emptyList(),
                ),
            ),
        )
    }

    @Test fun addingAndRemovingADividerOnAMarkedFragmentIsARealEdit() {
        Seq3Dialect.entries.forEach { dialect ->
            // Add: type an `else` above the marker of the third row.
            val plain = markedAltDocument(withOperand = false)
            val lines = plain.toSource(dialect).lines().toMutableList()
            val markerOfThree = lines.indexOfFirst { it.contains(": three") } - 1
            lines.add(markerOfThree, if (dialect == mermaid) "    else typed" else "else typed")
            val added = ok(lines.joinToString("\n"), dialect, plain).document.fragments.single()
            assertEquals(listOf("typed"), added.elseOperands.map { it.guard }, dialect.name)
            assertEquals("m3", added.elseOperands.single().startsAtMessageId, dialect.name)

            // Remove: delete the marked divider and its marker.
            val divided = markedAltDocument(withOperand = true)
            val dividedLines = divided.toSource(dialect).lines().toMutableList()
            val elseAt = dividedLines.indexOfFirst { it.trim().startsWith("else") }
            dividedLines.removeAt(elseAt)
            dividedLines.removeAt(elseAt - 1)
            val removed = ok(dividedLines.joinToString("\n"), dialect, divided).document.fragments.single()
            assertTrue(removed.elseOperands.isEmpty(), dialect.name)

            // Edit the guard of a marked divider: same operand, same anchor.
            val edited = ok(divided.toSource(dialect).replace("else second", "else renamed"), dialect, divided)
            assertEquals(
                Seq3Operand("op", "renamed", "m2"),
                edited.document.fragments.single().elseOperands.single(),
                dialect.name,
            )
        }
    }

    // -- Guided blocks ----------------------------------------------------------------------

    @Test fun guidedBlocksStartValidAndTheirFieldsPointAtTheEditableWords() {
        val kinds = listOf("loop", "alt", "opt", "par", "critical", "break", "group")
        Seq3Dialect.entries.forEach { dialect ->
            kinds.forEach { keyword ->
                listOf(emptyList(), listOf("A"), listOf("A", "B")).forEach { aliases ->
                    val (snippet, fields) = seq3SourceBlockSnippet(keyword, dialect, aliases)
                    val from = aliases.getOrNull(0) ?: "From"
                    val to = aliases.getOrNull(1) ?: "To"
                    val label = "$dialect $keyword $aliases"
                    fun word(field: SourceSnippetField) = snippet.substring(field.start, field.end)
                    assertEquals(listOf("condition", from, to, "message label"), fields.map(::word), label)
                    assertEquals(listOf(false, true, true, false), fields.map { it.endpoint }, label)
                    val result = ok(source(dialect, "participant $from", "participant $to", *snippet.lines().toTypedArray()), dialect)
                    assertEquals(1, result.document.fragments.single().messageIds.size, label)
                    assertEquals("message label", result.document.messages.single().labelTemplate, label)
                }
            }
        }
    }

    // -- create participant, live -----------------------------------------------------------

    @Test fun aCreatedParticipantStaysInItsColumnWhenTheSourceOwnsItsLifelines() {
        val document = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("A", "Alpha", setOf("A"), 0),
                Seq3Lifeline("B", "Beta", setOf("B"), 1),
                Seq3Lifeline("C", "Gamma", setOf("C"), 2),
            ),
            messages = listOf(
                Seq3Message("m1", Seq3Match("A", "spawn"), "A", "B", "spawn", Seq3Kind.CREATE),
                Seq3Message("m2", Seq3Match("A", "use"), "A", "C", "use"),
            ),
        )
        Seq3Dialect.entries.forEach { dialect ->
            val result = ok(document.toSource(dialect), dialect, document, live = true)
            assertEquals(listOf("A", "B", "C"), result.document.lifelines.map { it.id }, dialect.name)
            assertEquals(Seq3Kind.CREATE, result.document.messages.first { it.id == "m1" }.kind, dialect.name)
            assertFalse(result.evidenceLoss, dialect.name)
        }
    }

    // -- Live sessions ----------------------------------------------------------------------

    @Test fun operandIdsStayStableAcrossLiveApplies() {
        Seq3Dialect.entries.forEach { dialect ->
            val root = createTempDirectory("indagium-seq3-grammar").toFile()
            val state = AppState(
                File(root, "state.cache"),
                notesDir = File(root, "notes"),
                diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
            ).also { app ->
                val tab = mkTab("log", "sample.log", emptyList())
                app.tabs = listOf(tab)
                app.activateTab(tab.id)
            }
            val id = requireNotNull(state.seq3Sessions.beginEmpty("log"))
            assertTrue(state.seq3Sessions.prepareSourceDraft(id))
            assertTrue(state.seq3Sessions.switchSourceDraftDialect(id, dialect))
            fun apply(guard: String, label: String): Seq3Fragment {
                val text = source(
                    dialect, *declarations, "alt first", arrow(dialect, "A", "B", "one"),
                    "else $guard", arrow(dialect, "A", "B", label), "end",
                )
                state.seq3Sessions.updateSourceDraftText(id, text)
                assertIs<Seq3SourceImportResult.Success>(state.seq3Sessions.applyLiveSourceDraft(id, text, dialect), text)
                return state.seq3Sessions.sessions.single { it.id == id }.document.fragments.single()
            }
            val first = apply("slow", "two")
            val second = apply("slower", "two")
            val third = apply("slower", "twenty")
            assertEquals(first.id, second.id, dialect.name)
            assertEquals(first.elseOperands.single().id, second.elseOperands.single().id, "$dialect: guard edit")
            assertEquals(first.elseOperands.single().id, third.elseOperands.single().id, "$dialect: row edit")
            assertEquals("slower", third.elseOperands.single().guard, dialect.name)
            state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
            root.deleteRecursively()
        }
    }
}
