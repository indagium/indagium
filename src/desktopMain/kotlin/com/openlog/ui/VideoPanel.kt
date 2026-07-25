@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.openlog.model.LogTab
import com.openlog.model.VideoAttachment
import com.openlog.model.VideoSource
import com.openlog.video.VideoPlayerController
import com.openlog.video.formatVideoTime
import com.openlog.video.formatVideoTimeShort
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

// Preset playback-rate choices. VideoPlayerController.setRate accepts any Float in
// [0.1, 8] but the transport bar only ever offers a fixed, familiar set — matching ListStepper's
// own "small fixed choice set, not a free-form input" convention elsewhere in this codebase.
private val VIDEO_RATE_PRESETS = listOf(0.5f, 1f, 1.5f, 2f)

private fun pathFileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

private fun videoDisplayName(attachment: VideoAttachment): String = when (val source = attachment.source) {
    is VideoSource.LocalFile -> pathFileName(source.path)
    is VideoSource.ArchiveEntry -> pathFileName(source.displayName)
}

private fun videoFullPath(attachment: VideoAttachment): String = when (val source = attachment.source) {
    is VideoSource.LocalFile -> source.path
    is VideoSource.ArchiveEntry -> "${source.archivePath}/${source.entryPath}"
}

/**
 * Persists the same orientation that the user sees in the player. The player produces PNG bytes,
 * so applying the transform here keeps the decoder API independent of presentation preferences.
 */
