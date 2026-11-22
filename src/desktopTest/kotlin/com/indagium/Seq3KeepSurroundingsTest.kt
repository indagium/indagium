package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.keepSeq3NoteSurroundings
import kotlin.test.Test
import kotlin.test.assertEquals

class Seq3KeepSurroundingsTest {
    private fun note(title: String) = encodeSeq3Note(Seq3Document(title = title), Seq3Dialect.PLANTUML)

    @Test
    fun theOldWhitespaceAndTheTextAfterTheOldFenceSurviveAReEncode() {
        val old = "\n  " + note("old") + "Prose one.\n\nProse two.\n"
        assertEquals("\n  " + note("new") + "Prose one.\n\nProse two.\n", keepSeq3NoteSurroundings(old, note("new")))
    }

    @Test
    fun anOldTextThatIsNotADiagramNoteIsReplacedOutright() {
        assertEquals(note("new"), keepSeq3NoteSurroundings("plain text", note("new")))
        assertEquals(note("new"), keepSeq3NoteSurroundings(null, note("new")))
    }

    @Test
    fun aNoteWithNothingAroundItIsJustTheNewEncoding() {
        assertEquals(note("new"), keepSeq3NoteSurroundings(note("old"), note("new")))
    }
}
