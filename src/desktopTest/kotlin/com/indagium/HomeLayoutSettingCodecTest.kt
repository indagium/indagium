package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.DEFAULT_HOME_LAYOUT
import com.indagium.model.DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP
import com.indagium.model.HomeDiagramSegment
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis
import com.indagium.model.MAX_HOME_OPEN_ACTIONS_HEIGHT_DP
import com.indagium.model.MIN_HOME_OPEN_ACTIONS_HEIGHT_DP
import com.indagium.ui.HOME_MAX_WEIGHT
import com.indagium.ui.HOME_MIN_WEIGHT
import com.indagium.ui.HomeLayoutPreset
import com.indagium.ui.homeLayoutJson
import com.indagium.ui.homeLeafSections
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Shaped after HomeRecentGridColumnsSettingCodecTest: the three home* fields are JSON-only, appended last per
// CLAUDE.md's rule. settingsFromToken must never gain them, so a legacy positional token defaults them, same as `{}`.
class HomeLayoutSettingCodecTest {
    private fun decode(json: String): AppSettings = settingsFromJson(json)!!

    private fun allWeights(node: HomeLayoutNode): List<Float> = listOf(node.weight) +
        ((node as? HomeLayoutSplit)?.children?.flatMap { allWeights(it) } ?: emptyList())

    private fun assertValid(settings: AppSettings) {
        val visible = homeLeafSections(settings.homeLayout)
        assertEquals(visible.toSet().size, visible.size, "a section appears twice: ${settings.homeLayout}")
        assertEquals(HomeSectionId.entries.toSet(), (visible + settings.homeHiddenSections).toSet())
        assertEquals(HomeSectionId.entries.size, (visible + settings.homeHiddenSections).size)
        assertTrue(visible.isNotEmpty())
        assertTrue(allWeights(settings.homeLayout).all { it.isFinite() && it > 0f })
    }

    @Test
    fun homeLayoutAndActionAreaRoundTripThroughKeyedSettingsJson() {
        val custom = AppSettings(
            homeLayout = HomeLayoutSplit(
                HomeSplitAxis.ROW,
                listOf(
                    HomeLayoutLeaf(HomeSectionId.TESTS, 2.5f),
                    HomeLayoutSplit(
                        HomeSplitAxis.COLUMN,
                        listOf(
                            HomeLayoutLeaf(HomeSectionId.OPEN, 1.25f),
                            HomeLayoutLeaf(HomeSectionId.CAPTURE, 0.75f),
                            HomeLayoutLeaf(HomeSectionId.DIAGRAMS, 0.5f),
                        ),
                        1.5f,
                    ),
                ),
            ),
            homeLayoutTipDismissed = true,
            homeOpenActionsHeightDp = 248,
        )
        assertEquals(custom.homeLayout, decode(custom.settingsJson()).homeLayout)
        assertTrue(decode(custom.settingsJson()).homeLayoutTipDismissed)
        assertEquals(248, decode(custom.settingsJson()).homeOpenActionsHeightDp)

        val hiddenCase = AppSettings(
            homeLayout = HomeLayoutLeaf(HomeSectionId.OPEN),
            homeHiddenSections = listOf(HomeSectionId.TESTS, HomeSectionId.CAPTURE, HomeSectionId.DIAGRAMS),
        )
        val decoded = decode(hiddenCase.settingsJson())
        assertEquals(hiddenCase.homeLayout, decoded.homeLayout)
        assertEquals(hiddenCase.homeHiddenSections, decoded.homeHiddenSections)
        assertFalse(decoded.homeLayoutTipDismissed)

        for (preset in HomeLayoutPreset.entries) {
            val settings = AppSettings(homeLayout = preset.layout, homeHiddenSections = preset.hidden)
            val back = decode(settings.settingsJson())
            assertEquals(preset.layout, back.homeLayout, preset.name)
            assertEquals(preset.hidden, back.homeHiddenSections, preset.name)
        }
    }

    @Test
    fun openActionHeightDefaultsAndClampsWhenRestoredFromJson() {
        assertEquals(DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP, decode("{}").homeOpenActionsHeightDp)
        assertEquals(MIN_HOME_OPEN_ACTIONS_HEIGHT_DP, decode("""{"homeOpenActionsHeightDp":1}""").homeOpenActionsHeightDp)
        assertEquals(MAX_HOME_OPEN_ACTIONS_HEIGHT_DP, decode("""{"homeOpenActionsHeightDp":9000}""").homeOpenActionsHeightDp)
    }

    @Test
    fun layoutIsStoredInTheDocumentedShape() {
        assertEquals("""{"section":"open","w":1.0}""", homeLayoutJson(HomeLayoutLeaf(HomeSectionId.OPEN)).toString())
        val json = homeLayoutJson(DEFAULT_HOME_LAYOUT).toString()
        assertTrue(json.startsWith("""{"split":"column","w":1.0,"children":[{"split":"row","w":1.6,"children":["""), json)
        assertTrue(json.contains("""{"section":"tests","w":1.0}"""), json)
    }

