package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.LogTab
import com.indagium.utils.TagProcessInfo
import com.indagium.utils.canFollowTagByToken
import com.indagium.utils.defaultTagProcessSelection
import com.indagium.utils.tagProcessInfo
import com.indagium.utils.tagProcessRulePattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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

/**
 * The state of one "follow the process" popover opened from a tag row's pid button, shared by the
 * Filters panel's tag dropdown and the filter bar's tag dropdown. [tag] is the tag it is open for
 * (null = closed); [infos] is the scan result (null while scanning); the pid checkboxes, the
 * keep-following choice and the keyboard [cursor] live here so [TagProcessPopover] stays stateless.
 *
 * The owner assigns [refocus] (hands keyboard focus back to its tag field: the popup's clickable pid
 * button steals it, and the field's key handler is what drives the popover) and [commitRule] (turns
 * the built pattern into the PID_TID message rule) on every composition.
 */
internal class TagPidPopoverState {
    var tag by mutableStateOf<String?>(null)
        private set
    var infos by mutableStateOf<List<TagProcessInfo>?>(null)
        private set
    var selected by mutableStateOf<Set<Int>>(emptySet())
        private set
    var keepFollowing by mutableStateOf(true)
    var cursor by mutableStateOf(-1)
        private set

    // The tag row the pointer is on: its pid button is only drawn for the hovered row.
    var hoveredTag by mutableStateOf<String?>(null)

    var refocus: () -> Unit = {}
    var commitRule: (String) -> Unit = {}

    // Dismissing the popup by clicking its own pid button would otherwise be followed by that same
    // click re-opening it (the IssueCategoryDropdown race), so toggles are ignored just after a dismiss.
    private var toggleSuppressedUntilMs = 0L

    val isOpen: Boolean get() = tag != null

    /** Every close path (Cancel, outside click, Esc, after Add) hands focus back to the tag field. */
    fun close() {
        tag = null
        infos = null
        runCatching { refocus() }
    }

    /** Shared by the pid button's click and Enter on a row whose keyboard action is the pid button. */
    fun open(tag: String) {
        infos = null
        cursor = -1
        this.tag = tag
        runCatching { refocus() }
    }

    /** The popup was dismissed by an outside click; swallows an immediately following toggle click. */
    fun dismissByOutsideClick(nowMs: Long = System.currentTimeMillis()) {
        toggleSuppressedUntilMs = nowMs + TOGGLE_SUPPRESS_MS
        close()
    }

    /** The pid button was clicked: opens the popover, or closes it if open for [forTag]. */
    fun toggleFor(forTag: String, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs < toggleSuppressedUntilMs) return
        if (tag == forTag) close() else open(forTag)
    }

    fun togglePid(pid: Int) {
        selected = if (pid in selected) selected - pid else selected + pid
    }

    /** Closes the popover when its anchor tag is no longer among the dropdown's tag rows. */
    fun closeIfMissing(candidates: List<Pair<String, Boolean>>) {
        val open = tag ?: return
        if (candidates.none { (value, isPkg) -> !isPkg && value == open }) close()
    }

    /** Applies a scan result: the first one seeds the checkboxes, later ones (live tail) keep them. */
    fun onScanResult(result: List<TagProcessInfo>) {
        val seed = infos == null
        infos = result
        if (seed) {
            selected = defaultTagProcessSelection(result)
            keepFollowing = true
            cursor = 0
        } else {
            cursor = clampPopoverCursor(cursor, popoverItemCount(result))
        }
    }

    /** Builds the PID_TID rule for the open tag, hands it to [commitRule] and closes. */
    fun add() {
        val open = tag ?: return
        val list = infos.orEmpty()
        val pattern = tagProcessRulePattern(open, selected, list.mapTo(HashSet()) { it.pid }, keepFollowing)
        commitRule(pattern)
        close()
    }

    /**
     * Keys while the popover is open (↑↓ move, Space toggle, Enter add, Esc close). Returns whether
     * the key was consumed; false when closed. [onTab] runs after closing on Tab: Tab leaves the
     * field as usual, and an open popover with focus elsewhere would no longer receive its keys.
     */
    fun handleKey(key: Key, onTab: () -> Unit): Boolean {
        val openTag = tag ?: return false
        val list = infos
        val itemCount = popoverItemCount(list)
        when (key) {
            Key.DirectionDown -> cursor = movePopoverCursor(cursor, +1, itemCount)
            Key.DirectionUp -> cursor = movePopoverCursor(cursor, -1, itemCount)
            Key.Spacebar -> {
                val item = list?.getOrNull(cursor)
                if (item != null) {
                    togglePid(item.pid)
                } else if (list != null && cursor == list.size && canKeepFollowing(openTag, list, selected)) {
                    keepFollowing = !keepFollowing
                }
            }
            Key.Enter, Key.NumPadEnter -> if (canAddTagProcess(list, selected)) add()
            Key.Escape -> close()
            Key.Tab -> { close(); onTab() }
            Key.DirectionLeft, Key.DirectionRight -> Unit
            else -> return false
        }
        return true
    }

    private companion object {
        const val TOGGLE_SUPPRESS_MS = 200L
    }
}

