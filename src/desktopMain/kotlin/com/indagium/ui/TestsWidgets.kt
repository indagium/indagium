@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// Shared pieces of the Tests workspace: the per-workspace context (TestsUi), inline-error text fields that commit
// through the AppState delegates, a dropdown, hinted buttons and the confirm dialog. Everything takes its colours
// from themeColors() (via tc()); nothing here hard-codes a hex value.

internal const val TESTS_COMMIT_DEBOUNCE_MS = 400L
private val FIELD_FONT_SIZE = 12.sp
private val SMALL_FONT_SIZE = 11.sp
private val MENU_MAX_HEIGHT = 360.dp
private val MENU_SHAPE = RoundedCornerShape(8.dp)
private val MULTILINE_MIN_HEIGHT = 56.dp
private val MULTILINE_MAX_HEIGHT = 160.dp
private val CONFIRM_DIALOG_WIDTH = 440.dp
private val LOCK_ICON_SIZE = 14.dp
private const val BANNER_MAX_LINES = 4
private val DIALOG_SHAPE = RoundedCornerShape(8.dp)
private const val MENU_REOPEN_SUPPRESS_MS = 200L
private val SCROLLBAR_WIDTH = 6.dp
private val LIST_ROW_MIN_HEIGHT = 34.dp
private val MENU_WIDTH = 230.dp
private val MENU_ITEM_SHAPE = RoundedCornerShape(5.dp)
private val OVERFLOW_BUTTON_SIZE = 26.dp
private val SPLIT_CHEVRON_WIDTH = 24.dp
private val SPLIT_START_SHAPE = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
private val SPLIT_END_SHAPE = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
private val CHIP_SHAPE = RoundedCornerShape(6.dp)
private val SUMMARY_CHIP_MAX_WIDTH = 280.dp
private val DIALOG_MAX_HEIGHT = 720.dp
private val TITLE_FONT_SIZE = 14.sp

/** A line of feedback under the workspace header: an error, or an informational note such as an import warning. */
internal class TestsBanner(val message: String, val isError: Boolean)

/**
 * What every screen of the Tests workspace shares: the [AppState] (whose delegates return StoreResult), the
 * selection state, the workspace's root focus requester and the banner. Provided through [LocalTestsUi].
 */
@Stable
internal class TestsUi(
    val state: AppState,
    val view: TestsViewState,
    /** The workspace root's requester: reclaim it after a popup/dialog closes or the focused row disappears. */
    val rootFocus: FocusRequester,
    val scope: CoroutineScope,
) {
    var banner: TestsBanner? by mutableStateOf(null)

    val library: TestLibrary get() = state.testLibrary

    /** Gives keyboard focus back to the workspace root (a dismissed Popup / clicked clickable otherwise keeps it). */
    fun reclaimFocus() {
        runCatching { rootFocus.requestFocus() }
    }

    /** Shows the outcome of a store call in the banner (errors, then warnings) and returns it unchanged. */
    fun <T> report(result: StoreResult<T>): StoreResult<T> {
        val message = result.userMessage()
        val warnings = result.userWarnings()
        banner = when {
            message != null -> TestsBanner(message, isError = true)
            warnings.isNotEmpty() -> TestsBanner(warnings.joinToString(" "), isError = false)
            else -> null
        }
        return result
    }

    fun info(message: String) {
        banner = TestsBanner(message, isError = false)
    }
}

internal val LocalTestsUi = staticCompositionLocalOf<TestsUi> { error("TestsUi is only available inside the Tests workspace") }
internal val LocalTestsLimits = staticCompositionLocalOf { TestsLimitsUiState("", true, emptySet(), emptySet(), emptySet(), emptySet(), emptySet()) }

// ── Text ─────────────────────────────────────────────────────────────

