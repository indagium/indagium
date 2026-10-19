@file:Suppress("MaxLineLength")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.VoiceInputSettings
import com.indagium.voice.VoiceInputControllerFactory
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceInputState
import com.indagium.voice.VoiceLanguageCatalog
import com.indagium.voice.VoiceModelInstallResult
import com.indagium.voice.VoiceRecognitionEngines
import com.indagium.voice.VoiceTranscript
import kotlinx.coroutines.Dispatchers
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
    val spoken = transcript.text.trim()
    if (spoken.isEmpty()) return value
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val prefix = value.text.substring(0, start)
    val suffix = value.text.substring(end)
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

private const val OPENING_PUNCTUATION = "([{\u2018\u201c\"'"
private const val CLOSING_PUNCTUATION = ",.!?;:)]}\u2019\u201d\"'"

/** A compact microphone button for a prose field. The target remains registered only while it is composed. */
@Composable
internal fun VoiceDictationButton(
    targetId: String,
    enabled: Boolean,
    onTranscript: (VoiceTranscript) -> Unit,
    onFocusRestored: (() -> Unit)? = null,
    visible: Boolean = true,
    onInteractionActiveChange: (Boolean) -> Unit = {},
    onBeforeAction: () -> Unit = {},
) {
    val ui = LocalVoiceInputUi.current ?: return
    val coordinator = ui.coordinator
    val snapshot by coordinator.snapshot.collectAsState()
    val ownerHere = snapshot.ownerId == targetId
    val recordingHere = ownerHere && snapshot.state is VoiceInputState.Recording
    val transcribingHere = ownerHere && snapshot.state is VoiceInputState.Transcribing
    val failureMessage = (snapshot.state as? VoiceInputState.Failed)?.message?.takeIf { ownerHere }
    var installDialogOpen by remember(targetId, ui.runtime) { mutableStateOf(false) }
    val latestInsert = rememberUpdatedState(onTranscript)
    val latestEnabled = rememberUpdatedState(enabled)
    val latestFocusRestore = rememberUpdatedState(onFocusRestored)
    val latestInteractionCallback = rememberUpdatedState(onInteractionActiveChange)
    var actionPrepared by remember(targetId) { mutableStateOf(false) }

    LaunchedEffect(installDialogOpen) { latestInteractionCallback.value(installDialogOpen) }
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

    if (visible || recordingHere || transcribingHere || installDialogOpen) AppButton(
        label = when {
            recordingHere -> "Stop"
            transcribingHere -> "…"
            failureMessage != null -> "Retry"
            else -> ""
        },
        onClick = {
            if (!actionPrepared) onBeforeAction()
            actionPrepared = false
            when {
                recordingHere -> coordinator.stop(targetId)
                transcribingHere -> Unit
                else -> {
                    val next = coordinator.start(targetId)
                    if (next == VoiceInputState.ModelRequired) installDialogOpen = true
                }
            }
            onFocusRestored?.invoke()
        },
        variant = if (recordingHere) ButtonVariant.Primary else ButtonVariant.Ghost,
        isDanger = recordingHere || failureMessage != null,
        enabled = enabled && !transcribingHere && !coordinator.isBusyForOther(targetId),
        modifier = Modifier
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                if (enabled) {
                    onBeforeAction()
                    actionPrepared = true
                }
            }
            .onPointerEvent(PointerEventType.Exit) { actionPrepared = false }
            .semantics {
            contentDescription = when {
                recordingHere -> "Stop voice input"
                transcribingHere -> "Transcribing voice input"
                else -> "Voice input"
            }
            if (failureMessage != null) stateDescription = failureMessage
        }.testTag("voice-input-$targetId"),
        leadingIcon = if (recordingHere) Icons.Outlined.Stop else Icons.Outlined.Mic,
        horizontalPadding = 6.dp,
        tooltip = when {
            recordingHere -> "Stop local voice dictation"
            transcribingHere -> "Transcribing locally"
            failureMessage != null -> "$failureMessage Click to retry local dictation."
            else -> "Dictate into this field locally"
        },
    )
    if (installDialogOpen) {
        VoiceModelInstallDialog(
            installer = ui.runtime.modelInstaller,
            onDismiss = { installDialogOpen = false; onFocusRestored?.invoke() },
            onInstalled = { installDialogOpen = false; onFocusRestored?.invoke() },
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

/** Reusable global language/translation controls shared by the AI composer and prose editors. */
@Composable
internal fun VoiceLanguageControls(
    enabled: Boolean,
    onRestoreFocus: () -> Unit = {},
    onInteractionActiveChange: (Boolean) -> Unit = {},
) {
    val ui = LocalVoiceInputUi.current ?: return
    val settings = ui.settings
    val busy by ui.coordinator.snapshot.collectAsState()
    val recording = busy.state is VoiceInputState.Recording
    val transcribing = busy.state is VoiceInputState.Transcribing
    val supportsTranslation = VoiceRecognitionEngines.supportsTranslation(ui.runtime.engine)
    var languageMenuOpen by remember { mutableStateOf(false) }
    val latestInteractionCallback = rememberUpdatedState(onInteractionActiveChange)
    LaunchedEffect(languageMenuOpen) { latestInteractionCallback.value(languageMenuOpen) }
    DisposableEffect(Unit) {
        onDispose { latestInteractionCallback.value(false) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (supportsTranslation) AppButton(
            "EN",
            onClick = {
                ui.updateSettings(settings.copy(translateToEnglish = !settings.translateToEnglish))
                onRestoreFocus()
            },
            variant = if (settings.translateToEnglish) ButtonVariant.Primary else ButtonVariant.Secondary,
            enabled = enabled && !transcribing,
            horizontalPadding = 7.dp,
            tooltip = "Translate dictated speech to English locally. Turn off to keep the spoken language.",
        )
        Box {
            AppButton(
                if (settings.selectedRecognitionLanguageCode == "auto") "Auto" else settings.selectedRecognitionLanguageCode.uppercase(),
                onClick = { languageMenuOpen = !languageMenuOpen },
                variant = ButtonVariant.Secondary,
                enabled = enabled && !recording && !transcribing,
                horizontalPadding = 7.dp,
                tooltip = "Recognition language: ${VoiceLanguageCatalog.label(settings.selectedRecognitionLanguageCode)}",
            )
            if (languageMenuOpen) Popup(
                alignment = Alignment.BottomStart,
                onDismissRequest = { languageMenuOpen = false; onRestoreFocus() },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.background(tc().p, CORNER_SM).border(1.dp, tc().br, CORNER_SM).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    settings.recognitionLanguageCodes.forEach { code ->
                        val selected = code == settings.selectedRecognitionLanguageCode
                        HoverBox(
                            modifier = Modifier.padding(horizontal = 2.dp),
                            baseBg = if (selected) tc().abg else androidx.compose.ui.graphics.Color.Transparent,
                            onClick = {
                                ui.updateSettings(settings.copy(selectedRecognitionLanguageCode = code))
                                languageMenuOpen = false
                                onRestoreFocus()
                            },
                        ) {
                            AppText(VoiceLanguageCatalog.label(code), color = if (selected) tc().ac else tc().tx, fontSize = 11.sp)
                        }
                    }
                }
            }
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
