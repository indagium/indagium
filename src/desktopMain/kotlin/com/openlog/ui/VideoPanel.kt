package com.openlog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.LogTab
import com.openlog.model.VideoAttachment
import com.openlog.video.VideoPlayerController
import com.openlog.video.formatVideoTime

// Preset playback-rate choices. VideoPlayerController.setRate accepts any Float in
// [0.1, 8] but the transport bar only ever offers a fixed, familiar set — matching ListStepper's
// own "small fixed choice set, not a free-form input" convention elsewhere in this codebase.
private val VIDEO_RATE_PRESETS = listOf(0.5f, 1f, 1.5f, 2f)

/**
 * Wires the video panel into [tab]'s Row of panels (ui/FileView.kt, ui/CompareView.kt — plan doc's
 * Task B) — a leading [HDivider] plus the panel itself, following [BoundFilterPanel]'s own
 * bound-composable pattern. Renders nothing when [tab] has no attached video, or when the panel is
 * toggled off (AppState.videoPanelVisible, mirroring filterVisible/annotationVisible).
 */
@Composable
internal fun BoundVideoPanel(state: AppState, tab: LogTab) {
    val attachment = tab.attachedVideo ?: return
    if (!state.videoPanelVisible) return
    // Guaranteed non-null here: videoController(tabId) only returns null when the tab has no
    // attachedVideo, which was just checked above.
    val controller = state.videoController(tab.id) ?: return
    HDivider { delta -> state.updateVideoPanelWidth(state.videoPanelWidth - delta) }
    VideoPanel(state = state, tab = tab, attachment = attachment, controller = controller, width = state.videoPanelWidth)
}

@Composable
internal fun VideoPanel(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    width: Float,
) {
    val tc = tc()
    // Session-only, per-tab: VideoPlayerController exposes no rate GETTER (setRate is fire-and-
    // forget — see its own doc comment on the wall-clock-fallback limitation), so this is purely
    // which preset pill reads as "active." Revisiting a tab after switching away resets the shown
    // selection to 1x even if the controller's actual rate is still whatever was last set — a minor
    // cosmetic gap, not a functional one (the controller keeps playing at its real rate regardless).
    var selectedRate by remember(tab.id) { mutableStateOf(1f) }

    Column(Modifier.width(width.dp).fillMaxHeight().background(tc.p)) {
        SectionHeader(title = "Video")
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
            AppText(
                attachment.sourceLabel, color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
            )
        }
        VideoFrameArea(controller = controller, modifier = Modifier.fillMaxWidth().weight(1f))
        VideoTransportBar(
            state = state,
            tab = tab,
            attachment = attachment,
            controller = controller,
            selectedRate = selectedRate,
            onRateSelected = { rate -> selectedRate = rate; controller.setRate(rate) },
        )
    }
}

@Composable
private fun VideoFrameArea(controller: VideoPlayerController, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val err = controller.error
        val frame = controller.currentFrame
        when {
            err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = DANGER_RED, modifier = Modifier.size(22.dp))
                AppText("Couldn't play this video", color = Color.White, fontSize = 12.sp)
                AppText(
                    err, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp, start = 10.dp, end = 10.dp),
                )
            }
            frame != null -> Image(
                bitmap = frame, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            else -> AppText("Opening video…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun VideoTransportBar(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
) {
    val tc = tc()
    val playable = controller.error == null
    // While the thumb is being dragged, the slider shows this LOCAL value instead of
    // controller.positionMs — the real seek only happens once, on release (onValueChangeFinished),
    // rather than on every intermediate drag frame. Binding the slider straight to positionMs
    // during a drag would fight the decode thread's own (asynchronous, one-loop-iteration-later)
    // position updates and make the thumb visibly stutter/snap back mid-drag.
    var dragPositionMs by remember(tab.id) { mutableStateOf<Long?>(null) }
    val durationF = controller.durationMs.coerceAtLeast(1L).toFloat()
    val sliderValueMs = dragPositionMs ?: controller.positionMs
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = sliderValueMs.toFloat().coerceIn(0f, durationF),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let { controller.seek(it) }
                dragPositionMs = null
            },
            valueRange = 0f..durationF,
            enabled = playable,
            colors = SliderDefaults.colors(thumbColor = tc.ac, activeTrackColor = tc.ac, inactiveTrackColor = tc.br),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppText(formatVideoTime(sliderValueMs), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
            AppText(formatVideoTime(controller.durationMs), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolbarBtn(
                if (controller.isPlaying) "Pause" else "Play",
                icon = if (controller.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                showLabel = false,
                tooltip = if (controller.isPlaying) "Pause" else "Play",
                enabled = playable,
                onClick = { if (controller.isPlaying) controller.pause() else controller.play() },
            )
            VIDEO_RATE_PRESETS.forEach { rate ->
                PillBtn(rateLabel(rate), active = rate == selectedRate) { onRateSelected(rate) }
            }
        }
        VideoAnchorRow(state = state, tab = tab, attachment = attachment, controller = controller)
    }
}

private fun rateLabel(rate: Float): String {
    val whole = rate.toLong()
    return if (rate == whole.toFloat()) "${whole}x" else "${rate}x"
}

@Composable
private fun VideoAnchorRow(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
) {
    val tc = tc()
    val anchor = attachment.anchor
    // "Show in logs" jumps to whatever log row currently maps closest to the playhead — null (and
    // the button disabled) when there's no anchor yet, or the anchor/target rows' `ts` doesn't
    // parse (LogTime.parseMillisOfDay's TS_UNKNOWN case — brief/RAW-format rows).
    val targetLogId = if (anchor != null) state.videoMsToNearestLogId(tab, controller.positionMs) else null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(
            if (anchor != null) {
                "Anchored to line #${anchor.logId} @ ${formatVideoTime(anchor.videoMs)}"
            } else {
                "No anchor — right-click a log row to link it here"
            },
            color = tc.td, fontSize = 10.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(
                "Show in logs",
                onClick = { targetLogId?.let { state.requestVideoLogNavigation(tab.id, it) } },
                variant = ButtonVariant.Secondary,
                enabled = targetLogId != null,
                leadingIcon = Icons.Outlined.MyLocation,
            )
            if (anchor != null) {
                AppButton(
                    "Clear anchor",
                    onClick = { state.clearVideoAnchor(tab.id) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = Icons.Outlined.LinkOff,
                )
            }
        }
        AppButton(
            "Add frame to notes",
            onClick = {
                controller.grabCurrentFrame()?.let { bytes ->
                    state.addImageBlock(tab.id, bytes, provenance = "from ${attachment.sourceLabel}")
                    if (!state.annotationVisible) state.updateAnnotationVisible(true)
                }
            },
            variant = ButtonVariant.Secondary,
            enabled = controller.currentFrame != null,
            leadingIcon = Icons.Outlined.AddAPhoto,
        )
    }
}
