@file:Suppress("MaxLineLength")
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.VoiceInputSettings
import com.indagium.voice.VoiceAudio
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceInputState
import com.indagium.voice.VoiceLanguageCatalog
import com.indagium.voice.VoiceModelInstallResult
import com.indagium.voice.VoiceRecognitionEngines
import com.indagium.voice.VoiceTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceInputUi(
    val runtime: VoiceInputRuntime,
    val coordinator: VoiceInputSessionCoordinator,
    val settings: VoiceInputSettings,
    val updateSettings: (VoiceInputSettings) -> Unit,
)

internal val LocalVoiceInputUi = staticCompositionLocalOf<VoiceInputUi?> { null }

/** Insert into the latest retained selection, with one replacement operation for undo. */
internal fun insertVoiceTranscript(value: TextFieldValue, transcript: VoiceTranscript): TextFieldValue {
    val trimmed = transcript.text.trim()
    if (trimmed.isEmpty()) return value
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val prefix = value.text.substring(0, start)
    val suffix = value.text.substring(end)
    val spoken = if (startsSentence(prefix)) trimmed.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it } else trimmed
    val firstSpoken = spoken.firstOrNull()
    val lastSpoken = spoken.lastOrNull()
    val separatorBefore = prefix.isNotEmpty() && firstSpoken?.isLetterOrDigit() == true &&
        !prefix.last().isWhitespace() && prefix.last() !in OPENING_PUNCTUATION
    val separatorAfter = suffix.isNotEmpty() && lastSpoken?.isLetterOrDigit() == true &&
        !suffix.first().isWhitespace() && suffix.first() !in CLOSING_PUNCTUATION
    val insertion = buildString {
        if (separatorBefore) append(' ')
        append(spoken)
        if (separatorAfter) append(' ')
    }
    val text = prefix + insertion + suffix
    val caret = prefix.length + insertion.length
    return TextFieldValue(text, TextRange(caret))
}

/** True at the start of the text and after sentence-ending punctuation or a line break, ignoring blanks in between. */
private fun startsSentence(prefix: String): Boolean {
    val before = prefix.trimEnd(' ', '\t')
    return before.isEmpty() || before.last() in SENTENCE_ENDINGS
}

private const val SENTENCE_ENDINGS = ".!?\n\r"
private const val OPENING_PUNCTUATION = "([{\u2018\u201c\"'"
private const val CLOSING_PUNCTUATION = ",.!?;:)]}\u2019\u201d\"'"

enum class DictationPhase { Idle, Recording, Transcribing, Failed }

/**
 * Everything one dictation target shows and does, read by the one [DictationMic] wherever it is drawn (inside a
 * field, in a toolbar or beside a composer) so every placement shows the same state and opens the same menu. Built fresh on every composition
 * by [rememberDictationControl]; the animated clock is read lazily so only the small composables that
 * draw a timer or spinner recompose while recording.
 *
 * A plain state holder with one field per piece of shared state it forwards, so it deliberately has a long constructor.
 */
