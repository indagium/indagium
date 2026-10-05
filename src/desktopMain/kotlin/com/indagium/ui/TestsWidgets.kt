@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
        AppText(text.uppercase(), color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                Column(
                    Modifier.width(menuWidth).heightIn(max = MENU_MAX_HEIGHT).verticalScroll(rememberScrollState())
                        .shadow(8.dp, MENU_SHAPE).background(tc.p, MENU_SHAPE).border(1.dp, tc.br, MENU_SHAPE).padding(4.dp),
                ) {
                    if (options.isEmpty()) TestsHint(emptyText, Modifier.padding(8.dp))
                    options.forEach { option ->
                        TestsMenuItem(optionLabel(option), isSelected(option)) {
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
private fun TestsMenuItem(label: String, active: Boolean, onClick: () -> Unit) {
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

/** The banner under the workspace header that shows a failed store call or a warning, with a dismiss button. */
@Composable
internal fun TestsBannerView(banner: TestsBanner, onDismiss: () -> Unit) {
    val tc = tc()
    val color = if (banner.isError) DANGER_RED else tc.warn
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = .12f)).border(BorderStroke(1.dp, color.copy(alpha = .4f)))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(banner.message, color = color, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES, modifier = Modifier.weight(1f))
        SquareIconButton("×", fontSize = 14.sp, onClick = onDismiss)
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

// ── Confirm dialog ───────────────────────────────────────────────────

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
        Column(
            Modifier.width(CONFIRM_DIALOG_WIDTH).background(tc.p, DIALOG_SHAPE)
                .border(1.dp, tc.br, DIALOG_SHAPE).padding(20.dp),
        ) {
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            AppText(message, color = tc.td, fontSize = SMALL_FONT_SIZE, maxLines = BANNER_MAX_LINES)
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogActionButton(confirmLabel, active = true, danger = danger) {
                    onConfirm()
                    dismiss()
                }
                DialogActionButton("Cancel", active = false) { dismiss() }
            }
        }
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
