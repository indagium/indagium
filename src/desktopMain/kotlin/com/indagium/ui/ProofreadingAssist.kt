@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.ProofreadingSettings
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingCategory
import com.indagium.proofread.ProofreadingIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProofreadingUi(
    val service: EnglishProofreadingService,
    val settings: ProofreadingSettings,
    val ignoredSuggestions: MutableSet<String>,
)

internal val LocalProofreadingUi = staticCompositionLocalOf<ProofreadingUi?> { null }

/** What the field tells the assistant about the pointer: moved, pressed or left. Position is null on exit. */
internal typealias ProofreadingPointerHandler = (type: PointerEventType, position: Offset?, layout: TextLayoutResult?) -> Unit

/** Window-space geometry and input hooks published by the text field that owns the suggestion selection. */
@Stable
class ProofreadingPopupAnchorState internal constructor() {
    internal var caretBoundsInWindow: IntRect? by mutableStateOf(null)
        private set
    internal var keyEventHandler: ((KeyEvent) -> Boolean)? = null

    /** The marks the field should draw, in the coordinates of the text it currently shows. */
    internal var marks: List<ProofreadingIssue> by mutableStateOf(emptyList())
    internal var pointerHandler: ProofreadingPointerHandler? = null
    private var coordinates: LayoutCoordinates? = null
    private var layout: TextLayoutResult? = null

    internal fun handleKeyEvent(event: KeyEvent): Boolean = keyEventHandler?.invoke(event) == true

    internal fun update(coordinates: LayoutCoordinates?, layout: TextLayoutResult?, offset: Int) {
        if (coordinates == null || layout == null) return
        this.coordinates = coordinates
        this.layout = layout
        val next = windowBounds(coordinates, layout, offset)
        if (caretBoundsInWindow != next) caretBoundsInWindow = next
    }

    /** Window bounds of the character at [offset]: where a suggestion for a mark starting there should hang. */
    internal fun boundsInWindowAt(offset: Int): IntRect? {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val layout = layout ?: return null
        return windowBounds(coordinates, layout, offset)
    }

    private fun windowBounds(coordinates: LayoutCoordinates, layout: TextLayoutResult, offset: Int): IntRect {
        val caret = layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length))
        val topLeft = coordinates.localToWindow(Offset(caret.left, caret.top))
        val bottomRight = coordinates.localToWindow(Offset(caret.right, caret.bottom))
        return IntRect(
            topLeft.x.roundToInt(),
            topLeft.y.roundToInt(),
            bottomRight.x.roundToInt().coerceAtLeast(topLeft.x.roundToInt() + 1),
            bottomRight.y.roundToInt().coerceAtLeast(topLeft.y.roundToInt() + 1),
        )
    }
}

@Composable
internal fun rememberProofreadingPopupAnchorState(key: Any?): ProofreadingPopupAnchorState = remember(key) {
    ProofreadingPopupAnchorState()
}

/** Places a suggestion beside the anchor, flips above it when needed and keeps the card in-window. */
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

/**
 * Keeps marks that survived an edit and moves them with it, instead of blanking every underline until the next check
 * lands. A mark the edit touches (or whose neighbouring word characters changed) is dropped; the fresh result replaces
 * the whole list when it arrives.
 */
