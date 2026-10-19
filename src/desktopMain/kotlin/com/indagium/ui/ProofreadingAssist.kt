package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.indagium.model.ProofreadingSettings
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class ProofreadingUi(
    val service: EnglishProofreadingService,
    val settings: ProofreadingSettings,
    val ignoredSuggestions: MutableSet<String>,
)

internal val LocalProofreadingUi = staticCompositionLocalOf<ProofreadingUi?> { null }

/** Window-space caret geometry published by the text field that owns the suggestion selection. */
@Stable
class ProofreadingPopupAnchorState internal constructor() {
    internal var caretBoundsInWindow: IntRect? by mutableStateOf(null)
        private set
    internal var keyEventHandler: ((KeyEvent) -> Boolean)? = null

    internal fun handleKeyEvent(event: KeyEvent): Boolean = keyEventHandler?.invoke(event) == true

    internal fun update(coordinates: LayoutCoordinates?, layout: TextLayoutResult?, offset: Int) {
        if (coordinates == null || layout == null) return
        val caret = layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length))
        val topLeft = coordinates.localToWindow(Offset(caret.left, caret.top))
        val bottomRight = coordinates.localToWindow(Offset(caret.right, caret.bottom))
        val next = IntRect(
            topLeft.x.roundToInt(),
            topLeft.y.roundToInt(),
            bottomRight.x.roundToInt().coerceAtLeast(topLeft.x.roundToInt() + 1),
            bottomRight.y.roundToInt().coerceAtLeast(topLeft.y.roundToInt() + 1),
        )
        if (caretBoundsInWindow != next) caretBoundsInWindow = next
    }
}

@Composable
internal fun rememberProofreadingPopupAnchorState(key: Any?): ProofreadingPopupAnchorState = remember(key) {
    ProofreadingPopupAnchorState()
}

/** Places a suggestion beside the caret, flips above it when needed and keeps the card in-window. */
internal class ProofreadingPopupPositionProvider(
    private val caretBounds: IntRect,
    private val marginPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = marginPx.coerceAtLeast(0)
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val wantedX = if (layoutDirection == LayoutDirection.Ltr) caretBounds.left else caretBounds.right - popupContentSize.width
        val x = wantedX.coerceIn(margin, maxX)
        val below = caretBounds.bottom + gapPx
        val above = caretBounds.top - popupContentSize.height - gapPx
        val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
        val wantedY = if (fitsBelow || above < margin) below else above
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, wantedY.coerceIn(margin, maxY))
    }
}

