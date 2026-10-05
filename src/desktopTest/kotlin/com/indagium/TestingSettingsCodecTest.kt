package com.indagium

import com.indagium.model.AppSettings
import com.indagium.testing.model.DEFAULT_TRACKER_AUTH_HEADER
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.TrackerSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FAKE_TOKEN = "must-never-be-serialized-1234"

/** The `tracker` and `testing` keys of the keyed settings JSON (JSON form only; the frozen positional decoder is untouched). */
class TestingSettingsCodecTest {
    private val tracker = TrackerSettings(
        enabled = true, name = "Jira", mcpUrl = "https://tracker.example.com/mcp", authHeaderName = "X-Api-Key", bearerPrefix = false,
        prompt = "Project ABC\nType Bug\nLabels: found-by-indagium", agentProfileId = "profile-1",
    )
    private val testing = TestingSettings(
        defaultJudgeProfileId = "profile-2", defaultJudgeMode = JudgeMode.EVERY_STEP.wire,
        evidence = EvidenceFlags(video = true, screenshots = false, logcat = true, transcript = false), confirmationTimeoutMinutes = 12,
    )

    @Test
    fun theTrackerAndTestingSettingsRoundTrip() {
        val decoded = settingsFromJson(AppSettings(tracker = tracker, testing = testing).settingsJson())!!

        assertEquals(tracker, decoded.tracker)
        assertEquals(testing, decoded.testing)
        assertEquals(12 * 60_000L, decoded.testing.confirmationTimeoutMs)
    }

    @Test
    fun missingKeysFallBackToTheDefaults() {
        val decoded = settingsFromJson("{}")!!

        assertEquals(TrackerSettings(), decoded.tracker)
        assertEquals(TestingSettings(), decoded.testing)
        assertFalse(decoded.tracker.enabled)
        assertEquals(DEFAULT_TRACKER_AUTH_HEADER, decoded.tracker.authHeaderName)
        assertTrue(decoded.tracker.bearerPrefix)
        assertEquals(5, decoded.testing.confirmationTimeoutMinutes)
        assertEquals(JudgeMode.OFF, decoded.testing.judgeMode)
        assertEquals(EvidenceFlags(), decoded.testing.evidence)
        assertEquals(AppSettings().tracker, decoded.tracker)
    }

    @Test
    fun anOldDocumentWithoutTheKeysStillDecodesEverythingElse() {
        val written = AppSettings(fontSize = 15, tracker = tracker).settingsJson()
        val old = Json.parseToJsonElement(written).jsonObject.filterKeys { it != "tracker" && it != "testing" }
        val decoded = settingsFromJson(JsonObject(old).toString())!!

        assertEquals(15, decoded.fontSize)
        assertEquals(TrackerSettings(), decoded.tracker)
    }

    @Test
    fun aBlankAuthHeaderMeansNoAuthenticationAndIsKeptBlank() {
        val decoded = settingsFromJson(AppSettings(tracker = TrackerSettings(authHeaderName = "")).settingsJson())!!

        assertEquals("", decoded.tracker.authHeaderName)
        assertFalse(decoded.tracker.needsToken)
        assertTrue(TrackerSettings().needsToken)
    }

    @Test
    fun valuesOfTheWrongTypeOrOutOfRangeAreTolerated() {
        val decoded = settingsFromJson(
            """{"tracker":{"enabled":"yes","name":5,"mcpUrl":[1],"agentProfileId":"  "},""" +
                """"testing":{"defaultJudgeMode":"sometimes","confirmationTimeoutMinutes":0,"evidence":"all"}}""",
        )!!

        assertEquals(TrackerSettings(), decoded.tracker)
        assertEquals(JudgeMode.OFF.wire, decoded.testing.defaultJudgeMode)
        assertEquals(1, decoded.testing.confirmationTimeoutMinutes, "clamped up to the minimum")
        assertEquals(EvidenceFlags(), decoded.testing.evidence)
        assertEquals(120, settingsFromJson("""{"testing":{"confirmationTimeoutMinutes":99999}}""")!!.testing.confirmationTimeoutMinutes)
        assertEquals(TestingSettings(), settingsFromJson("""{"testing":"nonsense","tracker":3}""")!!.testing)
    }

    @Test
    fun theWrittenJsonCarriesNoTokenAndAnUnknownTokenKeyIsDroppedOnTheNextWrite() {
        val written = AppSettings(tracker = tracker, testing = testing).settingsJson()
        val trackerKeys = Json.parseToJsonElement(written).jsonObject["tracker"]!!.jsonObject.keys
        assertEquals(setOf("enabled", "name", "mcpUrl", "authHeaderName", "bearerPrefix", "prompt", "agentProfileId"), trackerKeys)
        assertFalse(written.lowercase().contains("token"), "no key or value in the settings mentions a token")

        // A hand-edited file that tries to smuggle a token in is read without it and written back without it.
        val tampered = written.replace("\"tracker\":{", "\"tracker\":{\"token\":\"$FAKE_TOKEN\",\"authorization\":\"Bearer $FAKE_TOKEN\",")
        assertTrue(tampered.contains(FAKE_TOKEN))
        val rewritten = settingsFromJson(tampered)!!.settingsJson()
        assertFalse(rewritten.contains(FAKE_TOKEN))
        assertNotNull(settingsFromJson(rewritten))
    }
}
