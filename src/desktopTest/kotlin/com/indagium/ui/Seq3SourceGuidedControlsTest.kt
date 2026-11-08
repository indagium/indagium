package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3SourceGuidedControlsTest {
    @Test
    fun newDiagramSeedsHaveOnlyTheSelectedDialectWrapperAndOptionalTitle() {
        assertEquals("@startuml\n@enduml", seq3SourceStarter(Seq3Dialect.PLANTUML))
        assertEquals("sequenceDiagram", seq3SourceStarter(Seq3Dialect.MERMAID))
        assertEquals("@startuml\ntitle New diagram\n@enduml", seq3SourceStarter(Seq3Dialect.PLANTUML, "New diagram"))
        assertEquals("sequenceDiagram\ntitle New diagram", seq3SourceStarter(Seq3Dialect.MERMAID, "New diagram"))
        assertFalse(seq3SourceStarter(Seq3Dialect.PLANTUML).contains("participant"))
        assertFalse(seq3SourceStarter(Seq3Dialect.MERMAID).contains("actor"))
    }

    @Test
    fun participantInsertionFollowsTitleAndExistingParticipantsButPrecedesBody() {
        val plantUml = "@startuml\ntitle Existing\n' indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nparticipant \"Alpha\" as A\n" +
            "' indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nA -> A: message\n@enduml"
        val mermaid = "sequenceDiagram\ntitle Existing\nparticipant A as Alpha\n%% indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nA->>A: message"

        val plantParticipantOffset = seq3SourceHeaderInsertionOffset(plantUml, Seq3Dialect.PLANTUML)
        val plantTitleOffset = seq3SourceHeaderInsertionOffset(plantUml, Seq3Dialect.PLANTUML, titleOnly = true)
        val mermaidParticipantOffset = seq3SourceHeaderInsertionOffset(mermaid, Seq3Dialect.MERMAID)

        assertEquals(plantUml.indexOf("' indagium-seq3:v1", plantUml.indexOf("participant")), plantParticipantOffset)
        assertEquals(plantUml.indexOf("' indagium-seq3:v1"), plantTitleOffset)
        assertEquals(mermaid.indexOf("%% indagium-seq3:v1"), mermaidParticipantOffset)
    }

    @Test
    fun hiddenMetadataProjectionRemovesOnlyProtocolRowsAndKeepsSourceLineNumbers() {
        val source = "@startuml\n' indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nparticipant \"Alpha\" as A\n' indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nA -> A: message\n@enduml"
        val ranges = seq3SourceMarkerDeletionRanges(source, Seq3Dialect.PLANTUML)
        val projected = ranges.asReversed().fold(source) { text, range -> text.removeRange(range.start, range.endExclusive) }

        assertEquals("@startuml\nparticipant \"Alpha\" as A\nA -> A: message\n@enduml", projected)
        assertEquals(listOf(1, 3, 5, 6), seq3SourceVisibleLineNumbers(source, Seq3Dialect.PLANTUML, showMarkers = false))
        assertEquals((1..6).toList(), seq3SourceVisibleLineNumbers(source, Seq3Dialect.PLANTUML, showMarkers = true))

        val mermaid = "sequenceDiagram\n%% indagium-seq3:v1 eyJ0IjoiY2FsbCJ9\nA->>A: message"
        val mermaidRange = seq3SourceMarkerDeletionRanges(mermaid, Seq3Dialect.MERMAID).single()
        assertEquals("sequenceDiagram\nA->>A: message", mermaid.removeRange(mermaidRange.start, mermaidRange.endExclusive))
        assertTrue(isSeq3SourceMarkerLine("%% indagium-seq3:v1 eyJ0IjoiY2FsbCJ9", Seq3Dialect.MERMAID))
        assertFalse(isSeq3SourceMarkerLine("%% indagium-seq3:v1", Seq3Dialect.MERMAID))
    }

    @Test
    fun noteScopeUsesLabeledArrowImmediatelyBeforeTheCaret() {
        val plantUml = "@startuml\nparticipant \"Alpha\" as A\nparticipant \"Beta\" as B\nA -> B: labeled call\n@enduml"
        val mermaid = "sequenceDiagram\nparticipant A as Alpha\nparticipant B as Beta\nA->>B: labeled call"
        assertEquals("A,B", seq3SourceNoteScope(plantUml, plantUml.indexOf("@enduml"), Seq3Dialect.PLANTUML, listOf("A", "B")))
        assertEquals("A,B", seq3SourceNoteScope(mermaid, mermaid.length, Seq3Dialect.MERMAID, listOf("A", "B")))
        assertNull(seq3SourceNoteScope(mermaid, mermaid.indexOf("A->>B"), Seq3Dialect.MERMAID, listOf("A", "B")))
    }

    @Test
    fun identifiesExistingMessageEndpointsInBothDialects() {
        for ((source, dialect) in listOf("@startuml\nClient -> Server: request" to Seq3Dialect.PLANTUML,
            "sequenceDiagram\nClient->>Server: request" to Seq3Dialect.MERMAID)) {
            val client = source.indexOf("Client")
            val server = source.indexOf("Server")
            assertEquals(SourceSnippetField(client, client + 6, true), sourceEndpointAt(source, TextRange(client + 2), dialect))
            assertEquals(SourceSnippetField(server, server + 6, true), sourceEndpointAt(source, TextRange(server, server + 6), dialect))
            assertNull(sourceEndpointAt(source, TextRange(source.indexOf("request")), dialect))
        }
    }

    @Test
    fun onlyDialectWrapperAndProtocolMarkersCountAsEmptySource() {
        assertFalse(seq3SourceHasUserContent("@startuml\n@enduml", Seq3Dialect.PLANTUML))
        assertFalse(seq3SourceHasUserContent("sequenceDiagram\n%% indagium-seq3:v1 abc123", Seq3Dialect.MERMAID))
        assertTrue(seq3SourceHasUserContent("sequenceDiagram\n%% keep this comment", Seq3Dialect.MERMAID))
        assertTrue(seq3SourceHasUserContent("@startuml\nparticipant \"Alpha\" as A\n@enduml", Seq3Dialect.PLANTUML))
    }
}
