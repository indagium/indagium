package com.indagium.ui

import com.indagium.model.AppSettings
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

// The three AI test folders of AppSettings (Settings → General), written into the keyed settings JSON (settingsJson in
// AutosaveCodec.kt) only when the user chose one; absent means "the default under the Default save folder". JSON form
// ONLY: the frozen positional settings decoder never sees them.

internal fun JsonObjectBuilder.putTestFolders(settings: AppSettings) {
    settings.testSuitesDir?.let { put("testSuitesDir", it) }
    settings.testRunsDir?.let { put("testRunsDir", it) }
    settings.testIssuesDir?.let { put("testIssuesDir", it) }
}
