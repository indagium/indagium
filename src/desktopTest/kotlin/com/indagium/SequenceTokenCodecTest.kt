package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.SequenceDef
import com.indagium.ui.b64
import com.indagium.ui.sequenceFromToken
import com.indagium.ui.sequenceToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers `SequenceDef.sequenceToken()` / `String.sequenceFromToken()` (ui/AutosaveCodec.kt) after
 * Wave 2.1 appended `scopeTid` (field index 10, the 11th field) — thread-scoped ("async")
 * sequences. Appended after the existing 10 fields (never inserted/reordered), so a legacy 10-field
 * token written before this field existed must still restore every prior field correctly and
 * default `scopeTid` to null (unscoped), matching [SequenceDef]'s own constructor default.
 */
class SequenceTokenCodecTest {
    @Test
    fun scopeTidRoundTripsThroughAnElevenFieldToken() {
        val original = SequenceDef(
            id = "seq1",
            matchText = "flow started",
            isRegex = false,
            priority = 1,
            color = Color.Red,
            tag = "Auth",
            endMatchText = "flow finished",
            endTag = "Lifecycle",
            scopeTid = 4213,
        )

        val restored = original.sequenceToken().sequenceFromToken()

        assertEquals(original, restored)
        assertEquals(4213, restored?.scopeTid)
    }

    @Test
    fun nullScopeTidRoundTripsAsNull() {
        val original = SequenceDef(
            id = "seq1", matchText = "flow started", priority = 1, color = Color.Blue, tag = "Auth",
        )

        val restored = original.sequenceToken().sequenceFromToken()

        assertNull(restored?.scopeTid)
    }

    @Test
    fun aLegacyTenFieldTokenDecodesWithScopeTidNull() {
        // A pre-Wave-2.1 token: exactly the 10 fields sequenceToken() used to write, with no
        // trailing scopeTid field at all (not even an empty one).
        val legacyToken = listOf(
            "seq1",
            "flow started",
            "false",
            "1",
            Color.Red.value.toString(),
            "true",
            "Auth",
            "flow finished",
            "false",
            "Lifecycle",
        ).joinToString("|") { it.b64() }

        val restored = legacyToken.sequenceFromToken()

        assertEquals("seq1", restored?.id)
        assertEquals("flow started", restored?.matchText)
        assertEquals("Auth", restored?.tag)
        assertEquals("flow finished", restored?.endMatchText)
        assertEquals("Lifecycle", restored?.endTag)
        assertNull(restored?.scopeTid, "a legacy token with no field 10 must decode with scopeTid == null")
    }
}
