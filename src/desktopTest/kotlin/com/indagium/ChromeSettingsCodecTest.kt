package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import com.indagium.model.ToolbarPlacement
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
    }

    @Test
    fun everyStyleAndEnumValueSurvives() {
        TabStripStyle.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabStripStyle = it).settingsJson()).tabStripStyle) }
        TabLabelMode.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabLabelMode = it).settingsJson()).tabLabelMode) }
        TabStripPosition.entries.forEach { assertEquals(it, decode(AppSettings().copy(tabStripPosition = it).settingsJson()).tabStripPosition) }
        ToolbarPlacement.entries.forEach { assertEquals(it, decode(AppSettings().copy(toolbarPlacement = it).settingsJson()).toolbarPlacement) }
        ChromeEdge.entries.forEach { assertEquals(it, decode(AppSettings().copy(toolbarEdge = it).settingsJson()).toolbarEdge) }
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
}
