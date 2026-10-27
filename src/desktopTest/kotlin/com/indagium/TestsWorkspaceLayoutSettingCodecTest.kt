package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.TestsWorkspaceLayoutState
import com.indagium.ui.TestsWorkspacePreset
import com.indagium.ui.TestsWorkspaceTileId
import com.indagium.ui.applyTestsWorkspacePreset
import com.indagium.ui.currentTestsWorkspaceLayout
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import com.indagium.ui.testsWorkspaceLayoutFromSettings
import com.indagium.ui.testsWorkspacePresetActive
import com.indagium.ui.testsWorkspaceVisibleTiles
import com.indagium.ui.withTestsWorkspaceLayout
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestsWorkspaceLayoutSettingCodecTest {
    private fun decode(json: String): AppSettings = assertNotNull(settingsFromJson(json))

    // These weights and pinned sizes describe a deliberately uneven saved layout fixture.
    @Suppress("MagicNumber")
    private fun customState(): TestsWorkspaceLayoutState = TestsWorkspaceLayoutState(
        layout = TileSplit(
            TileAxis.ROW,
            listOf(
                TileSplit(
                    TileAxis.COLUMN,
                    listOf(
                        TileLeaf(TestsWorkspaceTileId.SUITES, 1.7f),
                        TileLeaf(TestsWorkspaceTileId.LIBRARY, 0.9f),
                        TileLeaf(TestsWorkspaceTileId.ACTIVITY, 0.6f),
                    ),
                    sizeDp = 292f,
                ),
                TileSplit(
                    TileAxis.COLUMN,
                    listOf(
                        TileLeaf(TestsWorkspaceTileId.WORKSPACE, 2.4f),
                        TileLeaf(TestsWorkspaceTileId.EXECUTION, 0.8f),
                    ),
                    weight = 1.4f,
                ),
                TileSplit(
                    TileAxis.COLUMN,
                    listOf(
                        TileLeaf(TestsWorkspaceTileId.DETAILS, 1.3f),
                        TileLeaf(TestsWorkspaceTileId.EVIDENCE, 0.7f),
                    ),
                    sizeDp = 412f,
                ),
            ),
        ),
        hidden = listOf(TestsWorkspaceTileId.ACTIVITY),
        collapsed = listOf(TestsWorkspaceTileId.EXECUTION),
        locked = true,
    )

    @Test
    fun treeWeightsHeightsAndFlagsRoundTripThroughAutosaveSettings() {
        val state = customState()
        val persisted = AppSettings().withTestsWorkspaceLayout(state)
        assertEquals(state, persisted.testsWorkspaceLayoutFromSettings())

        val restored = decode(persisted.settingsJson())
        assertEquals(state, restored.testsWorkspaceLayoutFromSettings())
        assertEquals(
            292f,
            ((restored.currentTestsWorkspaceLayout().layout as TileSplit).children[0] as TileSplit).sizeDp,
        )
        assertEquals(
            412f,
            ((restored.currentTestsWorkspaceLayout().layout as TileSplit).children[2] as TileSplit).sizeDp,
        )
        assertEquals(setOf(TestsWorkspaceTileId.SUITES, TestsWorkspaceTileId.LIBRARY, TestsWorkspaceTileId.WORKSPACE,
            TestsWorkspaceTileId.EXECUTION, TestsWorkspaceTileId.DETAILS, TestsWorkspaceTileId.EVIDENCE),
            testsWorkspaceVisibleTiles(restored.currentTestsWorkspaceLayout()))
    }

    @Test
    fun activePresetIsStillRecognizedAfterRestart() {
        TestsWorkspacePreset.entries.forEach { preset ->
            val state = applyTestsWorkspacePreset(preset)
            val restored = decode(AppSettings().withTestsWorkspaceLayout(state).settingsJson())
                .currentTestsWorkspaceLayout()
            assertTrue(testsWorkspacePresetActive(restored, preset), preset.name)
        }
    }

    @Test
    fun missingOrLegacySettingsUseTheDefaultAndDoNotAddAPositionalField() {
        val empty = decode("{}")
        assertNull(empty.testsWorkspaceLayout)
        assertEquals(AppSettings().currentTestsWorkspaceLayout(), empty.currentTestsWorkspaceLayout())
        assertFalse(empty.settingsJson().contains("testsWorkspaceLayout"))

        val token = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        val legacy = assertNotNull(settingsFromToken(token))
        assertNull(legacy.testsWorkspaceLayout)
        assertEquals(empty.currentTestsWorkspaceLayout(), legacy.currentTestsWorkspaceLayout())
    }

    @Test
    fun malformedLayoutDefaultsWithoutBreakingOtherSettings() {
        val malformed = decode(
            """
                {
                  "fontSize": 17,
                  "testsWorkspaceLayout": {
                    "layout": {"split": "diagonal", "children": [{"section": "suites"}]},
                    "hidden": "activity",
                    "collapsed": [42],
                    "locked": "yes"
                  }
                }
            """.trimIndent(),
        )
        assertEquals(17, malformed.fontSize)
        assertEquals(AppSettings().currentTestsWorkspaceLayout(), malformed.currentTestsWorkspaceLayout())

        val wrongOuterType = decode("""{"testsWorkspaceLayout":"not an object"}""")
        assertNull(wrongOuterType.testsWorkspaceLayout)
        assertEquals(AppSettings().currentTestsWorkspaceLayout(), wrongOuterType.currentTestsWorkspaceLayout())
    }

    @Test
    fun duplicateUnknownAndMissingTilesAreSanitizedAndWorkspaceCannotBeHidden() {
        val restored = decode(
            """
                {
                  "testsWorkspaceLayout": {
                    "layout": {
                      "split": "row",
                      "children": [
                        {"section": "suites", "w": 2.5},
                        {"section": "suites", "w": 9},
                        {"section": "unknown"},
                        {"section": "workspace", "w": 0.5}
                      ]
                    },
                    "hidden": ["activity", "workspace", "activity", "unknown"],
                    "collapsed": ["details", "details", "unknown"],
                    "locked": true
                  }
                }
            """.trimIndent(),
        ).currentTestsWorkspaceLayout()

        val ids = TestsWorkspaceTileId.entries.toSet()
        assertEquals(ids, testsWorkspaceVisibleTiles(restored) + restored.hidden)
        assertEquals(ids.size, testsWorkspaceVisibleTiles(restored).size + restored.hidden.size)
        assertTrue(TestsWorkspaceTileId.WORKSPACE in testsWorkspaceVisibleTiles(restored))
        assertEquals(listOf(TestsWorkspaceTileId.ACTIVITY), restored.hidden)
        assertEquals(listOf(TestsWorkspaceTileId.DETAILS), restored.collapsed)
        assertTrue(restored.locked)
        assertTrue(allWeights(restored.layout).all { it.isFinite() && it > 0f })
    }

    private fun allWeights(node: TileNode<TestsWorkspaceTileId>): List<Float> = listOf(node.weight) +
        ((node as? TileSplit)?.children?.flatMap(::allWeights) ?: emptyList())
}
