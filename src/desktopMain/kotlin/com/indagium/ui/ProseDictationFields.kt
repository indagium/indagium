@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.voice.VoiceTranscript

/**
 * Everything a plain `String` field needs to take dictation: the draft with its caret, the retained selection (so a
 * transcript replaces what was highlighted even after the click on the microphone collapsed it), a focus requester and
 * the [DictationControl]. Used by [DictatedTextArea] and [DictatedInlineField]; the notes and test-library editors keep
 * their own, richer, wiring.
 */
@Stable
internal class ProseDictation(
    val state: MutableState<TextFieldValue>,
    /** Null when voice input is unavailable; the field then simply has no microphone. */
    val control: DictationControl?,
    /** Focus tracking, retained-selection bookkeeping, the focus requester and Escape-cancels-dictation. */
    val fieldModifier: Modifier,
    /** Records a new editor value, keeping the retained selection honest. */
    val accept: (TextFieldValue) -> Unit,
)

/**
 * Registers [targetId] as a dictation target for a field holding [value] and returns what the field must wire in. The
 * transcript lands through [onValue], like typed text, so callers need no knowledge of voice input at all.
 */
@Composable
internal fun rememberProseDictation(
    targetId: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean = true,
): ProseDictation {
    val state = remember(targetId) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var retainedSelection by remember(targetId) { mutableStateOf<TextRange?>(null) }
    var lastNonCollapsedSelection by remember(targetId) { mutableStateOf<TextRange?>(null) }
    var focused by remember(targetId) { mutableStateOf(false) }
    val focusRequester = remember(targetId) { FocusRequester() }
    val latestOnValue = rememberUpdatedState(onValue)
    val latestEnabled = rememberUpdatedState(enabled)

    // The caller's String stays the source of truth: an outside write (a reset, a loaded draft) replaces the draft.
    LaunchedEffect(value) {
        if (value != state.value.text) {
            state.value = TextFieldValue(value, TextRange(value.length))
            retainedSelection = null
            lastNonCollapsedSelection = null
        }
    }

    fun accept(updated: TextFieldValue) {
        if (updated.text != state.value.text) {
            retainedSelection = null
            lastNonCollapsedSelection = null
        } else if (updated.selection.start != updated.selection.end) {
            retainedSelection = null
            lastNonCollapsedSelection = updated.selection
        } else if (lastNonCollapsedSelection != null && retainedSelection == null) {
            // Compose may collapse a selection as focus moves from the field to the microphone. Keep the last real range
            // until a pointer, key or text edit proves the user moved it.
            retainedSelection = lastNonCollapsedSelection
        }
        state.value = updated
    }

    fun insertTranscript(transcript: VoiceTranscript) {
        if (!latestEnabled.value) return
        val base = retainedSelection?.let { state.value.copy(selection = it) } ?: state.value
        val updated = insertVoiceTranscript(base, transcript)
        state.value = updated
        retainedSelection = null
        lastNonCollapsedSelection = null
        latestOnValue.value(updated.text)
    }

    val control = rememberDictationControl(
        targetId = targetId,
        enabled = enabled,
        onTranscript = ::insertTranscript,
        onBeforeAction = { if (retainedSelection == null) retainedSelection = state.value.selection },
        onFocusRestored = { runCatching { focusRequester.requestFocus() } },
    )
    val fieldModifier = Modifier
        .onFocusChanged {
            if (focused && !it.hasFocus && retainedSelection == null) {
                retainedSelection = state.value.selection.takeIf { selection -> selection.start != selection.end }
                    ?: lastNonCollapsedSelection
            }
            focused = it.hasFocus
        }
        .onPointerEvent(PointerEventType.Press) {
            retainedSelection = null
            lastNonCollapsedSelection = null
        }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key != Key.Escape) {
                retainedSelection = null
                lastNonCollapsedSelection = null
            }
            false
        }
        .focusRequester(focusRequester)
        .cancelVoiceOnEscape(targetId)
    return ProseDictation(state, control, fieldModifier, ::accept)
}

/**
 * A [ScrollableTextArea] for prose a person writes (an AI prompt, a note, an issue text) with the in-field microphone.
 * [proseTargetId] is the stable domain id of the field; [modifier] sizes the whole field, microphone included.
 */
@Composable
internal fun DictatedTextArea(
    value: String,
    onValue: (String) -> Unit,
    proseTargetId: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    minHeight: Dp = 0.dp,
    maxHeight: Dp,
    resetKey: Any? = null,
    shape: Shape = CORNER_SM,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val prose = rememberProseDictation(proseTargetId, value, onValue, enabled)
    Box(modifier.fillMaxWidth().focusGroup()) {
        ScrollableTextArea(
            value = value,
            onValue = onValue,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().then(prose.fieldModifier),
            fontSize = fontSize,
            lineHeight = lineHeight,
            minHeight = minHeight,
            maxHeight = maxHeight,
            resetKey = resetKey,
            shape = shape,
            borderColor = borderColor,
            contentPadding = contentPadding,
            enabled = enabled,
            onClear = onClear,
            textFieldValueState = prose.state,
            onTextFieldValueChange = prose.accept,
            dictation = prose.control,
        )
    }
}

/** A single-line [InlineField] for prose, with the in-field microphone. [modifier] sizes the whole field. */
@Composable
internal fun DictatedInlineField(
    value: String,
    onValue: (String) -> Unit,
    proseTargetId: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fontSize: TextUnit = 12.sp,
    onSubmit: (() -> Unit)? = null,
) {
    val prose = rememberProseDictation(proseTargetId, value, onValue)
    val control = prose.control
    Box(modifier.fillMaxWidth().dictationActivity(control).focusGroup()) {
        InlineField(
            value = prose.state.value,
            onValue = {
                prose.accept(it)
                onValue(it.text)
            },
            placeholder = control?.placeholder(placeholder) ?: placeholder,
            modifier = Modifier.fillMaxWidth().then(prose.fieldModifier),
            fontSize = fontSize,
            onSubmit = onSubmit,
            contentEndPadding = if (control != null) INLINE_MIC_TEXT_INSET else 7.dp,
        )
        if (control != null) DictationFieldOverlay(
            control = control,
            hasText = value.isNotEmpty(),
            singleLine = true,
            shape = CORNER_SM,
        )
    }
}