@Suppress("LongParameterList")
@Stable
class DictationControl internal constructor(
    val targetId: String,
    val phase: DictationPhase,
    val failure: String?,
    private val recordingStartMillis: Long?,
    private val clock: State<Long>,
    val enabled: Boolean,
    val busyForOther: Boolean,
    val settings: VoiceInputSettings,
    val translationSupported: Boolean,
    private val menuOpenState: MutableState<Boolean>,
    private val menuSuppressedUntil: MutableState<Long>,
    private val hoveredState: MutableState<Boolean>,
    private val focusedState: MutableState<Boolean>,
    private val actionPrepared: MutableState<Boolean>,
    private val beforeAction: () -> Unit,
    private val startOrStop: () -> Unit,
    private val updateSettings: (VoiceInputSettings) -> Unit,
    private val restoreFocus: (() -> Unit)?,
) {
    val menuOpen: Boolean get() = menuOpenState.value
    val clickable: Boolean get() = enabled && phase != DictationPhase.Transcribing && !busyForOther
    val fieldActive: Boolean get() = hoveredState.value || focusedState.value || phase != DictationPhase.Idle

    fun elapsedSeconds(): Int {
        val start = recordingStartMillis ?: return 0
        return ((clock.value - start) / MILLIS_PER_SECOND).coerceIn(0L, VoiceAudio.MAX_DURATION_MILLIS / MILLIS_PER_SECOND).toInt()
    }

    fun blinkOn(): Boolean {
        val start = recordingStartMillis ?: return true
        return ((clock.value - start) / DICTATION_BLINK_MS) % 2L == 0L
    }

    fun spinnerStep(): Int = ((clock.value / DICTATION_SPINNER_STEP_MS) % DICTATION_SPINNER_STEPS).toInt()

    /** Text for an empty field: the live status while dictating, otherwise the field's own placeholder. */
    fun placeholder(default: String): String = when (phase) {
        DictationPhase.Recording -> "Listening… ${formatDictationElapsed(elapsedSeconds())}"
        DictationPhase.Transcribing -> "Transcribing…"
        else -> default
    }

    /** Short status shown beside the microphone when the field already holds text. */
    fun endLabel(): String? = when (phase) {
        DictationPhase.Recording -> "● ${formatDictationElapsed(elapsedSeconds())}"
        DictationPhase.Transcribing -> "Transcribing…"
        DictationPhase.Failed -> "Retry"
        DictationPhase.Idle -> null
    }

    fun activate() {
        if (!actionPrepared.value) beforeAction()
        actionPrepared.value = false
        startOrStop()
        restoreFocus?.invoke()
    }

    /** Runs on the press itself, before a focus change can collapse the editor's selection. */
    fun prepareAction() {
        if (!enabled) return
        beforeAction()
        actionPrepared.value = true
    }

    fun cancelPreparedAction() {
        actionPrepared.value = false
    }

    fun setHovered(hovered: Boolean) { hoveredState.value = hovered }

    fun setFocused(focused: Boolean) { focusedState.value = focused }

    fun toggleMenu() {
        // A click on the control that opened the menu also dismisses it (the popup sees the press
        // first), so ignore the toggle that immediately follows a dismissal.
        if (System.currentTimeMillis() < menuSuppressedUntil.value) return
        if (menuOpenState.value) dismissMenu() else openMenu()
    }

    fun openMenu() {
        if (!enabled || phase == DictationPhase.Recording || phase == DictationPhase.Transcribing) return
        menuOpenState.value = true
    }

    fun dismissMenu() {
        menuSuppressedUntil.value = System.currentTimeMillis() + MENU_REOPEN_GUARD_MS
        menuOpenState.value = false
        restoreFocus?.invoke()
    }

    fun selectLanguage(code: String) {
        updateSettings(settings.copy(selectedRecognitionLanguageCode = code))
        dismissMenu()
    }

    fun toggleTranslation() {
        updateSettings(settings.copy(translateToEnglish = !settings.translateToEnglish))
    }

    /** Long form for tooltips: `Auto → English`, `Ukrainian`. */
    fun modeDescription(): String {
        val code = settings.selectedRecognitionLanguageCode
        val language = if (code == "auto") "Auto" else VoiceLanguageCatalog.label(code)
        return if (translatesToEnglish()) "$language → English" else language
    }

    // Translating English speech to English changes nothing, so the arrow would only add noise.
    private fun translatesToEnglish(): Boolean =
        settings.translateToEnglish && translationSupported && settings.selectedRecognitionLanguageCode != "en"
}

private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_PER_SECOND = 1_000L
private const val DICTATION_TICK_MS = 125L
private const val DICTATION_BLINK_MS = 500L
private const val DICTATION_SPINNER_STEP_MS = 125L
private const val DICTATION_SPINNER_STEPS = 8L
private const val MENU_REOPEN_GUARD_MS = 250L

internal fun formatDictationElapsed(seconds: Int): String =
    "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

internal fun dictationLanguageLabel(code: String): String = if (code == "auto") "Detect automatically" else VoiceLanguageCatalog.label(code)