@Composable
internal fun TestsSectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val tc = tc()
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText(text, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
internal fun TestsFieldLabel(text: String, modifier: Modifier = Modifier) {
    AppText(text, color = tc().ts, fontSize = SMALL_FONT_SIZE, modifier = modifier.padding(bottom = 3.dp))
}

@Composable
internal fun TestsHint(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    AppText(text, color = tc().td, fontSize = SMALL_FONT_SIZE, maxLines = maxLines, modifier = modifier)
}

@Composable
internal fun TestsErrorText(text: String, modifier: Modifier = Modifier) {
    AppText(text, color = DANGER_RED, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES, modifier = modifier.padding(top = 2.dp))
}

/** A label above a field, both stretched to the full width. */
@Composable
internal fun TestsLabeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        TestsFieldLabel(label)
        content()
    }
}

// ── Scrolling ────────────────────────────────────────────────────────

/**
 * A vertically scrolling column with a visible scrollbar (a bare `verticalScroll` gives no hint that there is more).
 * [contentModifier] is applied INSIDE the scroll (padding that should scroll with the content). The scrollbar sits in a
 * matchParentSize() box, like [BoundedScrollBoxDp], so it never takes part in sizing: a `heightIn(max = …)` or
 * `weight(1f, fill = false)` passed as [modifier] still lets a short content hug its own height.
 */
@Composable
internal fun TestsScrollColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tc = tc()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState).then(contentModifier), content = content)
        Box(Modifier.matchParentSize()) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(SCROLLBAR_WIDTH),
                style = appScrollbarStyle(tc),
            )
        }
    }
}

// ── Rows ─────────────────────────────────────────────────────────────

/**
 * A selectable list row: [HoverBox]-based, tinted with the accent background while [selected], at least [minHeight] tall.
 * The click has no indication (the hover and selected backgrounds are the feedback), so rows do not flash a ripple.
 */
@Composable
internal fun TestsListRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = LIST_ROW_MIN_HEIGHT,
    content: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        baseBg = if (selected) tc.abg else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// ── Fields that commit through the store ─────────────────────────────

/**
 * A text field that edits a stored value. The text is a local draft; it is committed through [onCommit] on Enter
 * (single-line), when the field loses focus, or [TESTS_COMMIT_DEBOUNCE_MS] after the last keystroke. A failed commit
 * (StoreResult not Ok) keeps the draft and shows the message under the field; Esc discards the draft. While the
 * field has focus the stored value never overwrites what the user is typing.
 */
@Composable
internal fun CommitTextField(
    value: String,
    onCommit: (String) -> StoreResult<*>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    multiline: Boolean = false,
    mono: Boolean = false,
    minHeight: Dp = MULTILINE_MIN_HEIGHT,
    maxHeight: Dp = MULTILINE_MAX_HEIGHT,
    /** Single-line only: styled as a screen title (14sp SemiBold, border only on hover/focus), see [TestsTitleField]. */
    title: Boolean = false,
) {
    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val storedValue by rememberUpdatedState(value)
    val commitAction by rememberUpdatedState(onCommit)
    val editable by rememberUpdatedState(enabled)

    fun commit() {
        if (!editable || draft == storedValue) return
        error = commitAction(draft).userMessage()
    }
    LaunchedEffect(value) {
        if (!focused) {
            draft = value
            error = null
        }
    }
    LaunchedEffect(draft) {
        if (draft != storedValue && editable) {
            delay(TESTS_COMMIT_DEBOUNCE_MS)
            commit()
        }
    }
    val focusTracker = Modifier.onFocusChanged {
        if (focused && !it.hasFocus) commit()
        focused = it.hasFocus
    }
    val font = if (mono) MONO else LocalUiFontFamily.current
    CompositionLocalProvider(LocalUiFontFamily provides font) {
        Column(modifier.fillMaxWidth()) {
            when {
                multiline -> ScrollableTextArea(
                    value = draft,
                    onValue = { draft = it },
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxWidth().then(focusTracker),
                    fontSize = FIELD_FONT_SIZE,
                    minHeight = minHeight,
                    maxHeight = maxHeight,
                    borderColor = if (error != null) DANGER_RED else null,
                    enabled = enabled,
                )
                enabled && title -> TitleInput(
                    value = draft,
                    onValue = { draft = it },
                    placeholder = placeholder,
                    focused = focused,
                    modifier = Modifier.fillMaxWidth().then(focusTracker),
                    onSubmit = ::commit,
                    onCancel = {
                        draft = storedValue
                        error = null
                    },
                )
                enabled -> InlineField(
                    value = draft,
                    onValue = { draft = it },
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxWidth().then(focusTracker),
                    fontSize = FIELD_FONT_SIZE,
                    onSubmit = ::commit,
                    onCancel = {
                        draft = storedValue
                        error = null
                    },
                )
                else -> ReadOnlyValue(draft, placeholder)
            }
            error?.let { TestsErrorText(it) }
        }
    }
}

