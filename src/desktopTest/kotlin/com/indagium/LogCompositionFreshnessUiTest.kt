package com.indagium

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.indagium.model.Filter
import com.indagium.ui.LogCompositionFreshnessEffect
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class LogCompositionFreshnessUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun expandedCompositionEffectRescansWhenRestoredRowsAdvanceTheRevision() {
        val refreshCount = AtomicInteger()
        rule.setContent {
            var expanded by remember { mutableStateOf(false) }
            var revision by remember { mutableStateOf(0L) }
            var filter by remember { mutableStateOf(Filter()) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                // Model the restored-shell lifecycle: the panel is open before its file finishes
                // loading, then rows and stack-trace analysis each advance the session revision.
                expanded = true
                kotlinx.coroutines.delay(500)
                revision += 1
                kotlinx.coroutines.delay(500)
                revision += 1
                filter = filter.copy(pidTidFilter = "123")
            }
            LogCompositionFreshnessEffect(
                expanded = expanded,
                tabId = "restored-tab",
                filter = filter,
                revision = revision,
                onRefresh = { refreshCount.incrementAndGet() },
            )
        }

        rule.waitUntil(5_000) { refreshCount.get() >= 3 }
        rule.runOnIdle { assertEquals(3, refreshCount.get()) }
    }
}