/**
 * Scans the open tag's processes. Full scan, not the large-file cap: the line counts in the popover
 * must be right. Re-runs when logData changes (a live tail), keeping the user's checkboxes.
 */
@Composable
internal fun TagPidPopoverScanEffect(state: TagPidPopoverState, tab: LogTab) {
    LaunchedEffect(state.tag, tab.logData) {
        val scanTag = state.tag ?: return@LaunchedEffect
        val data = tab.logData
        val names = tab.analysis.processNames
        val result = withContext(Dispatchers.Default) { tagProcessInfo(data, scanTag, names) { ensureActive() } }
        state.onScanResult(result)
    }
}

/** The small "pid" box on a tag row of a tag dropdown (matches the +/− boxes' footprint). */
@Composable
internal fun TagPidButton(open: Boolean, kbd: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tc = tc()
    Box(
        modifier.size(20.dp)
            .background(if (open) tc.ac.copy(.2f) else if (kbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
            .border(1.dp, if (open || kbd) tc.ac else tc.br, CORNER_SM)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText("pid", color = if (open || kbd) tc.ac else tc.ts, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The fixed 20dp pid slot of a tag row: the pid button (when [hasPids] and the row is hovered,
 * keyboard-selected or its popover is open) plus the popover itself. Always 20dp wide so showing or
 * hiding the button never shifts the row.
 */
@Composable
internal fun TagPidSlot(
    tag: String,
    state: TagPidPopoverState,
    hasPids: Boolean,
    rowSelected: Boolean,
    kbd: Boolean,
    positionProvider: PopupPositionProvider,
    buttonModifier: Modifier = Modifier,
) {
    val open = state.tag == tag
    Box(Modifier.size(20.dp)) {
        if (hasPids && (state.hoveredTag == tag || rowSelected || open)) {
            TagPidButton(open = open, kbd = kbd, modifier = buttonModifier) { state.toggleFor(tag) }
        }
        if (open) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { state.dismissByOutsideClick() },
                properties = PopupProperties(focusable = false),
            ) {
                TagProcessPopover(
                    tag = tag,
                    infos = state.infos,
                    selected = state.selected,
                    keepFollowing = state.keepFollowing,
                    onToggle = { pid -> state.togglePid(pid) },
                    onKeepFollowingChange = { state.keepFollowing = it },
                    onAdd = { state.add() },
                    onCancel = { state.close() },
                    cursor = state.cursor,
                )
            }
        }
    }
}

private const val POPOVER_EDGE_GAP_PX = 6

/**
 * Left x of the popover: just right of [rightEdgePx]; when that would not fit in the window and a
 * [leftEdgePx] is given with room to its left, just left of that edge instead; else clamped into the
 * window. Either way it avoids covering the list it was opened from whenever the window allows.
 */
internal fun tagPopoverX(rightEdgePx: Int, leftEdgePx: Int?, popupWidthPx: Int, windowWidthPx: Int): Int {
    val right = rightEdgePx + POPOVER_EDGE_GAP_PX
    if (right + popupWidthPx > windowWidthPx && leftEdgePx != null) {
        val left = leftEdgePx - POPOVER_EDGE_GAP_PX - popupWidthPx
        if (left >= 0) return left
    }
    return right.coerceAtMost(windowWidthPx - popupWidthPx).coerceAtLeast(0)
}

/**
 * Opens the pid popover just right of the list it was opened from (a panel or a dropdown), so it
 * never covers that list, top-aligned with its anchor row and clamped into the window.
 */
internal class RightOfEdgePositionProvider(
    private val rightEdgePx: Int,
    private val leftEdgePx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = tagPopoverX(rightEdgePx, leftEdgePx, popupContentSize.width, windowSize.width)
        val y = anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