/** The text input behind [TestsTitleField]: no border at rest, the neutral border on hover, the accent one with focus. */
@Composable
private fun TitleInput(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    focused: Boolean,
    modifier: Modifier,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> tc.ac
        hovered -> tc.br
        else -> Color.Transparent
    }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = tc.tx, fontSize = TITLE_FONT_SIZE, fontWeight = FontWeight.SemiBold, fontFamily = LocalUiFontFamily.current),
        cursorBrush = SolidColor(tc.ac),
        modifier = modifier
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                        onCancel()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                        onSubmit()
                        true
                    }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(if (focused) tc.p else Color.Transparent, CORNER_SM)
            .border(1.dp, borderColor, CORNER_SM)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = TITLE_FONT_SIZE, fontWeight = FontWeight.SemiBold)
            inner()
        },
    )
}

@Composable
private fun ReadOnlyValue(text: String, placeholder: String) {
    val tc = tc()
    Box(Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM).padding(horizontal = 7.dp, vertical = 4.dp)) {
        AppText(text.ifEmpty { placeholder }, color = if (text.isEmpty()) tc.td else tc.ts, fontSize = FIELD_FONT_SIZE, maxLines = Int.MAX_VALUE)
    }
}

/** A whole-number field (timeouts, retries, caps) built on [CommitTextField]: anything outside [min]..[max] is rejected inline. */
@Composable
internal fun CommitNumberField(
    value: Long,
    min: Long,
    max: Long,
    onCommit: (Long) -> StoreResult<*>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CommitTextField(
        value = value.toString(),
        onCommit = { text ->
            val parsed = parseWholeNumber(text, min, max)
            if (parsed == null) StoreResult.Invalid("Enter a whole number between $min and $max.") else onCommit(parsed)
        },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * An editable screen title (suite, case or script name): a [CommitTextField] set as 14sp SemiBold text that only shows
 * a border while hovered or focused. [contentDescription] names it for assistive tech and UI tests.
 */
@Composable
internal fun TestsTitleField(
    value: String,
    onCommit: (String) -> StoreResult<*>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    contentDescription: String? = null,
) {
    CommitTextField(
        value = value,
        onCommit = onCommit,
        modifier = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier,
        enabled = enabled,
        placeholder = placeholder,
        title = true,
    )
}

// ── Page and inspector headers ───────────────────────────────────────

/**
 * The top of a centre screen: an optional [breadcrumb] line, then the title, one [primary] action and a ⋯ menu of
 * [overflowItems]. With [onTitleCommit] the title is an editable [TestsTitleField] (read-only when [titleEditable] is
 * false); without it the title is plain text.
 */
@Composable
internal fun TestsPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onTitleCommit: ((String) -> StoreResult<*>)? = null,
    titleEditable: Boolean = true,
    titleDescription: String? = null,
    titlePlaceholder: String = "",
    primary: (@Composable () -> Unit)? = null,
    overflowItems: List<TestsMenuItem> = emptyList(),
    breadcrumb: (@Composable RowScope.() -> Unit)? = null,
) {
    val tc = tc()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        breadcrumb?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (onTitleCommit != null) {
                    TestsTitleField(title, onTitleCommit, enabled = titleEditable, placeholder = titlePlaceholder, contentDescription = titleDescription)
                } else {
                    AppText(title, color = tc.tx, fontSize = TITLE_FONT_SIZE, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                }
            }
            primary?.invoke()
            if (overflowItems.isNotEmpty()) TestsOverflowMenu(overflowItems)
        }
    }
}

