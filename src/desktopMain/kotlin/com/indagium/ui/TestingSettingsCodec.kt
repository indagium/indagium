package com.indagium.ui

import com.indagium.testing.model.DEFAULT_TRACKER_AUTH_HEADER
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.MAX_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.MAX_TRACKER_PROMPT_CHARS
import com.indagium.testing.model.MAX_TRACKER_TEXT_CHARS
import com.indagium.testing.model.MIN_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.TrackerSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// The JSON form of the tracker and testing settings: the `tracker` and `testing` keys of AppSettings.settingsJson(). Lookups
// are by name with the field's own default, tolerant of a missing key or a value of the wrong type (a hand-edited file or a
// newer build never fails the whole settings document). The tracker's access token has no field here and never will: the
// SecretStore is its only home.

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.flag(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun trackerSettingsJson(tracker: TrackerSettings): JsonObject = buildJsonObject {
    put("enabled", tracker.enabled)
    put("name", tracker.name)
    put("mcpUrl", tracker.mcpUrl)
    put("authHeaderName", tracker.authHeaderName)
    put("bearerPrefix", tracker.bearerPrefix)
    put("prompt", tracker.prompt)
    tracker.agentProfileId?.let { put("agentProfileId", it) }
}

internal fun trackerSettingsFromJson(element: JsonElement?): TrackerSettings {
    val o = element.asObject() ?: return TrackerSettings()
    return TrackerSettings(
        enabled = o.flag("enabled", false),
        name = o.text("name").orEmpty().take(MAX_TRACKER_TEXT_CHARS),
        mcpUrl = o.text("mcpUrl").orEmpty().take(MAX_TRACKER_URL_CHARS),
        // A key that is present but blank means "no authentication"; a missing key is the default header.
        authHeaderName = if ("authHeaderName" in o) o.text("authHeaderName").orEmpty().take(MAX_TRACKER_TEXT_CHARS) else DEFAULT_TRACKER_AUTH_HEADER,
        bearerPrefix = o.flag("bearerPrefix", true),
        prompt = o.text("prompt").orEmpty().take(MAX_TRACKER_PROMPT_CHARS),
        agentProfileId = o.text("agentProfileId")?.takeIf { it.isNotBlank() },
    )
}

internal fun testingSettingsJson(testing: TestingSettings): JsonObject = buildJsonObject {
    testing.defaultJudgeProfileId?.let { put("defaultJudgeProfileId", it) }
    put("defaultJudgeMode", testing.defaultJudgeMode)
    put(
        "evidence",
        buildJsonObject {
            put("video", testing.evidence.video)
            put("screenshots", testing.evidence.screenshots)
            put("logcat", testing.evidence.logcat)
            put("transcript", testing.evidence.transcript)
        },
    )
    put("confirmationTimeoutMinutes", testing.confirmationTimeoutMinutes)
}

internal fun testingSettingsFromJson(element: JsonElement?): TestingSettings {
    val o = element.asObject() ?: return TestingSettings()
    val defaults = EvidenceFlags()
    val evidence = o["evidence"].asObject()
    return TestingSettings(
        defaultJudgeProfileId = o.text("defaultJudgeProfileId")?.takeIf { it.isNotBlank() },
        defaultJudgeMode = (JudgeMode.parse(o.text("defaultJudgeMode")) ?: JudgeMode.OFF).wire,
        evidence = EvidenceFlags(
            video = evidence?.flag("video", defaults.video) ?: defaults.video,
            screenshots = evidence?.flag("screenshots", defaults.screenshots) ?: defaults.screenshots,
            logcat = evidence?.flag("logcat", defaults.logcat) ?: defaults.logcat,
            transcript = evidence?.flag("transcript", defaults.transcript) ?: defaults.transcript,
        ),
        confirmationTimeoutMinutes = ((o["confirmationTimeoutMinutes"] as? JsonPrimitive)?.intOrNull ?: TestingSettings().confirmationTimeoutMinutes)
            .coerceIn(MIN_CONFIRMATION_TIMEOUT_MINUTES, MAX_CONFIRMATION_TIMEOUT_MINUTES),
    )
}

private const val MAX_TRACKER_URL_CHARS = 2_000
