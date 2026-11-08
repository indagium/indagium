package com.indagium.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typing inside an endpoint (an arrow's sender or receiver, a snippet's From / To) completes the whole name. */
class Seq3SourceEndpointTriggerTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private val pumlHead = "@startuml\nparticipant \"Alice\" as A\nactor \"Bob Smith\" as Bob\nparticipant Alias\nparticipant Alias_2\n"
    private val mmdHead = "sequenceDiagram\nparticipant A as Alice\nactor Bob as Bob Smith\nparticipant Alias\nparticipant Alias_2\n"

    private fun head(dialect: Seq3Dialect) = if (dialect == puml) pumlHead else mmdHead

    /** The trigger with the caret placed where `|` stands in [marked]. */
    private fun triggerAt(marked: String, dialect: Seq3Dialect, fields: List<SourceSnippetField> = emptyList()): Pair<String, Seq3SourceSuggestionTrigger?> {
        val caret = marked.indexOf('|')
        val source = marked.replace("|", "")
        return source to seq3SourceEndpointTrigger(source, TextRange(caret), dialect, fields)
    }

    @Test
    fun theReceiverOfACompleteMessageLineIsAnEndpointInBothDialects() {
        val cases = listOf(
            "A -> Bo|: x" to puml, "A --> Bo|: x" to puml, "A ->> Bo|: x" to puml,
            "A->>Bo|: x" to mmd, "A-->>Bo|: x" to mmd, "A-)Bo|: x" to mmd,
            "A->>+Bo|: x" to mmd, "A-->>-Bo|: x" to mmd, "A->>+Bo|: x" to mmd,
        )
        for ((line, dialect) in cases) {
            val (source, trigger) = triggerAt(head(dialect) + line, dialect)
            val t = requireNotNull(trigger) { "$line ${dialect.name}" }
            assertTrue(t.names && !t.keywords, "$line: only names belong in an endpoint")
            assertEquals("Bo", t.query, "$line ${dialect.name}")
            assertEquals("Bo", source.substring(t.start, t.end), "the range is the name without the activation mark; $line")
        }
    }

    @Test
    fun theSenderOfACompleteMessageLineIsAnEndpointWithoutTheTwoLetterMinimum() {
        for ((line, dialect) in listOf("B| -> A: x" to puml, "B|->>A: x" to mmd, "B|-->>A: x" to mmd)) {
            val (source, trigger) = triggerAt(head(dialect) + line, dialect)
            val t = requireNotNull(trigger) { "$line ${dialect.name}" }
            assertFalse(t.keywords, line)
            assertEquals("B", source.substring(t.start, t.end))
        }
    }

    @Test
    fun theCaretOutsideTheNameIsNoEndpoint() {
        for ((line, dialect) in listOf(
            "A -> Bo: ex|t" to puml, "A->>|+Bo: x" to mmd, "A ->| Bo: x" to puml, "title Al|" to puml,
        )) {
            assertNull(triggerAt(head(dialect) + line, dialect).second, "$line ${dialect.name}")
        }
        // A selection is not a typed word.
        val source = pumlHead + "A -> Bo: x"
        assertNull(seq3SourceEndpointTrigger(source, TextRange(source.indexOf("Bo"), source.indexOf("Bo") + 2), puml))
    }

    @Test
    fun theWholeNameTheCaretIsInIsTheRangeToReplace() {
        for (dialect in Seq3Dialect.entries) {
            val line = if (dialect == puml) "Alias -> Alias_|2x: x" else "Alias->>+Alias_|2x: x"
            val (source, trigger) = triggerAt(head(dialect) + "participant Alias_2x\n" + line, dialect)
            val t = requireNotNull(trigger)
            assertEquals("Alias_2x", source.substring(t.start, t.end), dialect.name)
            assertEquals("Alias_2x", t.query)
            val participants = sourceParticipants(source, dialect)
            val menu = seq3SourceEndpointMenuSuggestions(participants).first { it.keyword == "Alias_2x" }
            val inserted = insertSeq3SourceSuggestion(source, t, menu, dialect)
            assertEquals(source, inserted.text, "choosing the name already there changes nothing, not Alias_2x2x; ${dialect.name}")
            val other = insertSeq3SourceSuggestion(source, t, seq3SourceEndpointMenuSuggestions(participants).first { it.keyword == "Alias" }, dialect)
            assertEquals(source.replace("Alias_2x:", "Alias:"), other.text, dialect.name)
            assertEquals(other.text.indexOf("Alias:") + 5, other.selection.start, "the caret ends after the new name")
        }
    }

    @Test
    fun typingAPrefixOffersTheNamesThatStartWithTheWholeName() {
        for (dialect in Seq3Dialect.entries) {
            val (source, trigger) = triggerAt(head(dialect) + (if (dialect == puml) "A -> Ali|: x" else "A->>Ali|: x"), dialect)
            val t = requireNotNull(trigger)
            val participants = sourceParticipants(source, dialect)
            val shown = seq3SourceActiveSuggestions(source, t, dialect, participants.map { it.alias }, participants)
            // Alias and Alias_2 by alias, A by its display name Alice.
            assertEquals(listOf("Alias", "Alias_2", "A"), shown.map { it.keyword }, dialect.name)
            assertTrue(shown.all { it.name })
        }
    }

    @Test
    fun theClickMenuCoversTheNameAndEvenASelectionInsideIt() {
        for (dialect in Seq3Dialect.entries) {
            val line = if (dialect == puml) "Alias -> Alias_2: x" else "Alias->>+Alias_2: x"
            val source = head(dialect) + line
            val name = source.indexOf("Alias_2:")
            for (selection in listOf(TextRange(name + 6), TextRange(name + 1, name + 4), TextRange(name), TextRange(name + 7))) {
                val trigger = requireNotNull(seq3SourceEndpointMenuTrigger(source, selection, dialect)) { "$selection ${dialect.name}" }
                assertEquals("Alias_2", source.substring(trigger.start, trigger.end))
                val participants = sourceParticipants(source, dialect)
                assertEquals(participants.map { it.alias }, seq3SourceEndpointMenuSuggestions(participants).map { it.keyword }, "every participant")
                assertEquals(participants.indexOfFirst { it.alias == "Alias_2" }, seq3SourceEndpointMenuActiveIndex(trigger, participants), "the current name is the active row")
            }
            assertNull(seq3SourceEndpointMenuTrigger(source, TextRange(source.length), dialect), "the label is no endpoint")
        }
        // A name that is not declared leaves the first row active.
        val unknown = pumlHead + "A -> Ghost: x"
        val trigger = requireNotNull(seq3SourceEndpointMenuTrigger(unknown, TextRange(unknown.indexOf("Ghost")), puml))
        assertEquals(0, seq3SourceEndpointMenuActiveIndex(trigger, sourceParticipants(unknown, puml)))
    }

    @Test
    fun aSnippetEndpointFieldIsCompletedOverTheWholeField() {
        val source = pumlHead + "Cl -> To: message label"
        val start = source.indexOf("Cl")
        val fields = listOf(SourceSnippetField(start, start + 2, true), SourceSnippetField(start + 6, start + 8, true))
        val trigger = requireNotNull(seq3SourceEndpointTrigger(source, TextRange(start + 1), puml, fields))
        assertEquals(start, trigger.start)
        assertEquals(start + 2, trigger.end)
        assertEquals("Cl", trigger.query)
        assertFalse(trigger.keywords)
        // The selected field (Tab into it) is not a typed word: no list until something is typed.
        assertNull(seq3SourceEndpointTrigger(source, TextRange(start, start + 2), puml, fields))
        // A label field is no endpoint.
        val label = source.indexOf("message")
        assertNull(seq3SourceEndpointTrigger(source, TextRange(label + 2), puml, listOf(SourceSnippetField(label, source.length, false))))
        // The menu opens over a selected endpoint field too.
        assertNotNull(seq3SourceEndpointMenuTrigger(source, TextRange(start, start + 2), puml, fields))
    }

    @Test
    fun theEditorTriggerFallsBackToTheWordAtTheCaret() {
        // An unfinished message line (no colon yet) and a note scope are not endpoints of a complete line.
        for ((marked, dialect) in listOf(
            "A -> Bo|" to puml, "A->Bo|: x" to puml, "A->>+Bo|" to mmd, "note over A, Bo|" to puml, "Note over A, Bo|" to mmd,
        )) {
            val caret = (head(dialect) + marked).indexOf('|')
            val source = (head(dialect) + marked).replace("|", "")
            val t = requireNotNull(seq3SourceEditorTrigger(source, TextRange(caret), dialect)) { "$marked ${dialect.name}" }
            assertTrue(t.names && !t.keywords, marked)
            assertEquals("Bo", t.query)
        }
        // A bare word at a line start keeps its two-letter minimum and its keywords.
        val one = pumlHead + "A"
        assertNull(seq3SourceEditorTrigger(one, TextRange(one.length), puml))
        val two = pumlHead + "Al"
        assertTrue(requireNotNull(seq3SourceEditorTrigger(two, TextRange(two.length), puml)).keywords)
    }

    @Test
    fun anAcceptedEndpointStaysDismissedAtItsOwnLength() {
        val accepted = TextFieldValue(pumlHead + "Alias -> Alias: x", TextRange(pumlHead.length + "Alias -> Alias".length))
        val closed = requireNotNull(seq3SourceDismissalAfterAccept(accepted, puml))
        assertEquals("Alias", closed.query)
        val same = seq3SourceEditorTrigger(accepted.text, accepted.selection, puml)
        assertEquals(closed, seq3SourceDismissalAfterEdit(closed, same))
        val typed = pumlHead + "Alias -> Alias_: x"
        assertNull(seq3SourceDismissalAfterEdit(closed, seq3SourceEditorTrigger(typed, TextRange(typed.indexOf(":")), puml)))
    }
}