/** The top of an inspector pane: a small muted [eyebrow] ("Step 3"), the [title] below it and an optional ⋯ menu. */
@Composable
internal fun TestsInspectorHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    overflowItems: List<TestsMenuItem> = emptyList(),
) {
    val tc = tc()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            AppText(eyebrow, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            AppText(title, color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (overflowItems.isNotEmpty()) TestsOverflowMenu(overflowItems)
    }
}

// ── Dropdown ─────────────────────────────────────────────────────────

/**
 * A "label ▾" button that opens a menu of [options]. Dismissing the popup hands keyboard focus back to the workspace
 * root (a closed Popup does not), which is what keeps Alt+arrow and Esc working after the first click.
 */
@Composable
internal fun <T> TestsDropdown(
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuWidth: Dp = 220.dp,
    isSelected: (T) -> Boolean = { false },
    emptyText: String = "Nothing to choose from",
) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var suppressUntilMs by remember { mutableStateOf(0L) }

    fun close() {
        open = false
        suppressUntilMs = System.currentTimeMillis() + MENU_REOPEN_SUPPRESS_MS
        ui.reclaimFocus()
    }
    Box(modifier) {
        HoverBox(
            modifier = Modifier.clip(CORNER_MD).background(if (open) tc.p2 else Color.Transparent, CORNER_MD).border(1.dp, tc.br, CORNER_MD),
            hoverEnabled = enabled,
            onClick = if (enabled) ({ if (System.currentTimeMillis() >= suppressUntilMs) open = !open }) else null,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DisableSelection {
                    AppText(selectedLabel, color = if (enabled) tc.tx else tc.ts, fontSize = FIELD_FONT_SIZE, modifier = Modifier.weight(1f, fill = false))
                    AppText("▾", color = tc.td, fontSize = 9.sp)
                }
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = ::close,
                properties = PopupProperties(focusable = false),
            ) {
                TestsScrollColumn(
                    Modifier.width(menuWidth).heightIn(max = MENU_MAX_HEIGHT).shadow(8.dp, MENU_SHAPE).background(tc.p, MENU_SHAPE)
                        .border(1.dp, tc.br, MENU_SHAPE),
                    contentModifier = Modifier.padding(4.dp),
                ) {
                    if (options.isEmpty()) TestsHint(emptyText, Modifier.padding(8.dp))
                    options.forEach { option ->
                        TestsOptionRow(optionLabel(option), isSelected(option)) {
                            onSelect(option)
                            close()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestsOptionRow(label: String, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)), baseBg = if (active) tc.abg else Color.Transparent, onClick = onClick) {
        DisableSelection {
            AppText(
                label,
                color = if (active) tc.ac else tc.tx,
                fontSize = SMALL_FONT_SIZE,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ── Menus ────────────────────────────────────────────────────────────

/**
 * One entry of a [TestsOverflowMenu] / [TestsSplitButton] menu. [kbd] is a shortcut hint shown on the right; a
 * disabled item shows [disabledHint] in a tooltip; [dividerBefore] draws a separator line above it.
 */
internal class TestsMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val kbd: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val disabledHint: String? = null,
    val dividerBefore: Boolean = false,
)

/** Open/closed state of one popup menu, with the same reopen suppression and focus reclaim as [TestsDropdown]. */
@Stable
private class TestsMenuState(private val ui: TestsUi) {
    var open by mutableStateOf(false)
    private var suppressUntilMs = 0L

    /** A click that dismissed the popup also lands on the anchor; ignore it briefly so it does not reopen the menu. */
    fun toggle() {
        if (System.currentTimeMillis() >= suppressUntilMs) open = !open
    }

    fun close() {
        open = false
        suppressUntilMs = System.currentTimeMillis() + MENU_REOPEN_SUPPRESS_MS
        ui.reclaimFocus()
    }
}

@Composable
private fun rememberTestsMenuState(): TestsMenuState {
    val ui = LocalTestsUi.current
    return remember(ui) { TestsMenuState(ui) }
}

/** The popup of an action menu, anchored at [alignment] of its parent and [yOffset] below it. Picking an item closes it. */
@Composable
private fun TestsMenuPopup(items: List<TestsMenuItem>, alignment: Alignment, yOffset: Dp, onClose: () -> Unit) {
    val tc = tc()
    val density = LocalDensity.current
    Popup(
        alignment = alignment,
        offset = IntOffset(0, with(density) { yOffset.roundToPx() }),
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = false),
    ) {
        TestsScrollColumn(
            Modifier.width(MENU_WIDTH).heightIn(max = MENU_MAX_HEIGHT).shadow(8.dp, MENU_SHAPE).background(tc.p, MENU_SHAPE)
                .border(1.dp, tc.br, MENU_SHAPE),
            contentModifier = Modifier.padding(4.dp),
        ) {
            items.forEach { item ->
                if (item.dividerBefore) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 2.dp).height(1.dp).background(tc.br))
                HintWhen(!item.enabled && item.disabledHint != null, item.disabledHint.orEmpty()) {
                    HoverBox(
                        Modifier.fillMaxWidth().clip(MENU_ITEM_SHAPE),
                        hoverBg = tc.abg,
                        hoverEnabled = item.enabled,
                        onClick = if (item.enabled) ({
                            item.onClick()
                            onClose()
                        }) else null,
                    ) {
                        DisableSelection {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AppText(
                                    item.label,
                                    color = when {
                                        !item.enabled -> tc.td
                                        item.danger -> DANGER_RED
                                        else -> tc.tx
                                    },
                                    fontSize = SMALL_FONT_SIZE,
                                    modifier = Modifier.weight(1f),
                                )
                                item.kbd?.let { AppText(it, color = tc.td, fontSize = 10.sp, fontFamily = MONO) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A "⋯" button (or [anchorContent]) that opens a menu of [items]. Closing the popup hands keyboard focus back to the
 * workspace root, so Esc and the arrow keys keep working afterwards.
 */
@Composable
internal fun TestsOverflowMenu(
    items: List<TestsMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = "More actions",
    enabled: Boolean = true,
    anchorContent: (@Composable () -> Unit)? = null,
) {
    val tc = tc()
    val menu = rememberTestsMenuState()
    Box(modifier) {
        HoverBox(
            modifier = Modifier.size(OVERFLOW_BUTTON_SIZE).clip(CORNER_MD)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
            baseBg = if (menu.open) tc.p2 else Color.Transparent,
            hoverEnabled = enabled,
            onClick = if (enabled) ({ menu.toggle() }) else null,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DisableSelection {
                    if (anchorContent != null) anchorContent() else AppText("⋯", color = if (enabled) tc.ts else tc.td, fontSize = 14.sp)
                }
            }
        }
        if (menu.open) TestsMenuPopup(items, Alignment.TopEnd, OVERFLOW_BUTTON_SIZE + 2.dp, menu::close)
    }
}

/**
 * A button with a ▾ part that opens [menuItems], e.g. "+ Add step ▾": the label runs [onClick], the chevron opens the
 * menu. [enabled] covers the label only (with [disabledHint] explaining why); the chevron stays usable while the menu
 * has items, each of which carries its own enabled state.
 */
@Composable
internal fun TestsSplitButton(
    label: String,
    onClick: () -> Unit,
    menuItems: List<TestsMenuItem>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String? = null,
    variant: ButtonVariant = ButtonVariant.Secondary,
) {
    val menu = rememberTestsMenuState()
    Row(modifier) {
        HintWhen(!enabled && disabledHint != null, disabledHint.orEmpty()) {
            AppButton(label, onClick, variant = variant, enabled = enabled, shape = SPLIT_START_SHAPE)
        }
        Box {
            AppButton(
                "▾",
                onClick = { menu.toggle() },
                variant = variant,
                enabled = menuItems.isNotEmpty(),
                modifier = Modifier.width(SPLIT_CHEVRON_WIDTH).semantics { contentDescription = "$label options" },
                horizontalPadding = 0.dp,
                shape = SPLIT_END_SHAPE,
            )
            if (menu.open) TestsMenuPopup(menuItems, Alignment.TopEnd, OVERFLOW_BUTTON_SIZE + 2.dp, menu::close)
        }
    }
}

// ── Buttons, chips, banners ──────────────────────────────────────────

/** An [AppButton] that explains, in a tooltip, why it is disabled. */
@Composable
internal fun HintedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String? = null,
    variant: ButtonVariant = ButtonVariant.Secondary,
    isDanger: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val button: @Composable () -> Unit = { AppButton(label, onClick, variant, isDanger, enabled, modifier, leadingIcon) }
    if (!enabled && disabledHint != null) {
        TooltipArea(tooltip = { ToolbarTooltip(disabledHint, maxLines = BANNER_MAX_LINES) }) { button() }
    } else {
        button()
    }
}

/** Wraps [content] in a tooltip showing [hint] when [show] is true (the control inside is disabled and says why). */
@Composable
internal fun HintWhen(show: Boolean, hint: String, content: @Composable () -> Unit) {
    if (show) {
        TooltipArea(tooltip = { ToolbarTooltip(hint, maxLines = BANNER_MAX_LINES) }) { content() }
    } else {
        content()
    }
}

/** A small padlock with a tooltip, marking a locked suite or case. */
@Composable
internal fun LockBadge(hint: String, modifier: Modifier = Modifier) {
    TooltipArea(tooltip = { ToolbarTooltip(hint, maxLines = BANNER_MAX_LINES) }, modifier = modifier) {
        Icon(Icons.Outlined.Lock, contentDescription = hint, tint = tc().warn, modifier = Modifier.size(LOCK_ICON_SIZE))
    }
}

/** A rounded pill; with [onRemove] it shows a × that removes it. */
@Composable
internal fun TestsChip(label: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val tc = tc()
    Row(
        modifier.background(tc.abg, CORNER_SM).border(1.dp, tc.ac.copy(alpha = .35f), CORNER_SM)
            .padding(start = 7.dp, end = if (onRemove != null) 2.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(label, color = tc.ac, fontSize = 10.sp, modifier = Modifier.padding(vertical = 2.dp))
        if (onRemove != null) SquareIconButton("×", fontSize = 12.sp, onClick = onRemove, size = 16.dp)
    }
}

/** A square-cornered label such as a check-kind badge. */
@Composable
internal fun TestsBadge(label: String, modifier: Modifier = Modifier) {
    val tc = tc()
    Box(modifier.background(tc.br.copy(alpha = .5f), CORNER_SM).padding(horizontal = 6.dp, vertical = 1.dp)) {
        AppText(label, color = tc.ts, fontSize = 9.sp)
    }
}

/**
 * A clickable summary of one part of a screen ("Preconditions  Device unlocked"): the label muted, the value truncated.
 * [empty] mutes the value (nothing set yet); [selected] marks the chip whose editor is open.
 */
@Composable
internal fun TestsSummaryChip(
    label: String,
    value: String,
    empty: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier.widthIn(max = SUMMARY_CHIP_MAX_WIDTH).clip(CHIP_SHAPE).border(1.dp, if (selected) tc.ac else tc.br, CHIP_SHAPE),
        baseBg = if (selected) tc.abg else tc.p,
        onClick = onClick,
    ) {
        DisableSelection {
            Row(
                Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(label, color = tc.td, fontSize = SMALL_FONT_SIZE)
                AppText(
                    value,
                    color = if (empty) tc.td else tc.tx,
                    fontSize = SMALL_FONT_SIZE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * The banner under the workspace header that shows a failed store call or a warning. It has a dismiss button unless
 * [dismissible] is false (a persistent storage state that only goes away when the cause does).
 */
@Composable
internal fun TestsBannerView(banner: TestsBanner, dismissible: Boolean = true, onDismiss: () -> Unit) {
    val tc = tc()
    val color = if (banner.isError) DANGER_RED else tc.warn
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = .12f)).border(BorderStroke(1.dp, color.copy(alpha = .4f)))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(banner.message, color = color, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES, modifier = Modifier.weight(1f))
        if (dismissible) SquareIconButton("×", fontSize = 14.sp, onClick = onDismiss)
    }
}

/** A read-only notice with a padlock: why the screen below cannot be edited. */
@Composable
internal fun TestsLockedNotice(text: String, modifier: Modifier = Modifier) {
    val tc = tc()
    Row(
        modifier.fillMaxWidth().background(tc.warnBg, CORNER_MD).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = tc.warn, modifier = Modifier.size(LOCK_ICON_SIZE))
        AppText(text, color = tc.tx, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES, modifier = Modifier.weight(1f))
    }
}

// ── Dialog frame and confirm dialog ──────────────────────────────────

/**
 * The surface every Tests dialog shares, placed inside a `Dialog`: 8dp corners, 20dp padding, a 14sp [title], a
 * [body] that scrolls (with a scrollbar) once it outgrows [maxHeight], and a [footer] of centred
 * [DialogActionButton]s that stays visible below it.
 */
@Composable
internal fun TestsDialogFrame(
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = CONFIRM_DIALOG_WIDTH,
    maxHeight: Dp = DIALOG_MAX_HEIGHT,
    body: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    Column(modifier.width(width).heightIn(max = maxHeight).background(tc.p, DIALOG_SHAPE).border(1.dp, tc.br, DIALOG_SHAPE).padding(20.dp)) {
        AppText(title, color = tc.tx, fontSize = TITLE_FONT_SIZE, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        TestsScrollColumn(Modifier.weight(1f, fill = false), content = body)
        Spacer(Modifier.size(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = footer,
        )
    }
}

/** A modal yes/no prompt. Closing it (either way) reclaims the workspace's keyboard focus. */
@Composable
internal fun TestsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val tc = tc()
    val ui = LocalTestsUi.current

    fun dismiss() {
        onDismiss()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = title,
            body = { AppText(message, color = tc.td, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES) },
            footer = {
                DialogActionButton(confirmLabel, active = true, danger = danger) {
                    onConfirm()
                    dismiss()
                }
                DialogActionButton("Cancel", active = false) { dismiss() }
            },
        )
    }
}

// ── Row card ─────────────────────────────────────────────────────────

/**
 * The common frame of a row in a [ReorderableColumn] editor: grip, content, move buttons and a × that removes it.
 * [onRemove] null hides the ×; [enabled] false dims the remove button (the grip and move buttons already follow
 * [ReorderRowScope.enabled]).
 */
@Composable
internal fun ReorderRowCard(
    row: ReorderRowScope,
    enabled: Boolean,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    Row(
        modifier.fillMaxWidth().background(tc.p2, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReorderGrip(row, Modifier.padding(top = 4.dp))
        content()
        extraActions?.invoke(this)
        ReorderMoveButtons(row, Modifier.padding(top = 4.dp))
        if (onRemove != null) SquareIconButton("×", fontSize = 14.sp, onClick = onRemove, enabled = enabled, modifier = Modifier.padding(top = 4.dp))
    }
}