/**
 * Registers [targetId] with the shared coordinator while composed and exposes its state. The target stays
 * registered even when no control is drawn, so a transcript always has somewhere to land. Null without voice input.
 */
@Composable
internal fun rememberDictationControl(
    targetId: String,
    enabled: Boolean,
    onTranscript: (VoiceTranscript) -> Unit,
    onFocusRestored: (() -> Unit)? = null,
    onInteractionActiveChange: (Boolean) -> Unit = {},
    onBeforeAction: () -> Unit = {},
): DictationControl? {
    val ui = LocalVoiceInputUi.current ?: return null
    val coordinator = ui.coordinator
    val snapshot by coordinator.snapshot.collectAsState()
    val ownerHere = snapshot.ownerId == targetId
    val state = if (ownerHere) snapshot.state else VoiceInputState.Idle
    val failure = (state as? VoiceInputState.Failed)?.message
    var installDialogOpen by remember(targetId, ui.runtime) { mutableStateOf(false) }
    val menuOpen = remember(targetId) { mutableStateOf(false) }
    val menuSuppressedUntil = remember(targetId) { mutableStateOf(0L) }
    val hovered = remember(targetId) { mutableStateOf(false) }
    val focused = remember(targetId) { mutableStateOf(false) }
    val actionPrepared = remember(targetId) { mutableStateOf(false) }
    val latestInsert = rememberUpdatedState(onTranscript)
    val latestEnabled = rememberUpdatedState(enabled)
    val latestFocusRestore = rememberUpdatedState(onFocusRestored)
    val latestInteractionCallback = rememberUpdatedState(onInteractionActiveChange)
    val latestBeforeAction = rememberUpdatedState(onBeforeAction)
    val latestUi = rememberUpdatedState(ui)

    val interactionActive = installDialogOpen || menuOpen.value
    LaunchedEffect(interactionActive) { latestInteractionCallback.value(interactionActive) }
    DisposableEffect(targetId) {
        onDispose { latestInteractionCallback.value(false) }
    }

    LaunchedEffect(targetId, enabled) {
        if (!enabled) coordinator.cancel(targetId)
    }

    DisposableEffect(coordinator, targetId) {
        val registration = coordinator.registerTarget(
            id = targetId,
            enabled = { latestEnabled.value },
            insert = { transcript ->
                latestInsert.value.invoke(transcript)
                latestFocusRestore.value?.invoke()
            },
        )
        onDispose { registration.close() }
    }

    val recordingStart = (state as? VoiceInputState.Recording)?.startedAtMillis
    val animating = recordingStart != null || state is VoiceInputState.Transcribing
    val clock = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(animating) {
        while (animating) {
            clock.value = System.currentTimeMillis()
            delay(DICTATION_TICK_MS)
        }
    }

    val phase = when {
        state is VoiceInputState.Recording -> DictationPhase.Recording
        state is VoiceInputState.Transcribing -> DictationPhase.Transcribing
        failure != null -> DictationPhase.Failed
        else -> DictationPhase.Idle
    }
    val control = DictationControl(
        targetId = targetId,
        phase = phase,
        failure = failure,
        recordingStartMillis = recordingStart,
        clock = clock,
        enabled = enabled,
        busyForOther = coordinator.isBusyForOther(targetId),
        settings = ui.settings,
        translationSupported = VoiceRecognitionEngines.supportsTranslation(ui.runtime.engine),
        menuOpenState = menuOpen,
        menuSuppressedUntil = menuSuppressedUntil,
        hoveredState = hovered,
        focusedState = focused,
        actionPrepared = actionPrepared,
        beforeAction = { latestBeforeAction.value() },
        startOrStop = {
            when (state) {
                is VoiceInputState.Recording -> coordinator.stop(targetId)
                VoiceInputState.Transcribing -> Unit
                else -> if (coordinator.start(targetId) == VoiceInputState.ModelRequired) installDialogOpen = true
            }
        },
        updateSettings = { latestUi.value.updateSettings(it) },
        restoreFocus = { latestFocusRestore.value?.invoke() },
    )
    if (installDialogOpen) {
        VoiceModelInstallDialog(
            installer = ui.runtime.modelInstaller,
            onDismiss = { installDialogOpen = false; onFocusRestored?.invoke() },
            onInstalled = { installDialogOpen = false; onFocusRestored?.invoke() },
        )
    }
    return control
}

