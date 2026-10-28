package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewSectionId.AI
import com.indagium.model.LogViewSectionId.DIAGRAMS
import com.indagium.model.LogViewSectionId.LOG
import com.indagium.model.LogViewSectionId.LOG_A
import com.indagium.model.LogViewSectionId.NOTES
import com.indagium.model.LogViewSectionId.TAGS
import com.indagium.model.LogViewSectionId.TOOLBAR
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.decodeLogViewLayoutFile
import com.indagium.ui.encodeLogViewLayoutFile
import com.indagium.ui.logViewTileTreeJson
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import com.indagium.ui.tileLeafSections
import com.indagium.ui.withComparePreset
import com.indagium.ui.withLogViewLayout
import com.indagium.ui.withLogViewPreset
import com.indagium.ui.withLogViewTileCollapsed
import com.indagium.ui.withLogViewTileHidden
import com.indagium.ui.withLogViewTilesLocked
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The log-view tiling fields are JSON-only, appended last (CLAUDE.md): settingsFromToken must never gain them, so a legacy
// positional token defaults them, same as `{}`.
class LogViewLayoutSettingCodecTest {
    private val sizes = LogViewLegacySizes(300f, 420f, 0.4f, 0.4f)
    private val singleIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.COMPARE && it != LogViewSectionId.TOOLBAR }.toSet()
    private val compareIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.SINGLE }.toSet()

    private fun decode(json: String): AppSettings = settingsFromJson(json)!!

    private fun withKeys(base: AppSettings, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(base.settingsJson()).jsonObject.toMutableMap()
        edit(map)
        return JsonObject(map).toString()
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun aLoneTilesColumnAndItsHeightRoundTripThroughSettingsJson() {
        val classic = LogViewLayoutPreset.CLASSIC.layout(sizes) as TileSplit<LogViewSectionId>
        val withoutSaved = (classic.children.first() as TileSplit).children
            .filterNot { c -> c is TileLeaf && c.section == LogViewSectionId.SAVED_FILTERS }
        val lone = TileSplit(TileAxis.COLUMN, listOf(TileLeaf(LogViewSectionId.SAVED_FILTERS, 1f, 321f)), 1f, 240f)
        val tree = classic.copy(children = listOf((classic.children.first() as TileSplit).copy(children = withoutSaved)) + classic.children.drop(1) + lone)
        val stored = AppSettings().withLogViewLayout(tree)
        val back = decode(stored.settingsJson())
        assertEquals(stored.logViewLayout, back.logViewLayout)
        val last = (back.logViewLayout as TileSplit).children.last() as TileSplit
        assertEquals(240f, last.sizeDp)
        assertEquals(321f, (last.children.single() as TileLeaf).sizeDp)
    }

    @Test
    fun fullStateRoundTripsThroughKeyedSettingsJson() {
        val custom = AppSettings()
            .withLogViewPreset(LogViewLayoutPreset.REVIEW, sizes)
            .withLogViewTileHidden(TAGS, true)
            .withLogViewTileCollapsed(NOTES, true)
            .withLogViewTilesLocked(true)
            .withComparePreset(LogViewLayoutPreset.COMPARE_COLUMNS, sizes)
            .copy(compareSyncScroll = true)
        val back = decode(custom.settingsJson())
        assertEquals(custom.logViewLayout, back.logViewLayout)
        assertEquals(custom.logViewHidden, back.logViewHidden)
        assertEquals(listOf(DIAGRAMS, NOTES), back.logViewCollapsed)
        assertTrue(back.logViewTilesLocked)
        assertEquals(LogViewPresetId.REVIEW, back.lastLogViewPreset)
        assertEquals(custom.compareLayout, back.compareLayout)
        assertEquals(custom.compareHidden, back.compareHidden)
        assertEquals(LogViewPresetId.COMPARE_COLUMNS, back.lastComparePreset)
        assertTrue(back.compareSyncScroll)
        assertNotNull(back.logViewLayout)
        assertEquals(singleIds, tileLeafSections(back.logViewLayout).toSet())
        assertEquals(compareIds, tileLeafSections(back.compareLayout!!).toSet())
    }

    @Test
    fun namedCustomLayoutsAndResetTargetRoundTripThroughSettingsJsonAndPortableFile() {
        val baseLayout = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)
            .withLogViewPreset(LogViewLayoutPreset.ANALYSIS, sizes)
            .withLogViewTileHidden(DIAGRAMS, true)
        val layout = CustomLogViewLayout(
            id = "custom-log-view-test",
            name = "Crash review",
            tree = baseLayout.logViewLayout!!,
            filterPanelWidth = 284f,
            annotationPanelWidth = 410f,
            rightSidebarSplit = 0.37f,
            hidden = baseLayout.logViewHidden,
            collapsed = listOf(TAGS, NOTES),
            tilesLocked = true,
            filterVisible = false,
            annotationVisible = true,
            aiPanelVisible = false,
            videoPanelVisible = true,
            filterBarVisible = true,
        )
        // Current chrome is outside the layout, but this named snapshot remembers its own in-layout toolbar position.
        val saved = AppSettings(
            toolbarPlacement = ToolbarPlacement.WITH_TABS_END,
            customLogViewLayouts = listOf(layout),
            lastCustomLogViewLayoutId = layout.id,
        )
        val restored = decode(saved.settingsJson())
        assertEquals(listOf(layout), restored.customLogViewLayouts)
        assertEquals(layout.id, restored.lastCustomLogViewLayoutId)
        assertTrue(TOOLBAR in tileLeafSections(restored.customLogViewLayouts.single().tree))
        assertEquals(true, restored.customLogViewLayouts.single().filterBarVisible)

        val imported = decodeLogViewLayoutFile(encodeLogViewLayoutFile(layout)).getOrThrow()
        assertEquals(layout.name, imported.name)
        assertEquals(layout.tree, imported.tree)
        assertEquals(layout.hidden, imported.hidden)
        assertEquals(layout.collapsed, imported.collapsed)
        assertEquals(layout.filterPanelWidth, imported.filterPanelWidth)
        assertEquals(layout.annotationPanelWidth, imported.annotationPanelWidth)
        assertEquals(layout.rightSidebarSplit, imported.rightSidebarSplit)
        assertTrue(imported.filterVisible.not())
        assertTrue(imported.annotationVisible)
        assertTrue(imported.aiPanelVisible.not())
        assertTrue(imported.videoPanelVisible)
        assertEquals(true, imported.filterBarVisible)
        assertTrue(imported.id != layout.id, "portable imports receive a fresh local id")
    }

    @Test
    fun customSettingsDropUnknownHiddenAndCollapsedIdsWithoutDroppingTheSnapshot() {
        val base = AppSettings().withLogViewPreset(LogViewLayoutPreset.ANALYSIS, sizes)
        val layout = CustomLogViewLayout(
            id = "custom-log-view-future-ids",
            name = "Future IDs",
            tree = base.logViewLayout!!,
            filterPanelWidth = 220f,
            annotationPanelWidth = 360f,
            rightSidebarSplit = 0.5f,
            hidden = listOf(DIAGRAMS),
            collapsed = listOf(TAGS),
            tilesLocked = false,
            filterVisible = true,
            annotationVisible = true,
            aiPanelVisible = true,
            videoPanelVisible = true,
        )
        val raw = Json.parseToJsonElement(
            AppSettings(customLogViewLayouts = listOf(layout), lastCustomLogViewLayoutId = layout.id).settingsJson(),
        ).jsonObject.toMutableMap()
        val savedEntry = (raw.getValue("customLogViewLayouts") as JsonArray).single().jsonObject.toMutableMap()
        savedEntry["hidden"] = parse("[\"diagrams\",\"future_hidden\"]")
        savedEntry["collapsed"] = parse("[\"tags\",\"future_collapsed\"]")
        raw["customLogViewLayouts"] = JsonArray(listOf(JsonObject(savedEntry)))

        raw["lastCustomLogViewLayoutId"] = parse("\"${layout.id}\"")
        val restored = decode(JsonObject(raw).toString())
        val snapshot = restored.customLogViewLayouts.single()
        assertEquals(listOf(DIAGRAMS), snapshot.hidden)
        assertEquals(listOf(TAGS), snapshot.collapsed)
        assertEquals(layout.id, restored.lastCustomLogViewLayoutId)
    }

    @Test
    fun portableLayoutImportIgnoresUnknownAndMissingTilesAndLetsTheSanitizerRestoreThem() {
        val base = AppSettings().withLogViewPreset(LogViewLayoutPreset.ANALYSIS, sizes)
        val layout = CustomLogViewLayout(
            id = "custom-log-view-test",
            name = "Analysis",
            tree = base.logViewLayout!!,
            filterPanelWidth = 220f,
            annotationPanelWidth = 360f,
            rightSidebarSplit = 0.5f,
            hidden = emptyList(),
            collapsed = emptyList(),
            tilesLocked = false,
            filterVisible = true,
            annotationVisible = true,
            aiPanelVisible = false,
            videoPanelVisible = true,
        )
        val future = encodeLogViewLayoutFile(layout).replace("\"section\": \"issues\"", "\"section\": \"future_panel\"")
        val imported = decodeLogViewLayoutFile(future).getOrThrow()
        assertTrue(LogViewSectionId.ISSUES in tileLeafSections(imported.tree), "the sanitizer restores the missing known tile")
    }

    @Test
    fun portableLayoutImportKeepsTheInLayoutToolbarPosition() {
        val saved = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)
            .withLogViewPreset(LogViewLayoutPreset.ANALYSIS, sizes)
        val layout = CustomLogViewLayout(
            id = "custom-log-view-toolbar",
            name = "Toolbar in layout",
            tree = saved.logViewLayout!!,
            filterPanelWidth = 220f,
            annotationPanelWidth = 360f,
            rightSidebarSplit = 0.5f,
            hidden = emptyList(),
            collapsed = emptyList(),
            tilesLocked = false,
            filterVisible = true,
            annotationVisible = true,
            aiPanelVisible = false,
            videoPanelVisible = true,
        )

        val imported = decodeLogViewLayoutFile(encodeLogViewLayoutFile(layout)).getOrThrow()
        assertTrue(TOOLBAR in tileLeafSections(imported.tree))
        assertEquals(layout.tree, imported.tree, "the imported toolbar keeps its saved location")
    }

    @Test
    fun portableLayoutImportRejectsWrongFormatAndFutureVersion() {
        assertTrue(decodeLogViewLayoutFile("{} ").isFailure)
        assertTrue(
            decodeLogViewLayoutFile("""{"format":"indagium-log-view-layout","version":99}""").exceptionOrNull()
                ?.message.orEmpty().contains("newer version"),
        )
    }

    @Test
    fun pinnedSizesAndWeightsRoundTrip() {
        val stored = AppSettings().withLogViewPreset(LogViewLayoutPreset.CLASSIC, sizes)
        val back = decode(stored.settingsJson()).logViewLayout
        assertEquals(stored.logViewLayout, back)
        val root = back as TileSplit
        assertEquals(300f, root.children[0].sizeDp)
        assertEquals(420f, root.children[2].sizeDp)
        val right = (root.children[2] as TileSplit).children.associate { (it as TileLeaf).section to it.weight }
        assertEquals(0.4f, right.getValue(NOTES), 1e-6f)
        assertEquals(0.6f, right.getValue(AI), 1e-6f)
    }

    @Test
    fun absentJsonAndLegacyTokenGiveDefaults() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        for (settings in listOf(decode("{}"), settingsFromToken(legacyToken)!!)) {
            assertNull(settings.logViewLayout)
            assertNull(settings.compareLayout)
            assertEquals(emptyList(), settings.logViewHidden)
            assertEquals(emptyList(), settings.compareHidden)
            assertEquals(listOf(DIAGRAMS), settings.logViewCollapsed)
            assertFalse(settings.logViewTilesLocked)
            assertEquals(LogViewPresetId.CLASSIC, settings.lastLogViewPreset)
            assertEquals(LogViewPresetId.COMPARE_GRID, settings.lastComparePreset)
            assertFalse(settings.compareSyncScroll)
        }
    }

    @Test
    fun aFreshSettingsObjectWritesNoLayoutKeyUntilALayoutIsStored() {
        val keys = Json.parseToJsonElement(AppSettings().settingsJson()).jsonObject.keys
        assertFalse("logViewLayout" in keys)
        assertFalse("compareLayout" in keys)
        assertTrue("logViewHidden" in keys && "compareHidden" in keys)
        val stored = AppSettings().withLogViewPreset(LogViewLayoutPreset.CLASSIC, sizes)
        assertTrue("logViewLayout" in Json.parseToJsonElement(stored.settingsJson()).jsonObject.keys)
    }

    @Test
    fun malformedValuesFallBackWithoutBreakingTheRestOfTheSettings() {
        val raw = withKeys(AppSettings().copy(fontSize = 17)) {
            it["logViewLayout"] = parse("""{"split":"weird"}""")
            it["logViewHidden"] = parse("42")
            it["logViewCollapsed"] = parse("\"tags\"")
            it["logViewTilesLocked"] = parse("\"yes\"")
            it["lastLogViewPreset"] = parse("[1]")
            it["compareLayout"] = parse("[]")
            it["compareHidden"] = parse("{\"a\":1}")
            it["lastComparePreset"] = parse("\"nonsense\"")
            it["compareSyncScroll"] = parse("7")
        }
        val s = decode(raw)
        assertEquals(17, s.fontSize)
        assertNull(s.logViewLayout)
        assertNull(s.compareLayout)
        assertEquals(emptyList(), s.logViewHidden)
        assertEquals(emptyList(), s.compareHidden)
        assertEquals(listOf(DIAGRAMS), s.logViewCollapsed)
        assertFalse(s.logViewTilesLocked)
        assertEquals(LogViewPresetId.CLASSIC, s.lastLogViewPreset)
        assertEquals(LogViewPresetId.COMPARE_GRID, s.lastComparePreset)
        assertFalse(s.compareSyncScroll)
    }

    @Test
    fun aPresetOfTheWrongScopeIsIgnored() {
        val raw = withKeys(AppSettings()) {
            it["lastLogViewPreset"] = parse("\"COMPARE_GRID\"")
            it["lastComparePreset"] = parse("\"REVIEW\"")
        }
        val s = decode(raw)
        assertEquals(LogViewPresetId.CLASSIC, s.lastLogViewPreset)
        assertEquals(LogViewPresetId.COMPARE_GRID, s.lastComparePreset)
    }

    @Test
    fun unknownIdsAreDroppedAndTheLayoutIsCompleted() {
        val tree = parse(
            """{"split":"row","w":1,"children":[
                {"section":"log","w":2},{"section":"hologram","w":1},{"section":"notes","w":1},
                {"section":"log_a","w":1},{"section":"notes","w":9}]}""",
        )
        val raw = withKeys(AppSettings()) {
            it["logViewLayout"] = tree
            it["logViewHidden"] = parse("""["ai","warp","log","log_a"]""")
            it["logViewCollapsed"] = parse("""["tags","nope","tags"]""")
        }
        val s = decode(raw)
        val layout = s.logViewLayout!!
        val ids = tileLeafSections(layout)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(singleIds, ids.toSet())
        assertEquals(listOf(AI), s.logViewHidden)
        assertEquals(listOf(TAGS), s.logViewCollapsed)
    }

    @Test
    fun aLayoutThatDecodesToNothingIsNullAndHiddenIsStillCleaned() {
        val raw = withKeys(AppSettings()) {
            it["logViewLayout"] = parse("""{"split":"row","children":[{"section":"ghost"}]}""")
            it["logViewHidden"] = parse("""["tags","log","log_b"]""")
        }
        val s = decode(raw)
        assertNull(s.logViewLayout)
        assertEquals(listOf(TAGS), s.logViewHidden)
    }

    @Test
    fun pinnedSizesAreClampedOnDecode() {
        val tree = parse(
            """{"split":"row","w":1,"children":[
                {"section":"log","w":1},{"section":"notes","w":1,"dp":5},{"section":"ai","w":1,"dp":99999}]}""",
        )
        val s = decode(withKeys(AppSettings()) { it["logViewLayout"] = tree })

        fun pins(n: TileNode<LogViewSectionId>): List<Float> =
            listOfNotNull(n.sizeDp) + ((n as? TileSplit)?.children?.flatMap { pins(it) } ?: emptyList())
        val all = pins(s.logViewLayout!!)
        assertEquals(listOf(80f, 2000f), all.sorted())
    }

    @Test
    fun theCompareLayoutIsSanitizedAgainstTheCompareScope() {
        val tree = parse("""{"split":"row","children":[{"section":"log"},{"section":"log_a"}]}""")
        val s = decode(withKeys(AppSettings()) { it["compareLayout"] = tree })
        val ids = tileLeafSections(s.compareLayout!!)
        assertEquals(compareIds, ids.toSet())
        assertFalse(LOG in ids)
        assertTrue(LOG_A in ids)
    }

    @Test
    fun heightsPinnedInAScrollingColumnRoundTripAndAnOldTreeDecodesWithoutThem() {
        val tall = TileSplit(
            TileAxis.ROW,
            listOf(
                TileLeaf(LOG),
                TileSplit(TileAxis.COLUMN, listOf(TileLeaf(TAGS, 1f, 420f), TileLeaf(NOTES, 1f, 1750f), TileLeaf(AI)), 1f, 250f),
            ),
        )
        val stored = AppSettings().copy(logViewLayout = tall)
        val back = decode(stored.settingsJson()).logViewLayout!!

        fun leaf(id: LogViewSectionId, node: TileNode<LogViewSectionId> = back): TileLeaf<LogViewSectionId>? = when (node) {
            is TileLeaf -> node.takeIf { it.section == id }
            is TileSplit -> node.children.firstNotNullOfOrNull { leaf(id, it) }
        }
        assertEquals(420f, leaf(TAGS)?.sizeDp, "heights are the leaves' own sizeDp, no new field")
        assertEquals(1750f, leaf(NOTES)?.sizeDp)
        assertNull(leaf(AI)?.sizeDp)
        // An autosave written before heights existed: weights, one pinned Log level; it decodes as it was written.
        val old = AppSettings().withLogViewPreset(LogViewLayoutPreset.CLASSIC, sizes)
        assertEquals(old.logViewLayout, decode(old.settingsJson()).logViewLayout)
        val filterColumn = ((old.logViewLayout as TileSplit).children[0] as TileSplit).children
        assertTrue(filterColumn.all { it.sizeDp == null || (it as TileLeaf).section == LogViewSectionId.LOG_LEVEL })
    }

    @Test
    fun treeJsonUsesStableKeys() {
        val json = logViewTileTreeJson(TileSplit(TileAxis.ROW, listOf(TileLeaf(LogViewSectionId.MESSAGE_RULES, 1f, 120f), TileLeaf(LOG))))
        assertTrue(json.toString().contains("\"section\":\"message_rules\""))
        assertTrue(json.toString().contains("\"dp\":120.0"))
    }
}