private fun rotatedFramePng(sourceBytes: ByteArray, rotationDegrees: Int): ByteArray? {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation == 0) return sourceBytes
    val source = runCatching { ImageIO.read(ByteArrayInputStream(sourceBytes)) }.getOrNull() ?: return null
    val swapsDimensions = rotation == 90 || rotation == 270
    val output = BufferedImage(
        if (swapsDimensions) source.height else source.width,
        if (swapsDimensions) source.width else source.height,
        BufferedImage.TYPE_INT_ARGB,
    )
    val graphics = output.createGraphics()
    try {
        graphics.rotate(Math.toRadians(rotation.toDouble()))
        when (rotation) {
            90 -> graphics.translate(0, -source.height)
            180 -> graphics.translate(-source.width, -source.height)
            270 -> graphics.translate(-source.width, 0)
        }
        graphics.drawImage(source, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return runCatching {
        ByteArrayOutputStream().use { outputStream ->
            if (ImageIO.write(output, "png", outputStream)) outputStream.toByteArray() else null
        }
    }.getOrNull()
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun VideoHeader(
    attachment: VideoAttachment,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = tc()
    val fullPath = videoFullPath(attachment)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier.background(colors.p2, CORNER_SM)
                            .border(0.5.dp, colors.br, CORNER_SM)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(fullPath, color = colors.tx, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                },
            ) {
                AppText(
                    "Video: ${videoDisplayName(attachment)}",
                    color = colors.td,
                    fontSize = 10.sp,
                    fontFamily = UI,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun VideoRemoveAction(state: AppState, tab: LogTab) {
    var confirmationOpen by remember(tab.id) { mutableStateOf(false) }
    ToolbarBtn(
        label = "Remove video",
        icon = Icons.Outlined.DeleteOutline,
        showLabel = false,
        tooltip = "Remove video",
        onClick = { confirmationOpen = true },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
    )
    if (confirmationOpen) {
        Dialog(onDismissRequest = { confirmationOpen = false }) {
            val colors = tc()
            Column(
                Modifier.width(400.dp).background(colors.p, CORNER_MD)
                    .border(1.dp, colors.br, CORNER_MD)
                    .padding(20.dp),
            ) {
                AppText("Remove video?", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                AppText(
                    "This detaches the video from this log and stops playback. Your log and notes remain unchanged.",
                    color = colors.td,
                    fontSize = 11.sp,
                    maxLines = 3,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogActionButton("Remove video", active = true, danger = true) {
                        confirmationOpen = false
                        state.removeVideo(tab.id)
                    }
                    DialogActionButton("Cancel", active = false) { confirmationOpen = false }
                }
            }
        }
    }
}

/**
 * Wires the video player into the right sidebar. The controller lives in [AppState], so the
 * sidebar and detached window are two views of the very same playback session rather than two
 * competing decoders.
 */
@Composable
internal fun BoundVideoPanel(
    state: AppState,
    tab: LogTab,
    modifier: Modifier = Modifier,
) {
    val attachment = tab.attachedVideo ?: return
    if (!state.videoPanelVisible) return
    val controller = state.videoController(tab.id) ?: return
    var detached by remember(tab.id) { mutableStateOf(false) }
    val onSidebarVisibleChange = LocalVideoSidebarExpandedChange.current

    // A detached player deliberately does not retain a blank sidebar slot. Returning from it
    // restores the embedded player without recreating its controller.
    LaunchedEffect(detached) { onSidebarVisibleChange(!detached) }

    if (!detached) {
        VideoPanel(
            state = state,
            tab = tab,
            attachment = attachment,
            controller = controller,
            onDetach = { detached = true },
            modifier = modifier,
        )
    }
    if (detached) {
        VideoPlayerWindow(
            state = state,
            tab = tab,
            attachment = attachment,
            controller = controller,
            onReturnToSidebar = { detached = false },
        )
    }
}

@Composable
internal fun VideoPanel(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    onDetach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    // Session-only, per-tab: VideoPlayerController exposes no rate GETTER (setRate is fire-and-
    // forget — see its own doc comment on the wall-clock-fallback limitation), so this is purely
    // which preset pill reads as "active." Revisiting a tab after switching away resets the shown
    // selection to 1x even if the controller's actual rate is still whatever was last set — a minor
    // cosmetic gap, not a functional one (the controller keeps playing at its real rate regardless).
    var selectedRate by remember(tab.id) { mutableStateOf(1f) }

    Column(modifier.fillMaxSize().background(tc.p)) {
        VideoHeader(
            attachment = attachment,
            trailing = {
                VideoRemoveAction(state = state, tab = tab)
                ToolbarBtn(
                    label = "Maximize video",
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    showLabel = false,
                    tooltip = "Maximize video",
                    onClick = onDetach,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
                )
            },
        )
        VideoPlayerContents(
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
private fun VideoPlayerWindow(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    onReturnToSidebar: () -> Unit,
) {
    val tc = tc()
    var selectedRate by remember(tab.id) { mutableStateOf(1f) }
    Window(
        onCloseRequest = onReturnToSidebar,
        title = "Video — ${tab.filename}",
        state = rememberWindowState(size = DpSize(820.dp, 600.dp)),
        resizable = true,
    ) {
        Column(Modifier.fillMaxSize().background(tc.p)) {
            VideoHeader(
                attachment = attachment,
                trailing = {
                    VideoRemoveAction(state = state, tab = tab)
                    ToolbarBtn(
                        label = "Return video to sidebar",
                        icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                        showLabel = false,
                        tooltip = "Return video to sidebar",
                        onClick = onReturnToSidebar,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
                    )
                },
            )
            VideoPlayerContents(
                state = state,
                tab = tab,
                attachment = attachment,
                controller = controller,
                selectedRate = selectedRate,
                onRateSelected = { rate -> selectedRate = rate; controller.setRate(rate) },
            )
        }
    }
}

@Composable
private fun ColumnScope.VideoPlayerContents(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
) {
    val followLogs = state.isVideoFollowLogEnabled(tab.id)

    // Reading controller.positionMs here (during composition) makes VideoPlayerContents recompose on
    // every decode-thread position update, so the mapping below is always current. A snapshotFlow on
    // the same state did NOT reliably re-fire on those background-thread writes, which froze Follow
    // on one row while the video kept playing.
    //
    // Resolved exactly ONCE per recomposition and threaded down to the transport bar: the readout
    // and the "Logs" button used to each re-resolve it, and every resolve was an O(n) pass over the
    // whole log, so a 300k-row tab spent ~60ms of the UI thread per decoded frame — Follow, log
    // scrolling and playback all stalled together. AppState now caches its indexes per tab, but one
    // shared mapping is still the right shape: the readout and the selection can't disagree.
    val mapping = state.videoFollowMapping(tab.id, controller.positionMs)
    val followTarget = mapping.mappedNearestLogId.takeIf { followLogs }

    // `selectionKey` is what lets Follow re-assert itself. Keyed on followTarget alone, Follow went
    // permanently silent after any manual click: with filters active the target holds for as long as
    // the gap to the next *visible* row (often minutes, sometimes the rest of the file), so the key
    // never changed and the user's click stuck while the video played on. While playback is running,
    // a selection change re-fires this and Follow takes back over; while paused it does not, so a
    // manual click is still the one-off look-around it should be. navigateToVideoLog's own
    // already-selected dedupe (forceRecenter = false) keeps this from spamming scroll requests.
    val selectionKey = tab.selected.takeIf { controller.isPlaying }
    LaunchedEffect(followLogs, followTarget, selectionKey) {
        if (followLogs && followTarget != null) state.navigateToVideoLog(tab.id, followTarget)
    }

    VideoFrameArea(
        controller = controller,
        rotationDegrees = state.videoRotationDegrees(tab.id),
        modifier = Modifier.fillMaxWidth().weight(1f),
    )
    VideoTransportBar(
        state = state,
        tab = tab,
        attachment = attachment,
        controller = controller,
        mapping = mapping,
        selectedRate = selectedRate,
        onRateSelected = onRateSelected,
        followLogs = followLogs,
        onFollowLogsChange = { state.setVideoFollowLog(tab.id, it) },
        onRotateClockwise = { state.rotateVideoClockwise(tab.id) },
    )
}

@Composable
private fun VideoFrameArea(
    controller: VideoPlayerController,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
) {
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
            frame != null && rotationDegrees % 180 == 0 -> Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().rotate(rotationDegrees.toFloat()),
                contentScale = ContentScale.Fit,
            )
            frame != null -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // A 90° rotation swaps the frame's measured width and height. Measuring the
                // image with the parent's constraints would crop it after rotation; swapping
                // them first makes the rotated bounds fit the available frame area.
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    modifier = Modifier.width(maxHeight).height(maxWidth).rotate(rotationDegrees.toFloat()),
                    contentScale = ContentScale.Fit,
                )
            }
            else -> AppText("Opening video…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VideoTransportBar(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    mapping: VideoFollowMapping,
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
    followLogs: Boolean,
    onFollowLogsChange: (Boolean) -> Unit,
    onRotateClockwise: () -> Unit,
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
    val sliderColors = SliderDefaults.colors(thumbColor = tc.ac, activeTrackColor = tc.ac, inactiveTrackColor = tc.br)
    val sliderInteractionSource = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Elapsed/duration at the edges, transport controls centered between them — all in the
        // same strip above the timeline, so play/volume/overflow read as belonging to the clock
        // they control rather than to the anchor row below the slider.
        Box(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 2.dp)) {
            AppText(
                formatVideoTimeShort(sliderValueMs),
                color = tc.td,
                fontSize = 9.sp,
                fontFamily = MONO,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VolumeControl(controller = controller)
                ToolbarBtn(
                    if (controller.isPlaying) "Pause" else "Play",
                    icon = if (controller.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    showLabel = false,
                    tooltip = if (controller.isPlaying) "Pause" else "Play",
                    enabled = playable,
                    onClick = { if (controller.isPlaying) controller.pause() else controller.play() },
                )
                VideoOverflowMenu(
                    selectedRate = selectedRate,
                    onRateSelected = onRateSelected,
                    onRotateClockwise = onRotateClockwise,
                )
            }
            AppText(
                formatVideoTimeShort(controller.durationMs),
                color = tc.td,
                fontSize = 9.sp,
                fontFamily = MONO,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        // Material's normal slider reserves a 48dp touch target. This desktop transport uses the
        // same 28dp control height as the adjacent play and rate controls while retaining a
        // high-contrast 14dp thumb and a 4dp track for accurate mouse seeking.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
            Slider(
                value = sliderValueMs.toFloat().coerceIn(0f, durationF),
                onValueChange = { dragPositionMs = it.toLong() },
                onValueChangeFinished = {
                    dragPositionMs?.let { controller.seek(it) }
                    dragPositionMs = null
                },
                valueRange = 0f..durationF,
                enabled = playable,
                colors = sliderColors,
                interactionSource = sliderInteractionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = sliderInteractionSource,
                        colors = sliderColors,
                        enabled = playable,
                        thumbSize = DpSize(14.dp, 14.dp),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(4.dp),
                        enabled = playable,
                        colors = sliderColors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 2.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 2.dp),
            )
        }
        VideoAnchorRow(
            state = state,
            tab = tab,
            attachment = attachment,
            controller = controller,
            mapping = mapping,
            followLogs = followLogs,
            onFollowLogsChange = onFollowLogsChange,
        )
        if (state.settings.showVideoFollowReadout) {
            VideoFollowReadout(state = state, tab = tab, mapping = mapping, positionMs = controller.positionMs)
        }
    }
}

/**
 * Spells out what Follow is actually doing at the current playhead position. Follow can only ever
 * select a row the filter leaves visible, so in a filtered view it legitimately holds on one line
 * for as long as the gap to the next visible one — which reads as a silent freeze without this
 * line. HIDDEN_BY_FILTER and HIDDEN_BY_COLLAPSE are the cases that matter most: the log line
 * matching this video moment exists but isn't independently visible, so the selection is
 * deliberately behind the video — for two DIFFERENT reasons the readout must not conflate (see
 * AppState.fullFloorHiddenReason): the filter actually excludes it, or it passes the filter but
 * sits folded inside a collapsed sequence/manual/stack-trace group. Every branch that resolves to a
 * held row also names the computed target time (`mappedElapsedMs`, formatted back to a clock string)
 * ahead of it, so "how far behind is Follow actually holding" is visible at a glance instead of
 * requiring the reader to do timestamp arithmetic themselves — this is the single most useful number
 * for telling a genuinely-filtered/-folded hold apart from Follow being stuck on stale data.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun VideoFollowReadout(state: AppState, tab: LogTab, mapping: VideoFollowMapping, positionMs: Long) {
    val tc = tc()
    val posLabel = formatVideoTime(positionMs)
    val targetClock = mapping.mappedElapsedMs?.let { com.openlog.utils.formatElapsedAsClock(it) }
    val videoArrow = if (targetClock != null) "$posLabel → $targetClock" else posLabel
    val target = "log ${mapping.mappedNearestLogTs} · #${mapping.mappedNearestLogId}"
    val line = when (mapping.status) {
        FollowMappingStatus.NO_ANCHOR -> "Link a log line to a video moment to enable follow"
        FollowMappingStatus.BEFORE_FIRST -> "video $videoArrow → before first log line"
        FollowMappingStatus.AFTER_LAST -> "video $videoArrow → past last log line"
        FollowMappingStatus.NO_VISIBLE_ROW -> "video $videoArrow → no matching log line is visible (filtered out)"
        FollowMappingStatus.HIDDEN_BY_FILTER -> "video $videoArrow → holding at $target (matching line hidden by filter)"
        FollowMappingStatus.HIDDEN_BY_COLLAPSE -> "video $videoArrow → holding at $target (matching line folded inside a collapsed group)"
        FollowMappingStatus.ON_VISIBLE_ROW -> "video $videoArrow → $target"
    }
    // pointerInput below is keyed on tab.id alone (so right-clicking doesn't restart a coroutine on
    // every decoded frame) — which means its awaitPointerEventScope loop, once launched, keeps
    // running the SAME suspend closure across recompositions rather than getting a fresh one each
    // time. A closure over the plain `positionMs` parameter would then freeze at whatever position
    // was current when that closure was first launched, silently copying a stale diagnostic dump on
    // every later right-click. rememberUpdatedState is the standard fix: the pointerInput block
    // reads `.value` (via the property delegate) at click time, not at launch time.
    val latestPositionMs by rememberUpdatedState(positionMs)
    // Transient "Copied" confirmation for the right-click diagnostic-copy action below — same
    // COPIED_FEEDBACK_MS/auto-reset shape as McpInfoDialog's copy buttons, just rendered inline
    // since this line has no room for a second control next to it.
    var justCopied by remember(tab.id) { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPIED_FEEDBACK_MS)
            justCopied = false
        }
    }
    val readoutText: @Composable () -> Unit = {
        AppText(
            if (justCopied) "Follow diagnostics copied to clipboard" else line,
            color = if (justCopied) tc.ac else tc.td,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                // Right-click (not left) so the readout's normal left-click-to-select-nothing
                // behavior elsewhere in the app is undisturbed — this is a deliberate, secondary
                // "debug dump" action, not the line's primary purpose. No visible affordance beyond
                // the tooltip below: the readout is already the smallest sensible surface for this
                // (see the task's own framing) rather than adding a whole new button/menu.
                .pointerInput(tab.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                change.consume()
                                state.copyToClipboard(formatFollowDiagnostics(state.followDiagnostics(tab.id, latestPositionMs)))
                                justCopied = true
                            }
                        }
                    }
                },
        )
    }
    // Confirms what the anchor itself links, without spending a second line of vertical space on
    // it — the main readout above already carries the live mapping. Also documents the right-click
    // diagnostic-copy action, since nothing else on this line hints it exists.
    TooltipArea(
        tooltip = {
            Box(
                Modifier.background(tc.p2, CORNER_SM)
                    .border(0.5.dp, tc.br, CORNER_SM)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column {
                    if (mapping.anchorLogTs != null && mapping.anchorVideoMs != null) {
                        AppText(
                            "⚓ log ${mapping.anchorLogTs} = video ${formatVideoTime(mapping.anchorVideoMs)}",
                            color = tc.tx,
                            fontSize = 11.sp,
                        )
                    }
                    AppText("Right-click: copy Follow diagnostics to clipboard", color = tc.tx, fontSize = 11.sp)
                }
            }
        },
    ) { readoutText() }
}

/** A compact, single segmented control for the fixed playback-speed choices. */
@Composable
private fun PlaybackRateStepper(selectedRate: Float, onRateSelected: (Float) -> Unit) {
    val tc = tc()
    val index = VIDEO_RATE_PRESETS.indexOf(selectedRate).coerceAtLeast(0)
    Row(
        Modifier.border(0.5.dp, tc.br, CORNER_MD).clip(CORNER_MD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = index > 0) { onRateSelected(VIDEO_RATE_PRESETS[index - 1]) }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        Box(Modifier.width(38.dp).height(28.dp), contentAlignment = Alignment.Center) {
            AppText(rateLabel(selectedRate), color = tc.tx, fontSize = 10.sp, fontFamily = MONO, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        StepperButton("+", enabled = index < VIDEO_RATE_PRESETS.lastIndex) { onRateSelected(VIDEO_RATE_PRESETS[index + 1]) }
    }
}

private fun rateLabel(rate: Float): String {
    val whole = rate.toLong()
    return if (rate == whole.toFloat()) "${whole}x" else "${rate}x"
}

// The popover's vertical slider is a horizontal Slider rotated -90°. VOLUME_TRACK_LENGTH is its
// pre-rotation WIDTH (i.e. its visual length once rotated) and is the single source of truth for
// that dimension — the container height below is derived from it arithmetically rather than the
// two being sized independently, which is what previously let them drift apart: either the slider
// was hardcoded wider than its container (clipped into a misshapen blob), or the container was
// enlarged without the slider following (a short track floating in a mostly-empty box).
private val VOLUME_TRACK_LENGTH = 130.dp
private val VOLUME_POPOVER_VERTICAL_PADDING = 14.dp
private val VOLUME_POPOVER_HEIGHT = VOLUME_TRACK_LENGTH + VOLUME_POPOVER_VERTICAL_PADDING * 2
private val VOLUME_POPOVER_WIDTH = 28.dp
private val VOLUME_TRACK_WIDTH = 4.dp
private val VOLUME_THUMB_SIZE = 12.dp

/**
 * A plain vertical track/fill/thumb drawn directly with Box layers and a raw pointer loop — no
 * Material3 Slider, no rotation. [trackLength] is the ONLY thing that determines how long this
 * renders; there's no second, independently-sized element it can drift out of sync with.
 */
@Composable
private fun VerticalVolumeBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackLength: Dp,
    activeColor: Color,
    inactiveColor: Color,
    thumbColor: Color,
) {
    val clamped = value.coerceIn(0f, 1f)
    var trackHeightPx by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .width(VOLUME_POPOVER_WIDTH)
            .height(trackLength)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val isDrag = event.type == PointerEventType.Press || event.type == PointerEventType.Move
                        if (isDrag && change.pressed && trackHeightPx > 0f) {
                            change.consume()
                            // Top of the track is max, bottom is min (0), matching a physical
                            // volume-fader convention: push up to raise it.
                            val y = change.position.y.coerceIn(0f, trackHeightPx)
                            onValueChange(1f - (y / trackHeightPx))
                        }
                    }
                }
            },
    ) {
        Box(
            Modifier.align(Alignment.Center).width(VOLUME_TRACK_WIDTH).fillMaxHeight()
                .background(inactiveColor, RoundedCornerShape(VOLUME_TRACK_WIDTH / 2)),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).width(VOLUME_TRACK_WIDTH).fillMaxHeight(clamped)
                .background(activeColor, RoundedCornerShape(VOLUME_TRACK_WIDTH / 2)),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = trackLength * (1f - clamped) - VOLUME_THUMB_SIZE / 2)
                .size(VOLUME_THUMB_SIZE)
                .background(thumbColor, CircleShape),
        )
    }
}