/** Captures the editor selection on the press itself, before a focus change can collapse it. */
internal fun Modifier.dictationPress(control: DictationControl): Modifier = this
    .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { control.prepareAction() }
    .onPointerEvent(PointerEventType.Exit) { control.cancelPreparedAction() }

/** Tracks hover and focus inside the wrapped field so the in-field microphone can brighten with them. */
internal fun Modifier.dictationActivity(control: DictationControl?): Modifier =
    if (control == null) this else this
        .onPointerEvent(PointerEventType.Enter) { control.setHovered(true) }
        .onPointerEvent(PointerEventType.Exit) { control.setHovered(false) }
        .onFocusChanged { control.setFocused(it.hasFocus) }

private fun DictationControl.semanticDescription(): String = when (phase) {
    DictationPhase.Recording -> "Stop voice input"
    DictationPhase.Transcribing -> "Transcribing voice input"
    else -> "Voice input"
}

private fun DictationControl.tooltip(): String = when (phase) {
    DictationPhase.Recording -> "Stop dictation · Esc cancels"
    DictationPhase.Transcribing -> "Transcribing…"
    DictationPhase.Failed -> "$failure Click to retry."
    DictationPhase.Idle -> "Dictate · ${modeDescription()}"
}

/** The muted second tooltip line: how to reach the language and translation menu. Only while the microphone is idle. */
private fun DictationControl.tooltipDetail(): String? =
    if (phase == DictationPhase.Idle) "Right-click for language and translation" else null

/** A small arc that turns in discrete steps; driven by the shared clock instead of an infinite animation. */
@Composable
private fun DictationSpinner(control: DictationControl, color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        drawArc(
            color = color,
            startAngle = control.spinnerStep() * SPINNER_STEP_DEGREES,
            sweepAngle = SPINNER_SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private const val SPINNER_STEP_DEGREES = 45f
private const val SPINNER_SWEEP_DEGREES = 250f

/** Places the menu under its anchor's right edge, flipping above when the window has no room below. */
internal class DictationMenuPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(marginPx, maxX)
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val fitsBelow = below + popupContentSize.height <= windowSize.height - marginPx
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        return IntOffset(x, (if (fitsBelow || above < marginPx) below else above).coerceIn(marginPx, maxY))
    }
}

