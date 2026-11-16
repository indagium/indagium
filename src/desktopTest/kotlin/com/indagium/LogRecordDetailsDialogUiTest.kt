package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFormatEngine
import com.indagium.ui.LogRecordDetailsDialog
import com.indagium.utils.parseLogContent
import org.junit.Rule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LogRecordDetailsDialogUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val raw = """{"timestamp":"2024-01-01T00:00:00Z","level":"ERROR","message":"disk full","device":"sda1"}"""

    @Test
    fun theDialogShowsFieldsAndTheRawRecordAndItsButtonsCopyAndClose() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
        val entry = parseLogContent(ByteArrayInputStream((raw + "\n").toByteArray()), profile = profile).entries.single()
        val copied = mutableListOf<String>()
        var dismissed = 0
        var refocused = 0
        rule.setContent {
            LogRecordDetailsDialog(
                entry = entry,
                onCopy = { copied += it },
                onDismiss = { dismissed++ },
                onReclaimFocus = { refocused++ },
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("log-record-details-dialog").assertExists()
        rule.onNodeWithTag("log-record-field-device").assertTextContains("sda1", substring = true)
        rule.onNodeWithTag("log-record-raw").assertTextContains("disk full", substring = true)
        rule.onNodeWithTag("log-record-summary-level").assertTextContains("Error", substring = true)

        rule.onNodeWithTag("log-record-copy-raw").performClick()
        assertEquals(listOf(raw), copied)
        rule.onNodeWithTag("log-record-copy-json").performClick()
        assertTrue(copied.last().contains("\"device\": \"sda1\""), copied.last())
        assertEquals(2, refocused, "each copy returns focus to the log view")

        rule.onNodeWithTag("log-record-close").performClick()
        assertEquals(1, dismissed)
        assertEquals(3, refocused)
    }
}
