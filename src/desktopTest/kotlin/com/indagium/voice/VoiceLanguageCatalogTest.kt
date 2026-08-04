package com.indagium.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceLanguageCatalogTest {
    @Test
    fun defaultsAreAlwaysAvailableAndKnownAddedLanguagesAreKept() {
        assertEquals(
            listOf("auto", "uk", "en", "ru", "pl"),
            VoiceLanguageCatalog.normalize(listOf("ru", "pl", "unknown", "uk")),
        )
    }
}