/** The spoken-language picker and the translate-to-English switch, opened by right-clicking any [DictationMic]. */
@Composable
private fun DictationMenuPopup(control: DictationControl) {
    val tc = tc()
    val density = LocalDensity.current
    val provider = remember(density) {
        DictationMenuPositionProvider(
            gapPx = with(density) { 4.dp.roundToPx() },
            marginPx = with(density) { 8.dp.roundToPx() },
        )
    }
    val shape = RoundedCornerShape(8.dp)
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = control::dismissMenu,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(DICTATION_MENU_WIDTH).testTag("dictation-menu").background(tc.p, shape).border(1.dp, tc.br, shape)
                .padding(vertical = 6.dp),
        ) {
            AppText("Spoken language", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
            control.settings.recognitionLanguageCodes.forEach { code ->
                val selected = code == control.settings.selectedRecognitionLanguageCode
                MenuRow(
                    selected = selected,
                    onClick = { control.selectLanguage(code) },
                    modifier = Modifier.testTag("dictation-language-$code"),
                ) {
                    Box(Modifier.width(14.dp)) { if (selected) AppText("✓", color = tc.ac, fontSize = 11.sp) }
                    AppText(
                        dictationLanguageLabel(code),
                        color = if (selected) tc.ac else tc.tx,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (control.translationSupported) {
                Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(1.dp).background(tc.br))
                val on = control.settings.translateToEnglish
                MenuRow(
                    selected = false,
                    onClick = control::toggleTranslation,
                    modifier = Modifier.testTag("dictation-translate-toggle").semantics { stateDescription = if (on) "On" else "Off" },
                ) {
                    AppText("Translate to English", color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    DictationSwitch(on)
                }
            }
            Box(Modifier.padding(top = 5.dp, bottom = 3.dp).fillMaxWidth().height(1.dp).background(tc.br))
            AppText("Runs on this device · Esc cancels", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
}

private val DICTATION_MENU_WIDTH = 224.dp

@Composable
private fun MenuRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(CORNER_SM),
        baseBg = if (selected) tc.abg else Color.Transparent,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun DictationSwitch(on: Boolean) {
    val tc = tc()
    Box(
        Modifier.size(width = 26.dp, height = 14.dp)
            .background(if (on) tc.ac else tc.br, CircleShape)
            .padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(10.dp).background(if (on) Color.White else tc.td, CircleShape))
    }
}

/**
 * The one dictation microphone, a round icon button. Inside a prose field it sits at the bottom-right corner (always laid
 * out, so focusing the field moves nothing, muted at rest and brighter while the field is hovered or focused); as a
 * [standalone] button in a toolbar or beside a composer it keeps its normal tint. Left-click starts or stops, right-click
 * opens the language menu. Not focusable, so clicking it never takes the caret away from the text.
 */
@Composable
internal fun DictationMic(
    control: DictationControl,
    modifier: Modifier = Modifier,
    size: Dp = INLINE_MIC_SIZE,
    standalone: Boolean = false,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val phase = control.phase
    val tint = when {
        phase == DictationPhase.Recording || phase == DictationPhase.Failed -> DANGER_RED
        phase == DictationPhase.Transcribing -> tc.td
        !control.enabled -> tc.td.copy(alpha = .4f)
        hovered -> tc.tx
        standalone || control.fieldActive -> tc.ts
        else -> tc.td.copy(alpha = .55f)
    }
    val background = when {
        phase == DictationPhase.Recording -> DANGER_RED.copy(alpha = .16f)
        hovered && control.clickable -> tc.hv
        else -> Color.Transparent
    }
    val iconSize = (size.value * MIC_ICON_RATIO).dp
    Box(modifier) {
        TooltipArea(
            tooltip = {
                Box(Modifier.testTag("dictation-tooltip")) { ToolbarTooltip(control.tooltip(), maxLines = 3, detail = control.tooltipDetail()) }
            },
        ) {
            Box(
                Modifier.size(size).clip(CircleShape).background(background, CircleShape)
                    .semantics {
                        contentDescription = control.semanticDescription()
                        if (control.failure != null) stateDescription = control.failure
                    }
                    .testTag("voice-input-${control.targetId}")
                    .dictationPress(control)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
                    .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) control.openMenu() }
                    .then(
                        if (control.clickable) Modifier.focusProperties { canFocus = false }.clickable(onClick = control::activate)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    DictationPhase.Transcribing -> DictationSpinner(control, tint)
                    DictationPhase.Recording -> Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
                    else -> Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
                }
            }
        }
        if (control.menuOpen) DictationMenuPopup(control)
    }
}

private const val MIC_ICON_RATIO = 0.64f

internal val INLINE_MIC_SIZE = 22.dp

/** Every prose field puts its microphone this far from the trailing edge, so they all line up. */
internal val INLINE_MIC_END_PADDING = 4.dp

/** Width the field's text must stay clear of so the microphone never covers a character. */
internal val INLINE_MIC_TEXT_INSET = 30.dp

/**
 * Chrome that rides on top of a prose field: the danger border while recording, a status label beside the
 * microphone when the field already holds text, and the microphone itself. Everything is positioned absolutely,
 * so nothing here ever changes the field's size.
 */
@Composable
internal fun BoxScope.DictationFieldOverlay(
    control: DictationControl,
    hasText: Boolean,
    singleLine: Boolean,
    shape: Shape,
) {
    val tc = tc()
    if (control.phase == DictationPhase.Recording) Box(Modifier.matchParentSize().border(1.dp, DANGER_RED, shape))
    val alignment = if (singleLine) Alignment.CenterEnd else Alignment.BottomEnd
    val bottom = if (singleLine) 0.dp else 3.dp
    val label = control.endLabel()?.takeIf { hasText || control.phase == DictationPhase.Failed }
    if (label != null) AppText(
        label,
        color = if (control.phase == DictationPhase.Failed) DANGER_RED else tc.td,
        fontSize = 10.sp,
        modifier = Modifier.align(alignment)
            .padding(end = INLINE_MIC_END_PADDING + INLINE_MIC_SIZE + 2.dp, bottom = bottom)
            .background(tc.bg.copy(alpha = .92f), CORNER_SM)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
    DictationMic(control, Modifier.align(alignment).padding(end = INLINE_MIC_END_PADDING, bottom = bottom))
}

/**
 * Live dictation status for a footer line that carries no microphone of its own: `· ● Listening… 0:07` in the danger
 * colour while recording, `· Transcribing…` while the model works and the failure message after a failed attempt.
 * Draws nothing at rest. Emits into the caller's Row, and reads the animated clock here so only this small scope
 * recomposes while it ticks.
 */
@Composable
internal fun DictationFooterStatus(control: DictationControl?, fontFamily: FontFamily, fontSize: TextUnit = 10.sp) {
    val tc = tc()
    val phase = control?.phase ?: return
    if (phase == DictationPhase.Idle) return
    AppText("·", color = tc.td, fontSize = fontSize, fontFamily = fontFamily)
    when (phase) {
        DictationPhase.Recording -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("●", color = DANGER_RED.copy(alpha = if (control.blinkOn()) 1f else .3f), fontSize = fontSize, fontFamily = fontFamily)
            AppText("Listening… ${formatDictationElapsed(control.elapsedSeconds())}", color = DANGER_RED, fontSize = fontSize, fontFamily = fontFamily)
        }
        DictationPhase.Transcribing -> AppText("Transcribing…", color = tc.td, fontSize = fontSize, fontFamily = fontFamily)
        else -> AppText(
            control.failure ?: "Voice input failed",
            color = DANGER_RED,
            fontSize = fontSize,
            fontFamily = fontFamily,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

/** Escape cancels capture/transcription before the enclosing editor handles its own Escape action. */
@Composable
internal fun Modifier.cancelVoiceOnEscape(targetId: String): Modifier {
    val coordinator = LocalVoiceInputUi.current?.coordinator ?: return this
    return onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && coordinator.isOwner(targetId)) {
            coordinator.cancel(targetId)
            true
        } else {
            false
        }
    }
}

/** Explicit consent boundary: downloading the local Whisper model is the only voice network request. */
@Composable
internal fun VoiceModelInstallDialog(
    installer: com.indagium.voice.VoiceModelInstaller,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    val colors = tc()
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var failure by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!installing) onDismiss() }) {
        Column(
            Modifier.width(430.dp).background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Download local voice model", color = colors.tx, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            AppText(
                "Voice recordings and transcription stay on this device. Downloading the multilingual " +
                    "${installer.descriptor.id.removePrefix("whisper-")} model (${formatByteSize(installer.descriptor.sizeBytes)}) " +
                    "is the only network step.",
                color = colors.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            AppText(
                "The selected recognition language and translation mode are applied locally. Dictated text remains editable.",
                color = colors.td,
                fontSize = 11.sp,
                maxLines = 3,
            )
            if (installing) AppText("Downloading ${formatByteSize(downloadedBytes)}…", color = colors.ac, fontSize = 11.sp)
            failure?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, enabled = !installing)
                AppButton(
                    if (installing) "Downloading…" else "Download local model",
                    onClick = {
                        installing = true
                        failure = null
                        scope.launch {
                            when (val result = withContext(Dispatchers.IO) { installer.install { downloadedBytes = it } }) {
                                is VoiceModelInstallResult.Installed, is VoiceModelInstallResult.AlreadyInstalled -> onInstalled()
                                is VoiceModelInstallResult.Failure -> { installing = false; failure = result.message }
                            }
                        }
                    },
                    variant = ButtonVariant.Primary,
                    enabled = !installing,
                )
            }
        }
    }
}
