package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.LogViewSectionId
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.CHROME_VERTICAL_TAB_BAR_DP
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The chrome fields (tab strip look, chrome position, lock) are JSON-only and appended last (CLAUDE.md): settingsFromToken must
// never gain them, so a legacy token and an absent key both give the long-standing tab strip.
class ChromeSettingsCodecTest {
    private fun decode(json: String): AppSettings = settingsFromJson(json)!!

    private fun withKeys(base: AppSettings, edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(base.settingsJson()).jsonObject.toMutableMap()
        edit(map)
        return JsonObject(map).toString()
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    private fun assertDefaults(s: AppSettings) {
        assertEquals(TabStripStyle.CURRENT, s.tabStripStyle)
        assertEquals(TabSizeMode.FIXED, s.tabSizeMode)
        assertEquals(TabLabelMode.FILE_SESSION, s.tabLabelMode)
        assertFalse(s.tabShowTypeBadge)
        assertFalse(s.tabShowSourceStripe)
        assertTrue(s.tabCloseOnEvery)
        assertFalse(s.chromeLocked)
        assertEquals(TabStripPosition.TOP, s.tabStripPosition)
        assertEquals(ToolbarPlacement.WITH_TABS_END, s.toolbarPlacement)
        assertEquals(ChromeEdge.TOP, s.toolbarEdge)
        assertFalse(s.toolbarOuter)
        assertEquals(ToolbarAlign.START, s.toolbarAlign)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP, s.verticalTabBarWidthDp)
        assertEquals(ToolbarPlacement.WITH_TABS_END, s.toolbarFallbackPlacement)
    }

    @Test
    fun everyFieldRoundTripsThroughSettingsJson() {
        val custom = AppSettings(fontSize = 15).copy(
            tabStripStyle = TabStripStyle.TWO_LINE,
            tabSizeMode = TabSizeMode.FIT,
            tabLabelMode = TabLabelMode.SESSION_ONLY,
            tabShowTypeBadge = true,
            tabShowSourceStripe = true,
            tabCloseOnEvery = false,
            chromeLocked = true,
            tabStripPosition = TabStripPosition.LEFT,
            toolbarPlacement = ToolbarPlacement.OWN_EDGE,
            toolbarEdge = ChromeEdge.RIGHT,
            toolbarOuter = true,
            toolbarAlign = ToolbarAlign.END,
            verticalTabBarWidthDp = 312.5f,
            toolbarFallbackPlacement = ToolbarPlacement.OWN_EDGE,
        )
        val back = decode(custom.settingsJson())
        assertEquals(15, back.fontSize)
        assertEquals(TabStripStyle.TWO_LINE, back.tabStripStyle)
        assertEquals(TabSizeMode.FIT, back.tabSizeMode)
        assertEquals(TabLabelMode.SESSION_ONLY, back.tabLabelMode)
        assertTrue(back.tabShowTypeBadge)
        assertTrue(back.tabShowSourceStripe)
        assertFalse(back.tabCloseOnEvery)
        assertTrue(back.chromeLocked)
        assertEquals(TabStripPosition.LEFT, back.tabStripPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, back.toolbarPlacement)
        assertEquals(ChromeEdge.RIGHT, back.toolbarEdge)
        assertTrue(back.toolbarOuter)
        assertEquals(ToolbarAlign.END, back.toolbarAlign)
        assertEquals(312.5f, back.verticalTabBarWidthDp)
        assertEquals(ToolbarPlacement.OWN_EDGE, back.toolbarFallbackPlacement)
    }