internal fun shiftProofreadingIssues(issues: List<ProofreadingIssue>, oldText: String, newText: String): List<ProofreadingIssue> {
    if (issues.isEmpty() || oldText == newText) return issues
    var prefix = 0
    val shortest = min(oldText.length, newText.length)
    while (prefix < shortest && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
    val oldChangedEnd = oldText.length - suffix
    val delta = newText.length - oldText.length
    return issues.mapNotNull { issue ->
        val shifted = when {
            issue.end <= prefix -> issue
            issue.start >= oldChangedEnd -> issue.copy(start = issue.start + delta, end = issue.end + delta)
            else -> null
        } ?: return@mapNotNull null
        val keeps = shifted.start >= 0 && shifted.end <= newText.length && shifted.start < shifted.end &&
            newText.regionMatches(shifted.start, oldText, issue.start, issue.end - issue.start) &&
            isWordChar(newText.getOrNull(shifted.start - 1)) == isWordChar(oldText.getOrNull(issue.start - 1)) &&
            isWordChar(newText.getOrNull(shifted.end)) == isWordChar(oldText.getOrNull(issue.end))
        if (keeps) shifted else null
    }
}

private fun isWordChar(char: Char?): Boolean = char != null && char.isLetterOrDigit()

/** The mark under a pointer position given in text-layout coordinates, or null when the pointer is on plain text. */
internal fun proofreadingIssueAt(issues: List<ProofreadingIssue>, layout: TextLayoutResult, position: Offset): ProofreadingIssue? {
    val length = layout.layoutInput.text.length
    if (length == 0 || issues.isEmpty()) return null
    val nearest = layout.getOffsetForPosition(position)
    val hit = listOf(nearest - 1, nearest).firstOrNull { index ->
        index in 0 until length && layout.getBoundingBox(index).inflate(1f).contains(position)
    } ?: return null
    return issues.firstOrNull { hit >= it.start && hit < it.end }
}

/**
 * Draws the proofreading marks behind the text and reports pointer moves/presses to [state]. Apply it to the box that
 * wraps the inner text field, so its coordinates are the text layout's. Spelling gets a red wave, everything else an
 * amber dotted line; neither touches the text colour.
 */
@Composable
internal fun Modifier.proofreadingDecoration(
    state: ProofreadingPopupAnchorState?,
    layout: () -> TextLayoutResult?,
): Modifier {
    if (state == null) return this
    val warn = tc().warn
    return this
        .drawBehind { drawProofreadingMarks(state.marks, layout(), DANGER_RED, warn) }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter ->
                            state.pointerHandler?.invoke(PointerEventType.Move, change.position, layout())
                        PointerEventType.Press ->
                            if (event.buttons.isPrimaryPressed) state.pointerHandler?.invoke(PointerEventType.Press, change.position, layout())
                        PointerEventType.Exit -> state.pointerHandler?.invoke(PointerEventType.Exit, null, null)
                        else -> Unit
                    }
                }
            }
        }
}

private fun DrawScope.drawProofreadingMarks(
    marks: List<ProofreadingIssue>,
    layout: TextLayoutResult?,
    spelling: Color,
    other: Color,
) {
    layout ?: return
    val length = layout.layoutInput.text.length
    marks.forEach { issue ->
        val start = issue.start.coerceIn(0, length)
        val end = issue.end.coerceIn(start, length)
        if (start >= end) return@forEach
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val from = max(start, layout.getLineStart(line))
            val to = min(end - 1, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (to < from) continue
            val left = min(layout.getBoundingBox(from).left, layout.getBoundingBox(to).left)
            val right = max(layout.getBoundingBox(from).right, layout.getBoundingBox(to).right)
            val y = min(layout.getLineBaseline(line) + UNDERLINE_BASELINE_GAP.toPx(), layout.getLineBottom(line) - 1.dp.toPx())
            if (issue.category == ProofreadingCategory.SPELLING) drawWavyUnderline(left, right, y, spelling)
            else drawDottedUnderline(left, right, y, other)
        }
    }
}

private val UNDERLINE_BASELINE_GAP = 2.dp

// A dash this short with round caps renders as a dot.
private const val DOT_LENGTH_PX = 0.01f

/** A red squiggle: alternating quadratic bumps about 1.2dp tall. */
internal fun DrawScope.drawWavyUnderline(left: Float, right: Float, y: Float, color: Color) {
    val half = 2.dp.toPx()
    val amplitude = 1.2.dp.toPx()
    val path = Path().apply { moveTo(left, y) }
    var x = left
    var up = true
    while (x < right) {
        val next = min(x + half, right)
        path.quadraticTo((x + next) / 2f, y + (if (up) -amplitude else amplitude) * 2f, next, y)
        x = next
        up = !up
    }
    drawPath(path, color, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
}

/** Round 2dp dots with a gap, for grammar, style and capitalisation. */
internal fun DrawScope.drawDottedUnderline(left: Float, right: Float, y: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH_PX, 4.dp.toPx())),
    )
}

