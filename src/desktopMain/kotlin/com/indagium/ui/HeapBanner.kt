package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapSnapshot
import java.util.Locale

private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_GB = 1024L * BYTES_PER_MB

/** `12` for 12.0, `9.1` for 9.1: one decimal, dropped when it is zero. */
private fun gbNumber(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes.toDouble() / BYTES_PER_GB).removeSuffix(".0")

private fun mbNumber(bytes: Long): String = (bytes / BYTES_PER_MB).toString()

/**
 * "9.1 of 12 GB" (GB with one decimal, trailing ".0" dropped). Below 1 GB the figures are whole MB:
 * "512 of 768 MB"; a sub-GB reading against a GB-sized heap keeps each unit: "512 MB of 12 GB".
 */
internal fun heapUsageLabel(snapshot: HeapSnapshot): String {
    val used = snapshot.usedAfterGcBytes
    val max = snapshot.maxBytes
    return when {
        max >= BYTES_PER_GB && used >= BYTES_PER_GB -> "${gbNumber(used)} of ${gbNumber(max)} GB"
        max >= BYTES_PER_GB -> "${mbNumber(used)} MB of ${gbNumber(max)} GB"
        else -> "${mbNumber(used)} of ${mbNumber(max)} MB"
    }
}

/**
 * Banner text for [level], or null at NORMAL. Falls back to a figure-less sentence without a [snapshot].
 * [captureLogPaused] (some live capture tab's log view is paused, see TailCoordinator.pauseTailing) adds
 * the sentence saying so at CRITICAL; without a paused tab the banner makes no such claim.
 */
internal fun heapBannerText(level: HeapPressure, snapshot: HeapSnapshot?, captureLogPaused: Boolean = false): String? {
    // The figure is the whole heap after GC, not just log rows — hence "Indagium uses", not "logs use".
    val usage = snapshot?.let { "Indagium uses ${heapUsageLabel(it)}" } ?: "Indagium is using most of its available memory"
    return when (level) {
        HeapPressure.NORMAL -> null
        HeapPressure.WARNING -> "Memory is running low — $usage. Close tabs you don't need."
        HeapPressure.CRITICAL -> "Memory is almost full — $usage. Close tabs you don't need." +
            if (captureLogPaused) " Live capture log view is paused; recording continues." else ""
    }
}

/** The banner shows from WARNING up, unless the user dismissed this level (or a higher one). */
internal fun heapBannerVisible(level: HeapPressure, dismissedLevel: HeapPressure?): Boolean =
    level >= HeapPressure.WARNING && (dismissedLevel == null || level > dismissedLevel)

/**
 * Dismissal bookkeeping for a level change: cleared at NORMAL, and lowered with the level so a later
 * rise (WARNING dismissed, then CRITICAL) shows the banner again.
 */
internal fun nextDismissedHeapLevel(level: HeapPressure, dismissedLevel: HeapPressure?): HeapPressure? = when {
    level == HeapPressure.NORMAL -> null
    dismissedLevel != null && dismissedLevel > level -> level
    else -> dismissedLevel
}

/**
 * Slim, non-blocking memory banner placed directly under the tab bar. One line, ellipsised on narrow
 * windows; dismissible for the current level only. [onReclaimFocus] gives keyboard focus back to the
 * root key handler, because the clickable "×" takes it (see CLAUDE.md, Compose Desktop gotchas).
 */
@Composable
internal fun HeapPressureBanner(state: AppState, onReclaimFocus: () -> Unit) {
    val level = state.heapPressure
    var dismissed by remember { mutableStateOf<HeapPressure?>(null) }
    LaunchedEffect(level) { dismissed = nextDismissedHeapLevel(level, dismissed) }
    if (!heapBannerVisible(level, dismissed)) return
    // Read here (not in a LaunchedEffect) so the sentence tracks the tab list: the tab copy made by
    // pauseTailing/resumeTailing is a snapshot-state write the banner recomposes from.
    val captureLogPaused = state.tabs.any { it.tailPausedAtRow != null && it.captureSessionId != null }
    val text = heapBannerText(level, state.heapSnapshot, captureLogPaused) ?: return
    val colors = tc()
    val accent = if (level == HeapPressure.CRITICAL) DANGER_RED else colors.warn
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = .12f))
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(
            text,
            color = accent,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(20.dp)
                .clip(CORNER_SM)
                .clickable {
                    dismissed = level
                    onReclaimFocus()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss memory warning",
                tint = accent,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
