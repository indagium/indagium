package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.utils.TagProcessInfo
import com.indagium.utils.canFollowTagByToken
import androidx.compose.ui.semantics.selected as cursorSelected

// Rows show two lines (pid + name, then the time span), about this tall; the list scrolls past
// POPOVER_ROW_LIMIT of them.
private const val POPOVER_ROW_DP = 40
private const val POPOVER_ROW_LIMIT = 6

// The tag row's keyboard action cursor (FilterPanel's tagSelectedAction): 0 = include, 1 = exclude,
// and -1 = the pid button, which only rows that have a pid popover can reach.
internal const val TAG_ACTION_PID = -1
private const val TAG_ACTION_EXCLUDE = 1

/** ←/→ on a tag row: [delta] is -1 or +1; pid is only reachable when [pidAvailable]. */
internal fun nextTagRowAction(current: Int, delta: Int, pidAvailable: Boolean): Int =
    clampTagRowAction(current + delta, pidAvailable)

/** Keeps an action valid for the row it now sits on (a row without pids can't hold [TAG_ACTION_PID]). */
internal fun clampTagRowAction(action: Int, pidAvailable: Boolean): Int =
    action.coerceIn(if (pidAvailable) TAG_ACTION_PID else 0, TAG_ACTION_EXCLUDE)

/** The popover's keyboard items: one per pid row plus the final "Keep following" row. */
internal fun popoverItemCount(infos: List<TagProcessInfo>?): Int = (infos?.size ?: 0) + 1

/** ↑/↓ in the popover: clamps at the ends, never wraps; a cursor of -1 (none) lands on the first item. */
internal fun movePopoverCursor(cursor: Int, delta: Int, itemCount: Int): Int =
    if (itemCount <= 0) -1 else if (cursor < 0) 0 else (cursor + delta).coerceIn(0, itemCount - 1)

/** Keeps the cursor on an existing item after the row count changed (a tail re-scan). */
internal fun clampPopoverCursor(cursor: Int, itemCount: Int): Int =
    if (itemCount <= 0) -1 else cursor.coerceIn(0, itemCount - 1)

/** Whether the "Keep following" checkbox is operable: every listed pid checked and the tag tokenable. */
internal fun canKeepFollowing(tag: String, infos: List<TagProcessInfo>?, selected: Set<Int>): Boolean =
    canFollowTagByToken(tag) && !infos.isNullOrEmpty() && infos.all { it.pid in selected }

/** Whether "Add pid filter" is operable: the scan finished and at least one pid is checked. */
internal fun canAddTagProcess(infos: List<TagProcessInfo>?, selected: Set<Int>): Boolean =
    infos != null && selected.isNotEmpty()

/**
 * The "follow the process of a tag" popover opened from a tag row's pid button in FilterPanel.
 * Stateless so it can be tested on its own: the caller owns [selected] and [keepFollowing], loads
 * [infos] (null while the scan runs) and turns [onAdd] into the PID_TID rule.
 *
 * "Keep following" (the dynamic `tag:<Tag>` rule) only makes sense when every listed pid is
 * checked and the tag can be written as a token ([canFollowTagByToken]); otherwise the checkbox
 * is disabled and shown unchecked, and a hint says why.
 */
@Composable
fun TagProcessPopover(
    tag: String,
    infos: List<TagProcessInfo>?,
    selected: Set<Int>,
    keepFollowing: Boolean,
    onToggle: (Int) -> Unit,
    onKeepFollowingChange: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Keyboard cursor over [infos] plus the final "Keep following" row (-1 = none). The popover is
    // not focusable, so the caller (the tag field's key handler) moves it.
    cursor: Int = -1,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val tokenable = canFollowTagByToken(tag)
    val keepEnabled = canKeepFollowing(tag, infos, selected)
    val keepCursor = cursor >= 0 && cursor == (infos?.size ?: 0)
    Column(
        modifier.width(400.dp)
            .shadow(8.dp, shape)
            .background(tc.p, shape)
            .border(1.dp, tc.br, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("Follow the process of", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    tag,
                    color = tc.ac,
                    fontSize = 12.sp,
                    fontFamily = MONO,
                    fontWeight = FontWeight.SemiBold,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            AppText("Shows every line from that process, all tags included.", color = tc.td, fontSize = 10.sp)
        }
        when {
            infos == null -> AppText("Scanning…", color = tc.td, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
            infos.isEmpty() -> AppText("No process logged this tag.", color = tc.td, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
            else -> ScrollableItems(
                itemCount = infos.size,
                rowDp = POPOVER_ROW_DP,
                maxDp = POPOVER_ROW_LIMIT * POPOVER_ROW_DP,
                scrollToIndex = cursor,
            ) {
                infos.forEachIndexed { idx, info ->
                    ProcessRow(info, info.pid in selected, onCursor = idx == cursor, onToggle = { onToggle(info.pid) })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .testTag("tag-process-keep-row")
                .semantics { cursorSelected = keepCursor }
                .background(if (keepCursor) tc.abg else Color.Transparent)
                .clickable(enabled = keepEnabled) { onKeepFollowingChange(!keepFollowing) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactCheckBox(
                checked = keepFollowing && keepEnabled,
                onToggle = { onKeepFollowingChange(!keepFollowing) },
                enabled = keepEnabled,
                modifier = Modifier.testTag("tag-process-keep-following"),
            )
            Column {
                AppText("Keep following if the app restarts (new pid)", color = if (keepEnabled) tc.tx else tc.td, fontSize = 11.sp)
                if (!keepEnabled && infos != null) {
                    AppText(
                        if (tokenable) "Following restarts needs all pids checked" else "Can't follow restarts for this tag name",
                        color = tc.td,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            AppButton("Cancel", onClick = onCancel, variant = ButtonVariant.Secondary)
            Spacer(Modifier.width(8.dp))
            AppButton(
                "Add pid filter",
                onClick = onAdd,
                variant = ButtonVariant.Primary,
                enabled = canAddTagProcess(infos, selected),
                modifier = Modifier.testTag("tag-process-add"),
            )
        }
        AppText("↑↓ move · Space toggle · Enter add · Esc close", color = tc.td, fontSize = 9.sp)
    }
}

@Composable
private fun ProcessRow(info: TagProcessInfo, checked: Boolean, onCursor: Boolean, onToggle: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().height(POPOVER_ROW_DP.dp)
            .testTag("tag-process-item-${info.pid}")
            .semantics { cursorSelected = onCursor },
        baseBg = if (onCursor) tc.abg else Color.Transparent,
        onClick = onToggle,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactCheckBox(checked = checked, onToggle = onToggle, modifier = Modifier.testTag("tag-process-row-${info.pid}"))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(info.pid.toString(), color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    AppText(
                        info.name ?: "unknown process",
                        color = if (info.name != null) tc.ts else tc.td,
                        fontSize = 11.sp,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val tags = if (info.distinctTags == 1) "1 tag" else "${info.distinctTags} tags"
                AppText("${info.firstTs} – ${info.lastTs} · $tags", color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
            }
            AppText("${info.totalLines} lines", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
        }
    }
}
