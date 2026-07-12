package com.openlog.ai

import com.openlog.model.AiProviderProfile
import com.openlog.model.defaultAiProviderProfile
import com.openlog.ui.AppState
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiProviderProfileTest {
    @Test
    fun endpointValidationAllowsLoopbackAndProtectsRemoteProfiles() {
        listOf(
            "http://localhost:1234/v1",
            "http://127.0.0.1:1234/v1",
            "http://[::1]:1234/v1",
            "http://[0:0:0:0:0:0:0:1]:1234/v1",
        ).forEach { endpoint ->
            assertTrue(validateAiProviderProfile(defaultAiProviderProfile().copy(baseUrl = endpoint)).isValid)
        }

        assertEquals(AiProviderUrlProblem.MALFORMED, validateAiProviderProfile(defaultAiProviderProfile().copy(baseUrl = "not a url")).problem)
        assertEquals(
            AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED,
            validateAiProviderProfile(defaultAiProviderProfile().copy(baseUrl = "https://models.example/v1")).problem,
        )
        // Remote HTTP (e.g. an LM Studio instance reached over the LAN, which never serves TLS)
        // is allowed once the disclosure is acknowledged, same as remote HTTPS.
        assertEquals(
            AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED,
            validateAiProviderProfile(defaultAiProviderProfile().copy(baseUrl = "http://models.example/v1")).problem,
        )
        assertTrue(
            validateAiProviderProfile(
                defaultAiProviderProfile().copy(
                    baseUrl = "https://models.example/v1",
                    remoteDisclosureAcknowledged = true,
                ),
            ).isValid,
        )
        assertTrue(
            validateAiProviderProfile(
                defaultAiProviderProfile().copy(
                    baseUrl = "http://models.example/v1",
                    remoteDisclosureAcknowledged = true,
                ),
            ).isValid,
        )
    }

    @Test
    fun pathlessBaseUrlIsTreatedAsItsV1BaseButExplicitPathsAreLeftAlone() {
        // LM Studio's UI shows its "Reachable at" address as a bare http://host:port; treat that
        // as shorthand for the /v1 base this app actually speaks.
        assertEquals("http://192.168.0.189:1234/v1", aiProviderRequestBaseUrl("http://192.168.0.189:1234"))
        assertEquals("http://192.168.0.189:1234/v1", aiProviderRequestBaseUrl("http://192.168.0.189:1234/"))
        // An endpoint the user typed with an explicit path is never rewritten.
        assertEquals("http://192.168.0.189:1234/v1", aiProviderRequestBaseUrl("http://192.168.0.189:1234/v1"))
        assertEquals("https://models.example/proxy/openai", aiProviderRequestBaseUrl("https://models.example/proxy/openai"))
    }

    @Test
    fun profilesRoundTripButSessionKeyNeverEntersAutosave() {
        val cacheFile = File(createTempDirectory("openlog-ai-profiles").toFile(), "state.cache")
        val state = AppState(cacheFile)
        val remote = AiProviderProfile(
            id = "remote",
            displayName = "Team endpoint",
            baseUrl = "https://models.example/v1",
            model = "team-model",
            selected = true,
            remoteDisclosureAcknowledged = true,
        )
        state.updateSettings { it.copy(aiProviderProfiles = listOf(defaultAiProviderProfile().copy(selected = false), remote)) }
        state.setAiProviderApiKey(remote.id, "sk-test-must-never-persist")
        state.autosaveNow()

        val serialized = cacheFile.readText()
        assertFalse(serialized.contains("sk-test-must-never-persist"))
        assertFalse(serialized.contains("apiKey"))
        assertFalse(serialized.contains("AiSession"))
        state.close()
        assertEquals("", state.aiProviderApiKey(remote.id))

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(listOf("lm-studio", "remote"), restored.settings.aiProviderProfiles.map { it.id })
        assertEquals("remote", restored.settings.aiProviderProfiles.single { it.selected }.id)
        assertEquals("team-model", restored.settings.aiProviderProfiles.single { it.id == "remote" }.model)
        assertEquals("", restored.aiProviderApiKey("remote"))
        restored.close()
    }

    @Test
    fun changingRemoteEndpointClearsAcknowledgementUntilItIsConfirmedAgain() {
        val state = AppState()
        val original = AiProviderProfile(
            id = "remote",
            displayName = "Team endpoint",
            baseUrl = "https://first.example/v1",
            model = "team-model",
            selected = true,
            remoteDisclosureAcknowledged = true,
        )
        state.updateSettings { it.copy(aiProviderProfiles = listOf(original)) }

        val changed = original.copy(
            baseUrl = "https://second.example/v1",
            remoteDisclosureAcknowledged = true,
        )
        assertEquals(
            AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED.message,
            state.updateAiProviderProfile(changed),
        )
        val pending = state.settings.aiProviderProfiles.single()
        assertEquals("https://second.example/v1", pending.baseUrl)
        assertFalse(pending.remoteDisclosureAcknowledged)

        assertEquals(null, state.updateAiProviderProfile(pending.copy(remoteDisclosureAcknowledged = true)))
        assertTrue(state.settings.aiProviderProfiles.single().remoteDisclosureAcknowledged)
        state.close()
    }

    @Test
    fun oldSettingsTokenGetsDefaultLmStudioProfile() {
        val cacheFile = File(createTempDirectory("openlog-ai-legacy").toFile(), "state.cache")
        val legacySettings = tokenFields(
            "LIGHT", "12", "true", "", "5", "5", "8", "true", "true", "INDENTED", "false", "From",
            "5", "480", "true", "false", "8991", "false", "java", "j*ava", "false", "KEYWORD_REGEX", "false", "", "",
        )
        cacheFile.writeText("openLog2-cache-v1\nsettings\t${legacySettings.b64()}\ntabs\n")

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(listOf(defaultAiProviderProfile()), restored.settings.aiProviderProfiles)
        restored.close()
    }

    private fun tokenFields(vararg values: String): String = values.joinToString("|") { if (it.isEmpty()) "~" else it.b64() }

    private fun String.b64(): String = Base64.getEncoder().encodeToString(toByteArray())
}
