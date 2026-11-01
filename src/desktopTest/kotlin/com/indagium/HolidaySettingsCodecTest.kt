package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.*
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
import kotlin.test.assertTrue

// The seasonal-theme choices are JSON-only (one nested "holiday" object): settingsFromToken must never gain them, so a legacy
// token, an absent key and a malformed value all give the long-standing look (Off).
class HolidaySettingsCodecTest {
    private val defaults = HolidaySettings()

    private fun decode(json: String): AppSettings = settingsFromJson(json)!!

    private fun parse(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun withHolidayObject(base: AppSettings, edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(base.settingsJson()).jsonObject.toMutableMap()
        val holiday = root.getValue("holiday").jsonObject.toMutableMap()
        edit(holiday)
        root["holiday"] = JsonObject(holiday)
        return JsonObject(root).toString()
    }

    private val custom = HolidaySettings(
        mode = HolidayMode.AUTOMATIC,
        pack = HolidayPack.PUMPKIN_PATCH,
        lightness = HolidayLightness.DARK,
        intensity = HolidayIntensity.CALM,
        funIcons = false,
        pauseWhileBusy = false,
        effectToggles = mapOf("pumpkin.embers" to false, "pumpkin.bats" to false, "pumpkin.lightning" to true, "future.sparkles" to true),
        tabScene = false,
        filterPicture = false,
        animatePicture = false,
        notesCompanion = false,
    )

    @Test
    fun aNonDefaultHolidayRoundTripsAndKeepsItsNeighbours() {
        val back = decode(AppSettings(fontSize = 15).copy(holiday = custom).settingsJson())
        assertEquals(custom, back.holiday)
        assertEquals(15, back.fontSize)
    }

    @Test
    fun enumsAreStoredByName() {
        val json = AppSettings().copy(holiday = custom).settingsJson()
        assertTrue("\"AUTOMATIC\"" in json && "\"CALM\"" in json && "\"PUMPKIN_PATCH\"" in json, json)
    }

    @Test
    fun everyEnumValueSurvives() {
        HolidayMode.entries.forEach { assertEquals(it, decode(AppSettings().copy(holiday = defaults.copy(mode = it)).settingsJson()).holiday.mode) }
        HolidayLightness.entries.forEach {
            assertEquals(it, decode(AppSettings().copy(holiday = defaults.copy(lightness = it)).settingsJson()).holiday.lightness)
        }
        HolidayIntensity.entries.forEach {
            assertEquals(it, decode(AppSettings().copy(holiday = defaults.copy(intensity = it)).settingsJson()).holiday.intensity)
        }
    }

    @Test
    fun everyPackSurvivesAndPumpkinStaysTheDefault() {
        assertEquals(HolidayPack.PUMPKIN_PATCH, AppSettings().holiday.pack)
        HolidayPack.entries.forEach { pack ->
            val decoded = decode(AppSettings().copy(holiday = defaults.copy(pack = pack)).settingsJson())
            assertEquals(pack, decoded.holiday.pack)
        }
    }

    @Test
    fun aMissingHolidayKeyGivesDefaults() {
        val root = Json.parseToJsonElement(AppSettings().copy(holiday = custom).settingsJson()).jsonObject.toMutableMap()
        root.remove("holiday")
        assertEquals(defaults, decode(JsonObject(root).toString()).holiday)
        assertEquals(defaults, decode("{}").holiday)
    }

    @Test
    fun aLegacyTokenGivesDefaults() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~" else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }
        assertEquals(defaults, settingsFromToken(legacyToken)!!.holiday)
    }

    @Test
    fun unknownEnumNamesFallBackToDefaults() {
        val raw = withHolidayObject(AppSettings().copy(holiday = custom)) {
            it["mode"] = parse("\"SOMETIMES\"")
            it["pack"] = parse("\"EASTER_BUNNY\"")
            it["lightness"] = parse("\"dark\"")
            it["intensity"] = parse("\"LOUD\"")
        }
        val h = decode(raw).holiday
        assertEquals(defaults.mode, h.mode)
        assertEquals(defaults.pack, h.pack)
        assertEquals(defaults.lightness, h.lightness)
        assertEquals(defaults.intensity, h.intensity)
        // The valid siblings are untouched.
        assertEquals(custom.copy(mode = defaults.mode, pack = defaults.pack, lightness = defaults.lightness, intensity = defaults.intensity), h)
    }

    @Test
    fun wrongTypesFallBackToDefaultsWithoutBreakingTheRest() {
        val raw = withHolidayObject(AppSettings(fontSize = 17).copy(holiday = custom)) {
            it["mode"] = parse("3")
            it["intensity"] = parse("[\"CALM\"]")
            it["funIcons"] = parse("\"false\"")
            it["effects"] = parse("[1]")
            it["tabScene"] = parse("\"yes\"")
        }
        val s = decode(raw)
        assertEquals(17, s.fontSize)
        val h = s.holiday
        assertEquals(defaults.mode, h.mode)
        assertEquals(defaults.intensity, h.intensity)
        assertEquals(defaults.funIcons, h.funIcons)
        assertEquals(defaults.effectToggles, h.effectToggles)
        assertEquals(defaults.tabScene, h.tabScene)
        // Keys that were left alone keep their non-default values.
        assertEquals(custom.lightness, h.lightness)
        assertEquals(custom.filterPicture, h.filterPicture)
        assertEquals(custom.notesCompanion, h.notesCompanion)
    }

    @Test
    fun aHolidayValueThatIsNotAnObjectGivesDefaults() {
        val root = Json.parseToJsonElement(AppSettings().copy(holiday = custom).settingsJson()).jsonObject.toMutableMap()
        root["holiday"] = parse("\"ALWAYS\"")
        assertEquals(defaults, decode(JsonObject(root).toString()).holiday)
    }

    @Test
    fun anEffectEntryThatIsNotABooleanIsDroppedAndTheRestKept() {
        val raw = withHolidayObject(AppSettings().copy(holiday = custom)) {
            it["effects"] = parse("{\"pumpkin.bats\":false,\"pumpkin.embers\":\"no\",\"x\":3}")
        }
        assertEquals(mapOf("pumpkin.bats" to false), decode(raw).holiday.effectToggles)
    }
}