// Vertical clearance between the popover's bottom edge and the trigger button's top edge. Without
// this the popover (aligned TopCenter, i.e. popup-top = trigger-top by default) only clears the
// trigger by exactly its own height, landing flush against — and in practice slightly over — the
// button, which then swallowed clicks meant for it (see dismissOnClickOutside below).
private val VOLUME_POPOVER_GAP = 6.dp

/**
 * Speaker button that toggles mute on click and reveals a vertical volume slider on hover — the
 * slider stays hidden the rest of the time so it doesn't compete with play/overflow for space in
 * the centered transport cluster. Hover tracking (trigger + popup, with a grace-period close)
 * mirrors CtxHighlightAction's swatch popover in Components.kt.
 */
@Composable
private fun VolumeControl(controller: VideoPlayerController) {
    val tc = tc()
    val density = LocalDensity.current
    var hoveringTrigger by remember { mutableStateOf(false) }
    var hoveringPopup by remember { mutableStateOf(false) }
    var popupOpen by remember { mutableStateOf(false) }
    LaunchedEffect(hoveringTrigger, hoveringPopup) {
        if (hoveringTrigger || hoveringPopup) {
            popupOpen = true
        } else if (popupOpen) {
            delay(CTX_SUBMENU_CLOSE_DELAY_MS)
            popupOpen = false
        }
    }
    val muted = controller.isMuted || controller.volume <= 0f
    val icon = when {
        muted -> Icons.AutoMirrored.Outlined.VolumeOff
        controller.volume < 0.5f -> Icons.AutoMirrored.Outlined.VolumeDown
        else -> Icons.AutoMirrored.Outlined.VolumeUp
    }
    Box {
        ToolbarBtn(
            if (muted) "Unmute" else "Mute",
            icon = icon,
            showLabel = false,
            tooltip = if (muted) "Unmute" else "Mute",
            onClick = { controller.setMuted(!controller.isMuted) },
            modifier = Modifier
                .onPointerEvent(PointerEventType.Enter) { hoveringTrigger = true }
                .onPointerEvent(PointerEventType.Exit) { hoveringTrigger = false },
        )
        if (popupOpen) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -with(density) { (VOLUME_POPOVER_HEIGHT + VOLUME_POPOVER_GAP).roundToPx() }),
                onDismissRequest = { popupOpen = false },
                // Entirely hover-driven (see LaunchedEffect above), so it must NOT dismiss on an
                // outside click: with dismissOnClickOutside's default of true, a click on the mute
                // button itself counts as "outside" the popup and got consumed by the dismiss
                // handler before the button's own onClick ever ran — unmuting by click silently did
                // nothing.
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Box(
                    Modifier.width(VOLUME_POPOVER_WIDTH).height(VOLUME_POPOVER_HEIGHT)
                        .shadow(8.dp, CORNER_MD)
                        .background(tc.p, CORNER_MD)
                        .border(0.5.dp, tc.br, CORNER_MD)
                        .onPointerEvent(PointerEventType.Enter) { hoveringPopup = true }
                        .onPointerEvent(PointerEventType.Exit) { hoveringPopup = false },
                    contentAlignment = Alignment.Center,
                ) {
                    VerticalVolumeBar(
                        value = if (muted) 0f else controller.volume,
                        onValueChange = { v ->
                            controller.setVolume(v)
                            if (controller.isMuted && v > 0f) controller.setMuted(false)
                        },
                        trackLength = VOLUME_TRACK_LENGTH,
                        activeColor = tc.ac,
                        inactiveColor = tc.ts.copy(alpha = 0.4f),
                        thumbColor = tc.ac,
                    )
                }
            }
        }
    }
}