/** Marks that are still valid for the text they were computed from. */
private data class IssueSnapshot(val text: String, val issues: List<ProofreadingIssue>)

/** A suggestion opened by hovering or clicking a mark. [pinned] ones stay until the caret leaves, the text changes or Esc. */
private data class PassivePopup(val start: Int, val end: Int, val text: String, val pinned: Boolean)

/**
 * Adds ephemeral spelling/grammar marks and a suggestion popup to one editable prose field. The field draws the marks
 * itself with [proofreadingDecoration]; this composable keeps them fresh and owns the popup. The popup opens only on
 * hover or click of a marked range, or with Alt/Option+Enter (keyboard mode) - never just because the caret is in a word.
 */
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
) {
    val ui = LocalProofreadingUi.current
    val canProofread = ui != null && targetId != null && available && ui.settings.enabled
    var keyboardPopupOpen by remember(targetId) { mutableStateOf(false) }
    var popupText by remember(targetId) { mutableStateOf<String?>(null) }
    var popupSelection by remember(targetId) { mutableStateOf<TextRange?>(null) }
    var popupIssue by remember(targetId) { mutableStateOf<ProofreadingIssue?>(null) }
    var popupHasFocus by remember(targetId) { mutableStateOf(false) }
    var passive by remember(targetId) { mutableStateOf<PassivePopup?>(null) }
    var hoverCandidate by remember(targetId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var popupHovered by remember(targetId) { mutableStateOf(false) }
    var snapshot by remember(targetId) { mutableStateOf(IssueSnapshot("", emptyList())) }
    val latestValue = rememberUpdatedState(value)
    val latestAvailable = rememberUpdatedState(canProofread)
    val latestEditorFocused = rememberUpdatedState(editorFocused)
    val latestApply = rememberUpdatedState(onValueChange)
    val latestFocusRestore = rememberUpdatedState(onFocusRestored)
    val latestInteractionChange = rememberUpdatedState(onInteractionActiveChange)
    val active = canProofread && (editorFocused || keyboardPopupOpen)
    val latestActive = rememberUpdatedState(active)
    val firstActionFocusRequester = remember(targetId) { FocusRequester() }
    val density = LocalDensity.current

    // Marks follow the text while the next check is pending, so typing never blanks the underlines.
    val liveIssues = if (active) remember(snapshot, value.text) { shiftProofreadingIssues(snapshot.issues, snapshot.text, value.text) } else emptyList()
    val latestLive = rememberUpdatedState(liveIssues)
    SideEffect { anchorState?.marks = liveIssues }

    fun setKeyboardPopupOpen(open: Boolean, issue: ProofreadingIssue? = null) {
        if (open) {
            passive = null
            hoverCandidate = null
        }
        if (keyboardPopupOpen == open) return
        keyboardPopupOpen = open
        popupText = if (open) value.text else null
        popupSelection = if (open) value.selection else null
        popupIssue = if (open) issue else null
        if (!open) popupHasFocus = false
        latestInteractionChange.value(open)
    }

    LaunchedEffect(value.text, value.selection, editorFocused) {
        if (keyboardPopupOpen && popupText != value.text) {
            setKeyboardPopupOpen(false)
        } else if (keyboardPopupOpen && editorFocused && popupSelection?.let {
                !sameLogicalProofreadingSelection(it, value.selection)
            } == true
        ) {
            setKeyboardPopupOpen(false)
        }
        val open = passive
        if (open != null) {
            val caret = value.selection
            val outside = minOf(caret.start, caret.end) > open.end || maxOf(caret.start, caret.end) < open.start
            if (open.text != value.text || (open.pinned && editorFocused && outside)) passive = null
        }
    }
    LaunchedEffect(keyboardPopupOpen) {
        if (keyboardPopupOpen) {
            withFrameNanos { }
            runCatching { firstActionFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            passive = null
            hoverCandidate = null
            popupHovered = false
        }
    }
    DisposableEffect(anchorState) {
        onDispose {
            anchorState?.keyEventHandler = null
            anchorState?.pointerHandler = null
            anchorState?.marks = emptyList()
            latestInteractionChange.value(false)
        }
    }

    LaunchedEffect(targetId, value.text, active, ui?.settings?.language) {
        if (!active || value.text.isBlank()) {
            snapshot = IssueSnapshot(value.text, emptyList())
            return@LaunchedEffect
        }
        val text = value.text
        snapshot = IssueSnapshot(text, shiftProofreadingIssues(snapshot.issues, snapshot.text, text))
        val language = ui.settings.language
        delay(PROOFREADING_DEBOUNCE_MS)
        val result = try {
            ui.service.check(text, language)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        if (latestActive.value && latestValue.value.text == text) {
            snapshot = IssueSnapshot(
                text,
                result.filter { issue ->
                    issue.start >= 0 && issue.end <= text.length && issue.start < issue.end &&
                        suggestionIgnoreKey(issue, text) !in ui.ignoredSuggestions
                },
            )
        }
    }

    // Hover opens after a short dwell so sweeping the mouse across text does not flicker popups; leaving closes after a
    // grace period that lets the pointer travel into the popup. A pinned (clicked) popup ignores both.
    LaunchedEffect(hoverCandidate, popupHovered) {
        val candidate = hoverCandidate
        if (candidate != null) {
            val already = passive?.let { it.start == candidate.first && it.end == candidate.second } == true
            if (!already && !keyboardPopupOpen) {
                delay(PROOFREADING_HOVER_OPEN_MS)
                val current = passive
                val pinnedHere = current?.pinned == true && current.start == candidate.first && current.end == candidate.second
                if (!pinnedHere) passive = PassivePopup(candidate.first, candidate.second, latestValue.value.text, pinned = false)
            }
        } else if (!popupHovered) {
            delay(PROOFREADING_HOVER_CLOSE_MS)
            if (passive?.pinned != true) passive = null
        }
    }

    val selectionStart = minOf(value.selection.start, value.selection.end)
    val selectionEnd = maxOf(value.selection.start, value.selection.end)
    val focusedIssue = liveIssues.firstOrNull { issue ->
        if (selectionStart == selectionEnd) selectionStart in issue.start..issue.end
        else selectionStart < issue.end && selectionEnd > issue.start
    }
    val passiveIssue = passive?.takeIf { it.text == value.text }?.let { open ->
        liveIssues.firstOrNull { it.start == open.start && it.end == open.end }
    }
    val keyboardIssue = if (keyboardPopupOpen && popupText == value.text) popupIssue?.let { shown -> liveIssues.firstOrNull { it == shown } } else null
    val issueForPopup = keyboardIssue ?: passiveIssue
    // Reading the caret rectangle makes the popup follow scrolling and layout changes of the field.
    anchorState?.caretBoundsInWindow
    val anchorBounds = issueForPopup?.let { anchorState?.boundsInWindowAt(it.start) }
    val positionProvider = remember(anchorBounds, density) {
        anchorBounds?.let {
            ProofreadingPopupPositionProvider(
                caretBounds = it,
                marginPx = with(density) { 8.dp.roundToPx() },
                gapPx = with(density) { 4.dp.roundToPx() },
            )
        }
    }
    val shouldShowPopup = active && issueForPopup != null && positionProvider != null
    val compositionText = value.text
    LaunchedEffect(shouldShowPopup) { if (!shouldShowPopup) popupHovered = false }

    fun dismissPopupAndRestoreFocus() {
        passive = null
        hoverCandidate = null
        setKeyboardPopupOpen(false)
        latestFocusRestore.value()
    }

    SideEffect {
        anchorState?.keyEventHandler = proofreadingKeyHandler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@proofreadingKeyHandler false
            val enterKey = event.key == Key.Enter || event.key == Key.NumPadEnter
            val altOnly = event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
            val editorCanOpen = latestAvailable.value && latestEditorFocused.value && latestActive.value
            when {
                event.key == Key.Escape && (keyboardPopupOpen || passiveIssue != null) -> {
                    dismissPopupAndRestoreFocus()
                    true
                }
                enterKey && altOnly && editorCanOpen && focusedIssue != null -> {
                    setKeyboardPopupOpen(true, issue = focusedIssue)
                    true
                }
                else -> false
            }
        }
        anchorState?.pointerHandler = proofreadingPointerHandler@{ type, position, layout ->
            if (!latestActive.value) return@proofreadingPointerHandler
            if (type == PointerEventType.Exit || position == null || layout == null) {
                hoverCandidate = null
                return@proofreadingPointerHandler
            }
            val issue = proofreadingIssueAt(latestLive.value, layout, position)
            hoverCandidate = issue?.let { it.start to it.end }
            if (type == PointerEventType.Press) {
                if (issue != null && !keyboardPopupOpen) {
                    passive = PassivePopup(issue.start, issue.end, latestValue.value.text, pinned = true)
                } else if (passive?.pinned == true) {
                    passive = null
                }
            }
        }
        if (!canProofread && keyboardPopupOpen) setKeyboardPopupOpen(false)
    }
    if (shouldShowPopup) {
        ProofreadingSuggestionPopup(
            issue = requireNotNull(issueForPopup),
            flagged = compositionText.substringOrNull(issueForPopup.start, issueForPopup.end).orEmpty(),
            positionProvider = requireNotNull(positionProvider),
            keyboardOpen = keyboardPopupOpen,
            firstActionFocusRequester = firstActionFocusRequester,
            onPopupFocusChanged = { hasFocus ->
                if (hasFocus) {
                    popupHasFocus = true
                } else if (popupHasFocus && keyboardPopupOpen) {
                    popupHasFocus = false
                    setKeyboardPopupOpen(false)
                }
            },
            onHoverChanged = { popupHovered = it },
            onDismissRequest = {
                passive = null
                setKeyboardPopupOpen(false)
            },
            onEscape = ::dismissPopupAndRestoreFocus,
            onReplace = { issue, replacement ->
                val latest = latestValue.value
                if (latestAvailable.value && latestActive.value && latest.text == compositionText &&
                    latest.text.substringOrNull(issue.start, issue.end) != null
                ) {
                    val updated = latest.text.replaceRange(issue.start, issue.end, replacement)
                    passive = null
                    hoverCandidate = null
                    latestApply.value(TextFieldValue(updated, TextRange(issue.start + replacement.length)))
                    setKeyboardPopupOpen(false)
                    latestFocusRestore.value()
                }
            },
            onIgnore = { issue ->
                val latest = latestValue.value
                if (latestAvailable.value && latestActive.value && latest.text == compositionText) {
                    val ignoredKey = suggestionIgnoreKey(issue, latest.text)
                    ui.ignoredSuggestions.add(ignoredKey)
                    snapshot = IssueSnapshot(
                        latest.text,
                        liveIssues.filterNot { suggestionIgnoreKey(it, latest.text) == ignoredKey },
                    )
                    passive = null
                    hoverCandidate = null
                    setKeyboardPopupOpen(false)
                    latestFocusRestore.value()
                }
            },
        )
    }
}

/** The category line shows the kind of problem; the rule's own message only appears when it says more than that. */
internal fun proofreadingDetailMessage(issue: ProofreadingIssue): String? {
    val normalized = issue.message.trim().trimEnd('.', '!').lowercase()
    val boilerplate = normalized.isEmpty() ||
        normalized == issue.category.label ||
        normalized.startsWith("possible spelling mistake") ||
        normalized == "spelling mistake" ||
        normalized == "spelling"
    return issue.message.trim().takeUnless { boilerplate }
}

@Composable
private fun ProofreadingSuggestionPopup(
    issue: ProofreadingIssue,
    flagged: String,
    positionProvider: PopupPositionProvider,
    keyboardOpen: Boolean,
    firstActionFocusRequester: FocusRequester,
    onPopupFocusChanged: (Boolean) -> Unit,
    onHoverChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onEscape: () -> Unit,
    onReplace: (ProofreadingIssue, String) -> Unit,
    onIgnore: (ProofreadingIssue) -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val replacements = issue.replacements.take(MAX_PROOFREADING_REPLACEMENTS)
    val detail = proofreadingDetailMessage(issue)
    val warn = tc.warn
    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = keyboardOpen),
    ) {
        Column(
            Modifier.widthIn(max = 260.dp).background(tc.p, shape)
                .border(1.dp, tc.br, shape).testTag("proofreading-suggestion-popup")
                .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) }
                .onFocusChanged { onPopupFocusChanged(it.hasFocus) }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (keyboardOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onEscape()
                        true
                    } else {
                        false
                    }
                }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.weight(1f, fill = false).drawBehind {
                        val y = size.height - 1.dp.toPx()
                        if (issue.category == ProofreadingCategory.SPELLING) drawWavyUnderline(0f, size.width, y, DANGER_RED)
                        else drawDottedUnderline(0f, size.width, y, warn)
                    }.padding(bottom = 3.dp),
                ) {
                    AppText(
                        flagged.ifEmpty { issue.category.label },
                        color = tc.tx,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppText(issue.category.label, color = tc.td, fontSize = 10.sp)
                Box(Modifier.weight(1f))
                Box(
                    Modifier.background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                        .padding(horizontal = 4.dp, vertical = 1.dp).testTag("proofreading-suggestion-key-cap"),
                ) {
                    AppText(if (isMacOs) "⌥↵" else "Alt+↵", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                }
            }
            if (detail != null) AppText(detail, color = tc.ts, fontSize = 11.sp, maxLines = 3)
            if (keyboardOpen) AppText(
                "Tab to move · Enter/Space to choose · Esc to return",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.testTag("proofreading-suggestion-keyboard-hint"),
            )
            if (replacements.isNotEmpty()) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                replacements.forEachIndexed { index, replacement ->
                    SuggestionChip(
                        text = replacement,
                        primary = index == 0,
                        focusRequester = if (index == 0) firstActionFocusRequester else null,
                        keyboardOpen = keyboardOpen,
                        tag = "proofreading-suggestion-option-$index",
                        onApply = { onReplace(issue, replacement) },
                    )
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
                    .background(if (ignoreFocused) tc.hv else Color.Transparent, RoundedCornerShape(4.dp))
                    .testTag("proofreading-suggestion-ignore")
                    .onPreviewKeyEvent { event ->
                        if (keyboardOpen && isProofreadingActivationKey(event)) {
                            onIgnore(issue)
                            true
                        } else {
                            false
                        }
                    },
                tooltip = "Ignore this suggestion for this app session",
            )
        }
    }
}