    @Test
    fun absentJsonAndLegacyTokenDefaultTheHomeLayoutSettings() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        val jsonDefaults = decode("{}")
        val legacyDefaults = settingsFromToken(legacyToken)!!
        listOf(jsonDefaults, legacyDefaults).forEach { settings ->
            assertEquals(DEFAULT_HOME_LAYOUT, settings.homeLayout)
            assertTrue(settings.homeHiddenSections.isEmpty())
            assertFalse(settings.homeLayoutTipDismissed)
        }
        assertEquals(AppSettings().homeLayout, jsonDefaults.homeLayout)
    }

    @Test
    fun malformedLayoutFallsBackToTheDefault() {
        for (raw in listOf(
            """{"homeLayout":null}""",
            """{"homeLayout":"open"}""",
            """{"homeLayout":[1,2]}""",
            """{"homeLayout":{}}""",
            """{"homeLayout":{"split":"diagonal","children":[{"section":"open"}]}}""",
            """{"homeLayout":{"split":"row","children":[]}}""",
            """{"homeLayout":{"split":"row","children":"x"}}""",
            """{"homeLayout":{"section":"nope"}}""",
            """{"homeLayout":{"section":7}}""",
            """{"homeHiddenSections":"capture","homeLayoutTipDismissed":"yes"}""",
        )) {
            val settings = decode(raw)
            assertEquals(DEFAULT_HOME_LAYOUT, settings.homeLayout, raw)
            assertTrue(settings.homeHiddenSections.isEmpty(), raw)
            assertFalse(settings.homeLayoutTipDismissed, raw)
        }
    }

    @Test
    fun duplicateAndUnknownSectionsAreSanitized() {
        val settings = decode(
            """{"homeLayout":{"split":"row","w":1,"children":[
                {"section":"capture","w":2},{"section":"bogus","w":1},{"section":"capture","w":5},{"section":"open","w":1}]},
               "homeHiddenSections":["nope","tests","tests","diagrams",3]}""",
        )
        assertContentEquals(listOf(HomeSectionId.CAPTURE, HomeSectionId.OPEN), homeLeafSections(settings.homeLayout))
        assertEquals(listOf(HomeSectionId.TESTS, HomeSectionId.DIAGRAMS), settings.homeHiddenSections)
        assertEquals(2f, (settings.homeLayout as HomeLayoutSplit).children.first().weight)
        assertValid(settings)
    }

    @Test
    fun missingSectionsAreAppendedAtTheTrailingEdge() {
        val rowRoot = decode("""{"homeLayout":{"split":"row","children":[{"section":"open"},{"section":"capture"}]}}""")
        assertContentEquals(
            listOf(HomeSectionId.OPEN, HomeSectionId.CAPTURE, HomeSectionId.TESTS, HomeSectionId.DIAGRAMS),
            homeLeafSections(rowRoot.homeLayout),
        )
        assertEquals(HomeSplitAxis.ROW, (rowRoot.homeLayout as HomeLayoutSplit).axis)

        val leafRoot = decode("""{"homeLayout":{"section":"tests"}}""")
        assertContentEquals(
            listOf(HomeSectionId.TESTS, HomeSectionId.OPEN, HomeSectionId.CAPTURE, HomeSectionId.DIAGRAMS),
            homeLeafSections(leafRoot.homeLayout),
        )
        assertValid(leafRoot)

        val columnRoot = decode("""{"homeLayout":{"split":"column","children":[{"section":"open"},{"section":"tests"}]},"homeHiddenSections":[]}""")
        assertContentEquals(
            listOf(HomeSectionId.OPEN, HomeSectionId.TESTS, HomeSectionId.CAPTURE, HomeSectionId.DIAGRAMS),
            homeLeafSections(columnRoot.homeLayout),
        )
        assertEquals(HomeSplitAxis.COLUMN, (columnRoot.homeLayout as HomeLayoutSplit).axis)

        // A layout saved before the Diagrams section existed (the old default) gets it as a full-width row below.
        val legacyDefault = decode(
            """{"homeLayout":{"split":"column","w":1,"children":[{"split":"row","w":1.6,"children":[{"section":"open"},{"section":"capture"}]},
                {"section":"tests"}]}}""",
        )
        assertContentEquals(
            listOf(HomeSectionId.OPEN, HomeSectionId.CAPTURE, HomeSectionId.TESTS, HomeSectionId.DIAGRAMS),
            homeLeafSections(legacyDefault.homeLayout),
        )
        assertEquals(HomeSplitAxis.COLUMN, (legacyDefault.homeLayout as HomeLayoutSplit).axis)
        assertEquals(HomeSectionId.DIAGRAMS, ((legacyDefault.homeLayout as HomeLayoutSplit).children.last() as HomeLayoutLeaf).section)
        assertTrue(legacyDefault.homeHiddenSections.isEmpty())
    }

    @Test
    fun aSectionBothInTheTreeAndHiddenStaysVisible() {
        val settings = decode(
            """{"homeLayout":{"split":"row","children":[{"section":"open"},{"section":"capture"}]},"homeHiddenSections":["open","tests","diagrams"]}""",
        )
        assertEquals(listOf(HomeSectionId.TESTS, HomeSectionId.DIAGRAMS), settings.homeHiddenSections)
        assertEquals(listOf(HomeSectionId.OPEN, HomeSectionId.CAPTURE), homeLeafSections(settings.homeLayout))
    }

    @Test
    fun hidingEverythingFallsBackToTheDefaultLayout() {
        val settings = decode("""{"homeHiddenSections":["open","capture","tests","diagrams"]}""")
        assertEquals(DEFAULT_HOME_LAYOUT, settings.homeLayout)
        assertTrue(settings.homeHiddenSections.isEmpty())

        val partial = decode("""{"homeHiddenSections":["capture"]}""")
        assertEquals(listOf(HomeSectionId.CAPTURE), partial.homeHiddenSections)
        assertContentEquals(listOf(HomeSectionId.OPEN, HomeSectionId.TESTS, HomeSectionId.DIAGRAMS), homeLeafSections(partial.homeLayout))
        assertValid(partial)
    }

    @Test
    fun invalidWeightsAreRepairedAndClamped() {
        val settings = decode(
            """{"homeLayout":{"split":"row","w":"NaN","children":[
                {"section":"open","w":"NaN"},{"section":"capture","w":-4},{"section":"tests","w":0}]}}""",
        )
        assertEquals(listOf(1f, 1f, 1f, 1f), (settings.homeLayout as HomeLayoutSplit).children.map { it.weight })
        assertValid(settings)

        val clamped = decode(
            """{"homeLayout":{"split":"row","children":[{"section":"open","w":0.001},{"section":"capture","w":99999},{"section":"tests","w":"x"}]}}""",
        )
        assertEquals(listOf(HOME_MIN_WEIGHT, HOME_MAX_WEIGHT, 1f), (clamped.homeLayout as HomeLayoutSplit).children.map { it.weight }.take(3))

        val infinity = decode("""{"homeLayout":{"split":"row","children":[{"section":"open","w":"Infinity"},{"section":"capture"},{"section":"tests"}]}}""")
        assertValid(infinity)
    }

    @Test
    fun aVeryDeepTreeIsCappedAndStillValid() {
        // 50 alternating splits, "open" at the bottom: everything below the depth cap is dropped, open is re-added.
        var json = """{"section":"open"}"""
        repeat(50) { level ->
            val axis = if (level % 2 == 0) "row" else "column"
            json = """{"split":"$axis","children":[{"section":"capture"},$json]}"""
        }
        val settings = decode("""{"homeLayout":$json}""")
        assertValid(settings)
        assertTrue(depthOf(settings.homeLayout) <= 8, "depth ${depthOf(settings.homeLayout)}")
        assertNotNull(settings.homeLayout)
    }

    private fun depthOf(node: HomeLayoutNode): Int = when (node) {
        is HomeLayoutLeaf -> 1
        is HomeLayoutSplit -> 1 + (node.children.maxOfOrNull { depthOf(it) } ?: 0)
    }

    @Test
    fun diagramsSegmentRoundTripsAndDefaultsToNoTicket() {
        assertEquals(HomeDiagramSegment.NO_TICKET, AppSettings().homeDiagramsSegment)
        assertEquals(HomeDiagramSegment.NO_TICKET, decode("{}").homeDiagramsSegment)
        for (segment in HomeDiagramSegment.entries) {
            val json = AppSettings(homeDiagramsSegment = segment).settingsJson()
            assertTrue(json.contains(""""homeDiagramsSegment":"${segment.name}""""), json)
            assertEquals(segment, decode(json).homeDiagramsSegment, segment.name)
        }
    }

    @Test
    fun anUnknownOrMalformedDiagramsSegmentFallsBackToNoTicket() {
        for (raw in listOf(
            """{"homeDiagramsSegment":"EVERYTHING"}""",
            """{"homeDiagramsSegment":3}""",
            """{"homeDiagramsSegment":null}""",
            """{"homeDiagramsSegment":["ALL"]}""",
        )) {
            assertEquals(HomeDiagramSegment.NO_TICKET, decode(raw).homeDiagramsSegment, raw)
        }
        // A bad segment never costs the rest of the settings.
        val settings = decode("""{"fontSize":17,"homeDiagramsSegment":"nope"}""")
        assertEquals(17, settings.fontSize)
    }

    @Test
    fun theLegacyPositionalTokenLeavesTheDiagramsSegmentAtItsDefault() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        assertEquals(HomeDiagramSegment.NO_TICKET, settingsFromToken(legacyToken)!!.homeDiagramsSegment)
    }

    @Test
    fun aMalformedHomeLayoutNeverFailsTheWholeSettingsDecode() {
        // The rest of the settings still decode even when the home layout is garbage.
        val settings = decode("""{"fontSize":17,"homeLayout":{"split":"row","children":[{"section":"open"},42,null,"x",{"split":5}]}}""")
        assertEquals(17, settings.fontSize)
        assertValid(settings)
    }
}