// Fixed popup width. Without an explicit width here, the "Rotate clockwise" row's
// Modifier.fillMaxWidth() had nothing to bound itself against inside a Popup (which otherwise
// hands its content effectively the whole window as its measuring constraints) and stretched the
// menu across the full app width instead of wrapping its two short rows.
private val VIDEO_OVERFLOW_MENU_WIDTH = 172.dp
private val VIDEO_OVERFLOW_MENU_GAP = 6.dp

/**
 * Overflow menu for the two transport actions that don't need to be one click away: rotate and
 * playback speed. A click-to-open popup (not hover, unlike [VolumeControl]) since both actions
 * inside benefit from staying open across repeated clicks (e.g. stepping through speed presets).
 */
@Composable
private fun VideoOverflowMenu(
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
    onRotateClockwise: () -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    // Popup's default dismissOnClickOutside fires for ANY click outside its content — including a
    // second click on the trigger button itself, since the trigger isn't part of the popup's own
    // content bounds. That dismiss and the trigger's own onClick both fired for that same click, so
    // "close, then immediately reopen" is what a second press looked like. Recording when a dismiss
    // last happened and ignoring an onClick that follows within the same click lets the trigger
    // toggle normally instead of racing its own dismiss handler.
    var lastDismissedAtMs by remember { mutableStateOf(0L) }
    // Measured (not guessed) trigger height, so the popup's offset clears the actual button
    // instead of a hardcoded estimate that could under-shoot for a different font/density.
    var triggerHeightPx by remember { mutableStateOf(0) }
    Box(Modifier.onGloballyPositioned { triggerHeightPx = it.size.height }) {
        ToolbarBtn(
            "More options",
            icon = Icons.Outlined.MoreVert,
            showLabel = false,
            tooltip = "Rotate, playback speed",
            active = menuOpen,
            onClick = {
                val now = System.currentTimeMillis()
                if (now - lastDismissedAtMs > CTX_SUBMENU_CLOSE_DELAY_MS) menuOpen = !menuOpen
            },
        )
        if (menuOpen) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, triggerHeightPx + with(density) { VIDEO_OVERFLOW_MENU_GAP.roundToPx() }),
                onDismissRequest = {
                    menuOpen = false
                    lastDismissedAtMs = System.currentTimeMillis()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(VIDEO_OVERFLOW_MENU_WIDTH)
                        .shadow(8.dp, RoundedCornerShape(7.dp))
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HoverBox(
                        // Stays open on click — rotating is usually a "tap a few times to get to
                        // 90/180/270" action, so closing the menu on the first tap would force
                        // reopening it for every subsequent turn.
                        modifier = Modifier.fillMaxWidth().clip(CORNER_MD),
                        onClick = onRotateClockwise,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null, tint = tc.tx, modifier = Modifier.size(16.dp))
                            AppText("Rotate clockwise", color = tc.tx, fontSize = 12.sp)
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppText("Playback speed", color = tc.td, fontSize = 10.sp)
                        PlaybackRateStepper(selectedRate = selectedRate, onRateSelected = onRateSelected)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun VideoAnchorRow(
    state: AppState,
    tab: LogTab,
    attachment: VideoAttachment,
    controller: VideoPlayerController,
    mapping: VideoFollowMapping,
    followLogs: Boolean,
    onFollowLogsChange: (Boolean) -> Unit,
) {
    val tc = tc()
    val anchor = attachment.anchor
    val rotationDegrees = state.videoRotationDegrees(tab.id)
    // "Show in logs" jumps to the visible log row Follow itself would select at this playhead
    // position — null (and the button disabled) when there's no anchor yet, the anchor/target rows'
    // `ts` doesn't parse (LogTime.parseMillisOfDay's TS_UNKNOWN case — brief/RAW-format rows), or the
    // current filter hides every timestamped row. Taking it from the SAME mapping the readout and the
    // automatic follow effect use keeps "enabled", "what the readout says" and "where the click
    // lands" in sync — resolving them separately is how a filtered view could show an enabled button
    // whose click target isn't actually visible.
    val targetLogId = mapping.mappedNearestLogId?.takeIf { state.isVideoPositionValid(tab, controller.positionMs) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppButton(
            "Logs",
            onClick = { targetLogId?.let { state.navigateToVideoLog(tab.id, it, forceRecenter = true) } },
            variant = ButtonVariant.Secondary,
            enabled = targetLogId != null,
            leadingIcon = Icons.Outlined.MyLocation,
            horizontalPadding = 6.dp,
        )
        if (anchor != null) {
            // What "Link to current video position" silently captured, shown on hover rather than
            // as a permanent line — the anchored log line's own timestamp alongside it when
            // available (ts is empty for RAW/unparsed rows).
            val anchorLogTs = tab.rmap[anchor.logId]?.ts
            val anchorSummary = if (!anchorLogTs.isNullOrEmpty()) {
                "⚓ $anchorLogTs = ${formatVideoTime(anchor.videoMs)}"
            } else {
                "⚓ ${formatVideoTime(anchor.videoMs)}"
            }
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier.background(tc.p2, CORNER_SM)
                            .border(0.5.dp, tc.br, CORNER_SM)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(anchorSummary, color = tc.tx, fontSize = 11.sp)
                    }
                },
            ) {
                AppButton(
                    "Clear",
                    onClick = { state.clearVideoAnchor(tab.id) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = Icons.Outlined.LinkOff,
                    horizontalPadding = 6.dp,
                )
            }
        }
        AppButton(
            if (followLogs) "Following" else "Follow",
            onClick = { onFollowLogsChange(!followLogs) },
            variant = if (followLogs) ButtonVariant.Primary else ButtonVariant.Secondary,
            enabled = targetLogId != null,
            leadingIcon = Icons.Outlined.MyLocation,
            horizontalPadding = 6.dp,
        )
        ToolbarBtn(
            label = "Add frame to notes",
            icon = Icons.Outlined.AddAPhoto,
            showLabel = false,
            tooltip = "Add frame to notes",
            enabled = controller.currentFrame != null,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            onClick = {
                // Capture all context before encoding; playback can advance while ImageIO works.
                val capturedPositionMs = controller.positionMs
                controller.grabCurrentFrame()?.let { bytes ->
                    rotatedFramePng(bytes, rotationDegrees)?.let { orientedBytes ->
                        state.addVideoFrameNote(
                            tabId = tab.id,
                            sourceBytes = orientedBytes,
                            source = attachment.source,
                            sourceLabel = attachment.sourceLabel,
                            positionMs = capturedPositionMs,
                        )
                    }
                    if (!state.annotationVisible) state.updateAnnotationVisible(true)
                }
            },
        )
    }
}