    @Test
    fun everyStyleAndEnumValueSurvives() {
        TabStripStyle.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabStripStyle = it).settingsJson()).tabStripStyle) }
        TabLabelMode.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabLabelMode = it).settingsJson()).tabLabelMode) }
        TabStripPosition.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabStripPosition = it).settingsJson()).tabStripPosition) }
        ToolbarPlacement.entries.forEach { assertEquals(it, decode(AppSettings().copy(toolbarPlacement = it).settingsJson()).toolbarPlacement) }
        ChromeEdge.entries.forEach { assertEquals(it, decode(AppSettings().copy(toolbarEdge = it).settingsJson()).toolbarEdge) }
        ToolbarAlign.entries.forEach { assertEquals(it, decode(AppSettings().copy(toolbarAlign = it).settingsJson()).toolbarAlign) }
    }

    @Test
    fun absentKeysAndALegacyTokenGiveDefaults() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        assertDefaults(decode("{}"))
        assertDefaults(settingsFromToken(legacyToken)!!)
        val stripped = withKeys(AppSettings().copy(tabStripStyle = TabStripStyle.QUIET, chromeLocked = true)) { map ->
            map.keys.removeAll(
                listOf(
                    "tabStripStyle", "tabSizeMode", "tabLabelMode", "tabShowTypeBadge", "tabShowSourceStripe",
                    "tabCloseOnEvery", "chromeLocked", "tabStripPosition", "toolbarPlacement", "toolbarEdge",
                    "toolbarOuter", "toolbarAlign", "verticalTabBarWidthDp", "toolbarFallbackPlacement",
                ).toSet(),
            )
        }
        assertDefaults(decode(stripped))
    }

    @Test
    fun malformedValuesFallBackWithoutBreakingTheRestOfTheSettings() {
        val raw = withKeys(AppSettings().copy(fontSize = 17)) {
            it["tabStripStyle"] = parse("\"HOLOGRAM\"")
            it["tabSizeMode"] = parse("7")
            it["tabLabelMode"] = parse("[\"FILE_ONLY\"]")
            it["tabShowTypeBadge"] = parse("\"yes\"")
            it["tabShowSourceStripe"] = parse("{}")
            it["tabCloseOnEvery"] = parse("\"false\"")
            it["chromeLocked"] = parse("1")
            it["tabStripPosition"] = parse("\"top\"")
            it["toolbarPlacement"] = parse("null")
            it["toolbarEdge"] = parse("true")
            it["toolbarOuter"] = parse("\"yes\"")
            it["toolbarAlign"] = parse("\"MIDDLE\"")
            it["verticalTabBarWidthDp"] = parse("\"wide\"")
            it["toolbarFallbackPlacement"] = parse("5")
        }
        val s = decode(raw)
        assertEquals(17, s.fontSize)
        assertDefaults(s)
    }

    @Test
    fun oneBadKeyDoesNotResetItsNeighbours() {
        val raw = withKeys(AppSettings().copy(tabStripStyle = TabStripStyle.SEGMENTED, tabSizeMode = TabSizeMode.FIT)) {
            it["tabLabelMode"] = parse("\"???\"")
        }
        val s = decode(raw)
        assertEquals(TabStripStyle.SEGMENTED, s.tabStripStyle)
        assertEquals(TabSizeMode.FIT, s.tabSizeMode)
        assertEquals(TabLabelMode.FILE_SESSION, s.tabLabelMode)
    }

    @Test
    fun theTabBarWidthIsKeptInsideTheSplittersRange() {
        assertEquals(480f, decode(withKeys(AppSettings()) { it["verticalTabBarWidthDp"] = parse("9000") }).verticalTabBarWidthDp)
        assertEquals(140f, decode(withKeys(AppSettings()) { it["verticalTabBarWidthDp"] = parse("3") }).verticalTabBarWidthDp)
        assertEquals(260f, decode(withKeys(AppSettings()) { it["verticalTabBarWidthDp"] = parse("260") }).verticalTabBarWidthDp)
        assertEquals(200f, decode(withKeys(AppSettings()) { it["verticalTabBarWidthDp"] = parse("null") }).verticalTabBarWidthDp)
    }

    @Test
    fun theFallbackPlacementIsNeverInLayoutItself() {
        val raw = withKeys(AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)) { it["toolbarFallbackPlacement"] = parse("\"IN_LAYOUT\"") }
        val s = decode(raw)
        assertEquals(ToolbarPlacement.IN_LAYOUT, s.toolbarPlacement)
        assertEquals(ToolbarPlacement.WITH_TABS_END, s.toolbarFallbackPlacement)
    }

    @Test
    fun anOldSettingsFileDecodesToTheSameLayoutAsBefore() {
        // What the previous version wrote: the placement fields without any of the new ones.
        val old = withKeys(AppSettings(tabStripPosition = TabStripPosition.LEFT, toolbarPlacement = ToolbarPlacement.BESIDE_TABS)) { map ->
            map.keys.removeAll(setOf("toolbarOuter", "toolbarAlign", "verticalTabBarWidthDp", "toolbarFallbackPlacement"))
        }
        val s = decode(old)
        assertEquals(ToolbarPlacement.BESIDE_TABS, s.toolbarPlacement, "the legacy value is read as it was written")
        assertFalse(s.toolbarOuter)
        assertEquals(ToolbarAlign.START, s.toolbarAlign)
        assertEquals(200f, s.verticalTabBarWidthDp)
        // And it still draws the toolbar's own bar on the content side of the tabs, its buttons at the start.
        val a = com.indagium.ui.chromeArrangement(s)
        assertEquals(ToolbarPlacement.OWN_EDGE, a.placement)
        assertFalse(a.toolbarOuter)
        assertEquals(ToolbarAlign.START, a.toolbarAlign)
        assertEquals(ChromeEdge.LEFT, a.toolbarBarEdge)
        // A file from before the placement fields existed keeps the long-standing default bar.
        val default = com.indagium.ui.chromeArrangement(decode("{}"))
        assertFalse(default.cards)
        assertTrue(default.toolbarInTabBar)
    }

    @Test
    fun theToolbarTileSurvivesAnInLayoutRoundTripAndGoesWhenTheToolbarLeaves() {
        val tree = TileSplit(
            TileAxis.ROW,
            listOf(TileLeaf(LogViewSectionId.TOOLBAR, 1f, 80f), TileLeaf(LogViewSectionId.LOG)),
        )
        val inLayout = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT, logViewLayout = tree)
        val back = decode(inLayout.settingsJson())
        assertEquals(ToolbarPlacement.IN_LAYOUT, back.toolbarPlacement)
        assertTrue(LogViewSectionId.TOOLBAR in com.indagium.ui.tileLeafSections(assertNotNull(back.logViewLayout)))
        // The same tree with the toolbar somewhere else: the tile is not kept.
        val elsewhere = decode(inLayout.copy(toolbarPlacement = ToolbarPlacement.WITH_TABS_END).settingsJson())
        assertFalse(LogViewSectionId.TOOLBAR in com.indagium.ui.tileLeafSections(assertNotNull(elsewhere.logViewLayout)))
        // Old JSON, no toolbar tile anywhere: the layout is exactly what it always decoded to.
        val plain = AppSettings(logViewLayout = com.indagium.ui.classicLogViewLayout(com.indagium.ui.LogViewLegacySizes()))
        assertEquals(
            com.indagium.ui.tileLeafSections(assertNotNull(plain.logViewLayout)).toSet(),
            com.indagium.ui.tileLeafSections(assertNotNull(decode(plain.settingsJson()).logViewLayout)).toSet(),
        )
        assertFalse(LogViewSectionId.TOOLBAR in com.indagium.ui.tileLeafSections(assertNotNull(decode(plain.settingsJson()).logViewLayout)))
    }
}
