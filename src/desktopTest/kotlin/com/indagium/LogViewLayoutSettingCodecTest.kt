package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewSectionId.AI
import com.indagium.model.LogViewSectionId.DIAGRAMS
import com.indagium.model.LogViewSectionId.LOG
import com.indagium.model.LogViewSectionId.LOG_A
import com.indagium.model.LogViewSectionId.NOTES
import com.indagium.model.LogViewSectionId.TAGS
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.logViewTileTreeJson
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import com.indagium.ui.tileLeafSections
import com.indagium.ui.withComparePreset
import com.indagium.ui.withLogViewPreset
import com.indagium.ui.withLogViewTileCollapsed
import com.indagium.ui.withLogViewTileHidden
import com.indagium.ui.withLogViewTilesLocked
import kotlinx.serialization.json.Json
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
    private val singleIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.COMPARE }.toSet()
    private val compareIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.SINGLE }.toSet()

    private fun decode(json: String): AppSettings = settingsFromJson(json)!!

    private fun withKeys(base: AppSettings, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(base.settingsJson()).jsonObject.toMutableMap()
        edit(map)
        return JsonObject(map).toString()
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

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
        assertEquals(listOf(NOTES), back.logViewCollapsed)
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
            assertEquals(listOf(DIAGRAMS), settings.logViewHidden)
            assertEquals(listOf(DIAGRAMS), settings.compareHidden)
            assertEquals(emptyList(), settings.logViewCollapsed)
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
        assertEquals(listOf(DIAGRAMS), s.logViewHidden)
        assertEquals(listOf(DIAGRAMS), s.compareHidden)
        assertEquals(emptyList(), s.logViewCollapsed)
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
    fun treeJsonUsesStableKeys() {
        val json = logViewTileTreeJson(TileSplit(TileAxis.ROW, listOf(TileLeaf(LogViewSectionId.MESSAGE_RULES, 1f, 120f), TileLeaf(LOG))))
        assertTrue(json.toString().contains("\"section\":\"message_rules\""))
        assertTrue(json.toString().contains("\"dp\":120.0"))
    }
}