/** A replacement as a pill. The first one is accent-tinted: it is the default Enter applies in keyboard mode. */
@Composable
private fun SuggestionChip(
    text: String,
    primary: Boolean,
    focusRequester: FocusRequester?,
    keyboardOpen: Boolean,
    tag: String,
    onApply: () -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(50)
    var focused by remember(text) { mutableStateOf(false) }
    var hovered by remember(text) { mutableStateOf(false) }
    val tint = if (primary) tc.ac else tc.tx
    Box(
        Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .testTag(tag)
            .onPreviewKeyEvent { event ->
                if (keyboardOpen && isProofreadingActivationKey(event)) {
                    onApply()
                    true
                } else {
                    false
                }
            }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(
                when {
                    focused || hovered -> if (primary) tc.ac.copy(alpha = .22f) else tc.hv
                    primary -> tc.abg
                    else -> tc.bg
                },
                shape,
            )
            .border(if (focused) 1.5.dp else 1.dp, if (focused || primary) tc.ac.copy(alpha = if (focused) 1f else .45f) else tc.br, shape)
            .clickable(onClick = onApply)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        AppText(text, color = tint, fontSize = 11.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal, maxLines = 2)
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
private const val PROOFREADING_HOVER_OPEN_MS = 250L
private const val PROOFREADING_HOVER_CLOSE_MS = 350L
