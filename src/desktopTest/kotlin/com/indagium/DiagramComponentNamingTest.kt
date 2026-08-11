package com.indagium

import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.participantDisplayNames
import com.indagium.ui.commonPackagePrefix
import com.indagium.ui.proposeComponentName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers proposeComponentName's naming heuristic (ui/TagSuggestions.kt) — a shared package
 * prefix wins over a single-pid process name, and either falls back to null rather than guessing.
 * The result is never applied automatically; these tests only pin what value would be offered.
 */
class DiagramComponentNamingTest {
    @Test
    fun sharedPackagePrefixProposesItsLastSegment() {
        val tags = setOf("com.example.bt.Adapter", "com.example.bt.Scanner")

        assertEquals("com.example.bt", commonPackagePrefix(tags))
        assertEquals("bt", proposeComponentName(tags, pidsByTag = emptyMap(), processNames = emptyMap()))
    }

    @Test
    fun noSharedPrefixButASinglePidProposesTheProcessNamesLastSegment() {
        val tags = setOf("Bluetooth", "WifiManager")
        val pidsByTag = mapOf("Bluetooth" to setOf(1234), "WifiManager" to setOf(1234))
        val processNames = mapOf(1234 to "com.example.app")

        assertNull(commonPackagePrefix(tags), "no dotted prefix shared by either tag")
        assertEquals("app", proposeComponentName(tags, pidsByTag, processNames))
    }

    @Test
    fun noSharedPrefixAndTwoPidsProposesNothing() {
        val tags = setOf("Bluetooth", "WifiManager")
        val pidsByTag = mapOf("Bluetooth" to setOf(1234), "WifiManager" to setOf(5678))
        val processNames = mapOf(1234 to "com.example.app", 5678 to "com.example.other")

        assertNull(proposeComponentName(tags, pidsByTag, processNames))
    }

    @Test
    fun aSingleTagProposesNothing() {
        val tags = setOf("com.example.bt.Adapter")

        assertNull(
            proposeComponentName(
                tags,
                pidsByTag = mapOf("com.example.bt.Adapter" to setOf(1234)),
                processNames = mapOf(1234 to "com.example.app"),
            ),
            "a single tag's alias is the user's own words, nothing to infer",
        )
    }

    @Test
    fun aTagAndItsOwnDottedChildShareNoUsablePrefix() {
        // "A" is itself one of the tags, not a package above them — proposing "A" back would be
        // indistinguishable from the tag it's meant to name.
        assertNull(commonPackagePrefix(setOf("A", "A.B")))
        assertNull(proposeComponentName(setOf("A", "A.B"), pidsByTag = emptyMap(), processNames = emptyMap()))
    }

    @Test
    fun collidingSimpleNamesKeepTheSmallestDistinctPackageSuffix() {
        val names = participantDisplayNames(
            listOf(
                DiagramParticipant("a", "com.example.client.Service", ParticipantKind.TAG),
                DiagramParticipant("b", "com.example.server.Service", ParticipantKind.TAG),
            ),
        )
        assertEquals(listOf("client.Service", "server.Service"), names)
    }
}
