package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Declared participant and actor names are completed wherever a name belongs. */
class Seq3SourceNameSuggestionsTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private val pumlHead = "@startuml\nparticipant \"Alice\" as A\nactor \"Bob Smith\" as Bob\nparticipant \"Alpha service\" as Alpha\n"
    private val mmdHead = "sequenceDiagram\nparticipant A as Alice\nactor Bob as Bob Smith\nparticipant Alpha as Alpha service\n"

    private fun head(dialect: Seq3Dialect) = if (dialect == puml) pumlHead else mmdHead

    private fun trigger(source: String, dialect: Seq3Dialect) =
        seq3SourceSuggestionTrigger(source, TextRange(source.length), dialect)

    private fun shown(source: String, dialect: Seq3Dialect): List<String> {
        val trigger = trigger(source, dialect) ?: return emptyList()
        val participants = sourceParticipants(source, dialect)
        return seq3SourceActiveSuggestions(source, trigger, dialect, participants.map { it.alias }, participants).map { it.keyword }
    }

    @Test
    fun theStartOfALineOffersMatchingNamesBeforeKeywords() {
        for (dialect in Seq3Dialect.entries) {
            val source = head(dialect) + "Al"
            val trigger = requireNotNull(trigger(source, dialect)) { dialect.name }
            assertTrue(trigger.names && trigger.keywords, dialect.name)
            // Alpha matches by alias, A by its display name Alice; the keyword `alt` follows the names.
            assertEquals(listOf("Alpha", "A", "alt"), shown(source, dialect), dialect.name)
        }
    }

    @Test
    fun aNameMatchesByAliasOrByDisplayNameAndAcceptingInsertsTheAlias() {
        for (dialect in Seq3Dialect.entries) {
            val source = head(dialect) + "Ali"
            assertEquals(listOf("A"), shown(source, dialect), "display name Alice matches `Ali`; ${dialect.name}")
            val trigger = requireNotNull(trigger(source, dialect))
            val participants = sourceParticipants(source, dialect)
            val suggestion = seq3SourceActiveSuggestions(source, trigger, dialect, participants.map { it.alias }, participants).single()
            assertTrue(suggestion.name)
            assertEquals("Alice", suggestion.syntax, "the display name is shown beside the alias")
            val inserted = insertSeq3SourceSuggestion(source, trigger, suggestion, dialect)
            assertEquals(head(dialect) + "A", inserted.text, dialect.name)
            assertEquals(inserted.text.length, inserted.selection.start)
        }
    }

    @Test
    fun anAliasThatIsAlsoTheNameShowsNoSecondLabel() {
        val source = pumlHead + "Bo"
        val trigger = requireNotNull(trigger(source, puml))
        val participants = sourceParticipants(source, puml)
        val bob = seq3SourceActiveSuggestions(source, trigger, puml, participants.map { it.alias }, participants).first()
        assertEquals("Bob", bob.keyword)
        assertEquals("Bob Smith", bob.syntax, "this actor's display name differs from its alias")
        assertEquals(Seq3SourceGlyph.ACTOR, bob.glyph)
        val declared = "@startuml\nparticipant Plain\nPl"
        val plain = seq3SourceActiveSuggestions(
            declared, requireNotNull(trigger(declared, puml)), puml, emptyList(), sourceParticipants(declared, puml),
        ).first()
        assertEquals("", plain.syntax)
        assertEquals(Seq3SourceGlyph.PARTICIPANT, plain.glyph)
    }

    @Test
    fun theTargetAfterAnArrowOffersNamesFromTheFirstLetter() {
        for ((arrow, dialect) in listOf("A -> " to puml, "A --> " to puml, "A ->> " to puml, "A->" to puml, "A->>" to mmd, "A-->>" to mmd, "A-)" to mmd, "A->>+" to mmd, "A-->>-" to mmd)) {
            val source = head(dialect) + arrow + "B"
            val trigger = requireNotNull(trigger(source, dialect)) { "$arrow ${dialect.name}" }
            assertTrue(trigger.names && !trigger.keywords, "$arrow: only names follow an arrow")
            assertEquals(listOf("Bob"), shown(source, dialect), "$arrow ${dialect.name}")
        }
        // No letter yet, no list.
        assertNull(trigger(pumlHead + "A -> ", puml))
        // The label after the colon is not a name position.
        assertNull(trigger(pumlHead + "A -> Bob: B", puml))
    }

    @Test
    fun noteScopesAndTheirCommaListOfferNames() {
        for ((prefix, dialect) in listOf(
            "note over " to puml, "note left of " to puml, "note right of " to puml, "note over A, " to puml, "note over A,  " to puml,
            "Note over " to mmd, "Note left of " to mmd, "Note right of " to mmd, "Note over A," to mmd,
        )) {
            val source = head(dialect) + prefix + "Bo"
            assertEquals(listOf("Bob"), shown(source, dialect), "$prefix ${dialect.name}")
            assertFalse(requireNotNull(trigger(source, dialect)).keywords, prefix)
        }
        // After the colon the note text is free text.
        assertNull(trigger(pumlHead + "note over A: Bo", puml))
    }

    @Test
    fun activationAndLifecycleKeywordsOfferNames() {
        for (keyword in listOf("activate", "deactivate", "destroy", "create")) {
            val source = pumlHead + "$keyword Bo"
            assertEquals(listOf("Bob"), shown(source, puml), "PlantUML $keyword")
        }
        for (keyword in listOf("activate", "deactivate", "destroy")) {
            assertEquals(listOf("Bob"), shown(mmdHead + "$keyword Bo", mmd), "Mermaid $keyword")
        }
        // Mermaid's `create` is followed by participant/actor and a NEW name, so no declared name is offered.
        assertNull(trigger(mmdHead + "create Bo", mmd))
        assertNull(trigger(mmdHead + "create participant Bo", mmd))
        // Indentation in front is fine.
        assertEquals(listOf("Bob"), shown(pumlHead + "    activate Bo", puml))
    }

    @Test
    fun namesDoNotAppearWhereNoNameBelongs() {
        assertNull(trigger(pumlHead + "title Al", puml))
        assertNull(trigger(pumlHead + "participant Al", puml))
        assertNull(trigger(pumlHead + "loop Al", puml))
        assertNull(trigger(pumlHead + "' A -> Al", puml), "a comment line is not an arrow line")
        assertNull(trigger(mmdHead + "%% A->>Al", mmd))
        assertNull(trigger(pumlHead + "A -> Bob: hello Al", puml))
        // `/` keeps meaning keywords only.
        val slash = requireNotNull(trigger(pumlHead + "/al", puml))
        assertFalse(slash.names)
    }

    @Test
    fun aBareLetterAtALineStartStillShowsNothingButTwoLettersDo() {
        assertNull(trigger(pumlHead + "A", puml), "the 2-letter minimum of a bare trigger is kept")
        assertNotNull(trigger(pumlHead + "Al", puml))
        assertNull(trigger(mmdHead + "B", mmd))
    }

    @Test
    fun namesComeFirstThenKeywordsAtALineStart() {
        val source = "@startuml\nparticipant \"Pat\" as Pat\nactor \"Parker\" as Parker\npa"
        val participants = sourceParticipants(source, puml)
        val trigger = requireNotNull(trigger(source, puml))
        val keywords = seq3SourceActiveSuggestions(source, trigger, puml, participants.map { it.alias }, participants)
        assertEquals(listOf("Pat", "Parker", "participant", "par"), keywords.map { it.keyword })
        assertEquals(listOf(true, true, false, false), keywords.map { it.name })
    }

    @Test
    fun anExactNameWithNothingMoreToCompleteHidesTheList() {
        for (dialect in Seq3Dialect.entries) {
            // `Bob` is typed in full and nothing else starts with it.
            assertEquals(emptyList(), shown(head(dialect) + "A -> Bob".let { if (dialect == mmd) "A->>Bob" else it }, dialect), dialect.name)
            assertEquals(emptyList(), shown(head(dialect) + "activate Bob", dialect), dialect.name)
            assertEquals(emptyList(), shown(head(dialect) + "Bob", dialect), "a line start with only an exact name; ${dialect.name}")
        }
        // A longer alias that also starts with the typed one keeps the list, without the exact one.
        val longer = "@startuml\nparticipant A\nparticipant AB\nactivate A"
        assertEquals(listOf("AB"), shown(longer, puml))
        // The exact match is case sensitive: `bob` still offers `Bob`.
        assertEquals(listOf("Bob"), shown(pumlHead + "activate bob", puml))
    }

    @Test
    fun anExactKeywordStillHidesItsListAtALineStartWhenNoNameMatches() {
        assertEquals(emptyList(), shown(pumlHead + "alt ok\n    A -> Bob: x\nend", puml))
        // A name that is also a keyword prefix: names first, then the keyword.
        val source = "@startuml\nparticipant Endpoint\nen"
        assertEquals(listOf("Endpoint", "end"), shown(source, puml))
    }

    @Test
    fun acceptingAMidLineNameReplacesJustTheTypedWord() {
        val source = pumlHead + "A -> Bo"
        val trigger = requireNotNull(trigger(source, puml))
        val participants = sourceParticipants(source, puml)
        val suggestion = seq3SourceActiveSuggestions(source, trigger, puml, participants.map { it.alias }, participants).first()
        val inserted = insertSeq3SourceSuggestion(source, trigger, suggestion, puml)
        assertEquals(pumlHead + "A -> Bob", inserted.text)
        // A name that spells a keyword (`title`, `actor`) is still just a name.
        val tricky = "@startuml\nparticipant \"The title\" as title\nactivate ti"
        val t = requireNotNull(trigger(tricky, puml))
        val named = seq3SourceActiveSuggestions(tricky, t, puml, emptyList(), sourceParticipants(tricky, puml)).single()
        assertEquals("@startuml\nparticipant \"The title\" as title\nactivate title", insertSeq3SourceSuggestion(tricky, t, named, puml).text)
    }

    @Test
    fun aNameInTheMiddleOfAWordIsReplacedWhole() {
        val source = pumlHead + "activate Bxx rest"
        val caret = source.indexOf("Bxx") + 1
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(caret), puml))
        assertEquals("Bxx", trigger.query)
        assertEquals(source.indexOf("Bxx"), trigger.start)
        assertEquals(source.indexOf("Bxx") + 3, trigger.end)
    }

    @Test
    fun theKeywordGlyphsOfTheToolbarAreTheSuggestionRowGlyphs() {
        for (dialect in Seq3Dialect.entries) {
            val all = seq3SourceSuggestions(dialect, "", emptyList(), "alt")
            for (keyword in listOf("participant", "actor", "note", "call", "return", "async", "loop", "alt", "opt", "par", "critical", "break", "group", "activate", "deactivate")) {
                assertEquals(all.first { it.keyword == keyword }.glyph, seq3SourceKeywordGlyph(keyword), "$keyword ${dialect.name}")
            }
        }
    }
}