/** Adds ephemeral spelling/grammar marks and a selection-aware suggestion popup to one editable prose field. */
@Composable
internal fun ProofreadingAssist(
    value: TextFieldValue,
    available: Boolean,
    editorFocused: Boolean,
    targetId: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusRestored: () -> Unit = {},
    anchorState: ProofreadingPopupAnchorState? = null,
    onInteractionActiveChange: (Boolean) -> Unit = {},
): VisualTransformation {
    val ui = LocalProofreadingUi.current
    val canProofread = ui != null && targetId != null && available && ui.settings.enabled
    var keyboardPopupOpen by remember(targetId) { mutableStateOf(false) }
    var popupText by remember(targetId) { mutableStateOf<String?>(null) }
    var popupSelection by remember(targetId) { mutableStateOf<TextRange?>(null) }
    var popupIssue by remember(targetId) { mutableStateOf<ProofreadingIssue?>(null) }
    var dismissedAt by remember(targetId) { mutableStateOf<Pair<String, TextRange>?>(null) }
    var popupHasFocus by remember(targetId) { mutableStateOf(false) }
    var issues by remember(targetId) { mutableStateOf(emptyList<ProofreadingIssue>()) }
    var checkedText by remember(targetId) { mutableStateOf<String?>(null) }
    var checkedRevision by remember(targetId) { mutableStateOf(0L) }
    val latestValue = rememberUpdatedState(value)
    val latestAvailable = rememberUpdatedState(canProofread)
    val latestEditorFocused = rememberUpdatedState(editorFocused)
    val latestApply = rememberUpdatedState(onValueChange)
    val latestFocusRestore = rememberUpdatedState(onFocusRestored)
    val latestInteractionChange = rememberUpdatedState(onInteractionActiveChange)
    var revision by remember(targetId) { mutableStateOf(0L) }
    val active = canProofread && (editorFocused || keyboardPopupOpen)
    val latestActive = rememberUpdatedState(active)
    val firstActionFocusRequester = remember(targetId) { FocusRequester() }
    val caretBounds = anchorState?.caretBoundsInWindow
    val density = LocalDensity.current
    val positionProvider = remember(caretBounds, density) {
        caretBounds?.let {
            ProofreadingPopupPositionProvider(
                caretBounds = it,
                marginPx = with(density) { 8.dp.roundToPx() },
                gapPx = with(density) { 4.dp.roundToPx() },
            )
        }
    }

    fun setKeyboardPopupOpen(
        open: Boolean,
        suppressPassive: Boolean = false,
        issue: ProofreadingIssue? = null,
    ) {
        if (suppressPassive) dismissedAt = value.text to (popupSelection ?: value.selection)
        if (keyboardPopupOpen == open) return
        keyboardPopupOpen = open
        popupText = if (open) value.text else null
        popupSelection = if (open) value.selection else null
        popupIssue = if (open) issue else null
        if (!open) popupHasFocus = false
        latestInteractionChange.value(open)
    }

    LaunchedEffect(value.text, value.selection, editorFocused) {
        if (dismissedAt?.first != value.text) {
            dismissedAt = null
        }
        if (editorFocused && dismissedAt?.second?.let { !sameLogicalProofreadingSelection(it, value.selection) } == true) {
            dismissedAt = null
        }
        if (keyboardPopupOpen && popupText != value.text) {
            setKeyboardPopupOpen(false)
        } else if (keyboardPopupOpen && editorFocused && popupSelection?.let {
                !sameLogicalProofreadingSelection(it, value.selection)
            } == true
        ) {
            setKeyboardPopupOpen(false)
        }
    }
    LaunchedEffect(keyboardPopupOpen) {
        if (keyboardPopupOpen) {
            withFrameNanos { }
            runCatching { firstActionFocusRequester.requestFocus() }
        }
    }
    DisposableEffect(anchorState) {
        onDispose {
            anchorState?.keyEventHandler = null
            latestInteractionChange.value(false)
        }
    }

    LaunchedEffect(targetId, value.text, active, ui?.settings?.language) {
        val request = ++revision
        issues = emptyList()
        checkedText = null
        if (!active || value.text.isBlank()) return@LaunchedEffect
        val text = value.text
        val language = ui.settings.language
        delay(PROOFREADING_DEBOUNCE_MS)
        val result = try {
            ui.service.check(text, language)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        if (revision == request && active && latestValue.value.text == text) {
            issues = result.filter { issue ->
                issue.start >= 0 && issue.end <= text.length && issue.start < issue.end &&
                    suggestionIgnoreKey(issue, text) !in ui.ignoredSuggestions
            }
            checkedText = text
            checkedRevision = request
        }
    }
    LaunchedEffect(targetId) { issues = emptyList(); checkedText = null }

    val underlineColor = tc().ac
    val resultIsCurrent = checkedText == value.text && checkedRevision == revision
    val transformation = remember(issues, active, resultIsCurrent, underlineColor) {
        if (!active || !resultIsCurrent || issues.isEmpty()) VisualTransformation.None
        else VisualTransformation { original ->
            val annotated = AnnotatedString.Builder(original.text)
            issues.forEach { issue ->
                val start = issue.start.coerceIn(0, original.length)
                val end = issue.end.coerceIn(start, original.length)
                if (start < end) annotated.addStyle(
                    SpanStyle(color = underlineColor, textDecoration = TextDecoration.Underline),
                    start,
                    end,
                )
            }
            TransformedText(annotated.toAnnotatedString(), OffsetMapping.Identity)
        }
    }

    val selectionStart = minOf(value.selection.start, value.selection.end)
    val selectionEnd = maxOf(value.selection.start, value.selection.end)
    val focusedIssue = issues.takeIf { resultIsCurrent }?.firstOrNull { issue ->
        if (selectionStart == selectionEnd) selectionStart in issue.start..issue.end
        else selectionStart < issue.end && selectionEnd > issue.start
    }
    val issueForPopup = if (keyboardPopupOpen && popupText == value.text && resultIsCurrent) popupIssue else focusedIssue
    val passivePopupDismissed = dismissedAt?.let { dismissal ->
        dismissal.first == value.text && sameLogicalProofreadingSelection(dismissal.second, value.selection)
    } == true
    val hasCurrentPopupIssue = active && issueForPopup != null && positionProvider != null
    val popupIsNotSuppressed = keyboardPopupOpen || !passivePopupDismissed
    val shouldShowPopup = hasCurrentPopupIssue && popupIsNotSuppressed
    val focusedIssueIsCurrent = focusedIssue != null && resultIsCurrent

    fun dismissKeyboardPopupAndRestoreFocus() {
        setKeyboardPopupOpen(false, suppressPassive = true)
        latestFocusRestore.value()
    }

    SideEffect {
        anchorState?.keyEventHandler = proofreadingKeyHandler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@proofreadingKeyHandler false
            val enterKey = event.key == Key.Enter || event.key == Key.NumPadEnter
            val altOnly = event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
            val editorCanOpen = latestAvailable.value && latestEditorFocused.value && latestActive.value
            when {
                event.key == Key.Escape && keyboardPopupOpen -> {
                    dismissKeyboardPopupAndRestoreFocus()
                    true
                }
                enterKey && altOnly && editorCanOpen && focusedIssueIsCurrent -> {
                    setKeyboardPopupOpen(true, issue = requireNotNull(focusedIssue))
                    true
                }
                else -> false
            }
        }
        if (!canProofread && keyboardPopupOpen) setKeyboardPopupOpen(false)
    }
    if (shouldShowPopup) {
        ProofreadingSuggestionPopup(
            issue = requireNotNull(issueForPopup),
            positionProvider = requireNotNull(positionProvider),
            keyboardOpen = keyboardPopupOpen,
            firstActionFocusRequester = firstActionFocusRequester,
            onPopupFocusChanged = { hasFocus ->
                if (hasFocus) popupHasFocus = true
                else if (popupHasFocus && keyboardPopupOpen) {
                    popupHasFocus = false
                    setKeyboardPopupOpen(false)
                }
            },
            onDismissRequest = { setKeyboardPopupOpen(false) },
            onEscape = ::dismissKeyboardPopupAndRestoreFocus,
            onReplace = { issue, replacement ->
                val latest = latestValue.value
                val checkIsCurrent = latestAvailable.value && latestActive.value && checkedText == latest.text && checkedRevision == revision
                if (checkIsCurrent && latest.text.substringOrNull(issue.start, issue.end) != null) {
                    val updated = latest.text.replaceRange(issue.start, issue.end, replacement)
                    latestApply.value(TextFieldValue(updated, TextRange(issue.start + replacement.length)))
                    setKeyboardPopupOpen(false)
                    latestFocusRestore.value()
                }
            },
            onIgnore = { issue ->
                val latest = latestValue.value
                if (latestAvailable.value && latestActive.value && checkedText == latest.text && checkedRevision == revision) {
                    val ignoredKey = suggestionIgnoreKey(issue, latest.text)
                    ui.ignoredSuggestions.add(ignoredKey)
                    issues = issues.filterNot { suggestionIgnoreKey(it, latest.text) == ignoredKey }
                    setKeyboardPopupOpen(false)
                    latestFocusRestore.value()
                }
            },
        )
    }
    return transformation
}

@Composable
private fun ProofreadingSuggestionPopup(
    issue: ProofreadingIssue,
    positionProvider: PopupPositionProvider,
    keyboardOpen: Boolean,
    firstActionFocusRequester: FocusRequester,
    onPopupFocusChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onEscape: () -> Unit,
    onReplace: (ProofreadingIssue, String) -> Unit,
    onIgnore: (ProofreadingIssue) -> Unit,
) {
    val tc = tc()
    val replacements = issue.replacements.take(MAX_PROOFREADING_REPLACEMENTS)
    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = keyboardOpen),
    ) {
        Column(
            Modifier.widthIn(max = 280.dp).background(tc.p, RoundedCornerShape(6.dp))
                .border(1.dp, tc.br, RoundedCornerShape(6.dp)).testTag("proofreading-suggestion-popup")
                .onFocusChanged { onPopupFocusChanged(it.hasFocus) }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (keyboardOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onEscape()
                        true
                    } else false
                }
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AppText(issue.message, color = tc.tx, fontSize = 11.sp, maxLines = 3)
            AppText(
                if (keyboardOpen) "Tab to move · Enter/Space to choose · Esc to return"
                else "Alt/Option+Enter opens keyboard suggestions",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.testTag("proofreading-suggestion-keyboard-hint"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                replacements.forEachIndexed { index, replacement ->
                    var optionFocused by remember(issue.start, replacement) { mutableStateOf(false) }
                    HoverBox(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstActionFocusRequester) else Modifier)
                            .onFocusChanged { optionFocused = it.isFocused }
                            .testTag("proofreading-suggestion-option-$index")
                            .onPreviewKeyEvent { event ->
                                if (keyboardOpen && isProofreadingActivationKey(event)) {
                                    onReplace(issue, replacement)
                                    true
                                } else false
                            },
                        forceHover = optionFocused,
                        onClick = { onReplace(issue, replacement) },
                    ) {
                        AppText(replacement, color = tc.tx, fontSize = 11.sp, maxLines = 3)
                    }
                }
                var ignoreFocused by remember(issue.start) { mutableStateOf(false) }
                AppButton(
                    "Ignore",
                    onClick = { onIgnore(issue) },
                    variant = ButtonVariant.Ghost,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.then(if (replacements.isEmpty()) Modifier.focusRequester(firstActionFocusRequester) else Modifier)
                        .onFocusChanged { ignoreFocused = it.isFocused }
                        .background(if (ignoreFocused) tc.hv else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
                        .testTag("proofreading-suggestion-ignore")
                        .onPreviewKeyEvent { event ->
                            if (keyboardOpen && isProofreadingActivationKey(event)) {
                                onIgnore(issue)
                                true
                            } else false
                        },
                    tooltip = "Ignore this suggestion for this app session",
                )
            }
        }
    }
}

private fun isProofreadingActivationKey(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)

private fun sameLogicalProofreadingSelection(expected: TextRange, actual: TextRange): Boolean =
    expected == actual || (expected.start != expected.end && actual.collapsed && (actual.start == expected.start || actual.start == expected.end))

private fun suggestionIgnoreKey(issue: ProofreadingIssue, text: String): String =
    "${issue.ruleId}:${text.substringOrNull(issue.start, issue.end)?.lowercase().orEmpty()}"

private fun String.substringOrNull(start: Int, end: Int): String? =
    takeIf { start >= 0 && end >= start && end <= length }?.substring(start, end)

private const val PROOFREADING_DEBOUNCE_MS = 600L
private const val MAX_PROOFREADING_REPLACEMENTS = 4
