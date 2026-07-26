package com.openlog.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates capture and recognizer work. It is deliberately independent from Compose and AI
 * providers; the UI observes [state], calls [consumeTranscript], and appends it to its own field.
 */
class VoiceInputController(
    private val hasInstalledModel: () -> Boolean,
    private val capture: VoiceCapture,
    private val transcriber: VoiceTranscriber,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val optionsProvider: () -> VoiceTranscriptionOptions = { VoiceTranscriptionOptions() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStateChanged: (VoiceInputState) -> Unit = {},
) {
    private var activeSession: VoiceCaptureSession? = null
    private var requestGeneration = 0L

    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()

    fun startRecording(): VoiceInputState {
        if (activeSession != null || state.value is VoiceInputState.Transcribing) return state.value
        if (!hasInstalledModel()) return transition(VoiceInputState.ModelRequired)
        return when (val start = capture.start()) {
            is VoiceCaptureStartResult.Failure -> transition(VoiceInputState.Failed(start.message))
            is VoiceCaptureStartResult.Started -> {
                activeSession = start.session
                transition(VoiceInputState.Recording(nowMillis()))
            }
        }
    }

    /**
     * Stops capture and starts local recognition on [dispatcher]. The returned [Job] is useful to
     * tests and to a UI that wants to await shutdown; it never sends the resulting text.
     */
    fun stopAndTranscribe(translateToEnglish: Boolean? = null): Job? {
        val session = activeSession ?: return null
        activeSession = null
        val generation = ++requestGeneration
        transition(VoiceInputState.Transcribing)
        return scope.launch(dispatcher) {
            val result = session.stop()
            if (generation != requestGeneration) return@launch
            when (result) {
                is VoiceCaptureResult.Captured -> {
                    if (result.audio.isSilentOrEmpty()) transitionIfCurrent(generation, VoiceInputState.Failed("No speech was captured. Try again."))
                    else transcribe(generation, result.audio, translateToEnglish)
                }
                VoiceCaptureResult.Cancelled -> transitionIfCurrent(generation, idleOrModelRequired())
                VoiceCaptureResult.TimedOut -> transitionIfCurrent(generation, VoiceInputState.Failed("Recording stopped after 90 seconds. Try a shorter request."))
                is VoiceCaptureResult.Failure -> transitionIfCurrent(generation, VoiceInputState.Failed(result.message))
            }
        }
    }

    fun cancel() {
        requestGeneration++
        activeSession?.cancel()
        activeSession = null
        transition(idleOrModelRequired())
    }

    /** Returns the result once, which prevents a stale transcript being inserted twice. */
    fun consumeTranscript(): VoiceTranscript? {
        val ready = state.value as? VoiceInputState.TranscriptReady ?: return null
        transition(idleOrModelRequired())
        return ready.transcript
    }

    private fun transcribe(generation: Long, audio: VoiceAudio, translateToEnglish: Boolean?) {
        val result = try {
            val configured = optionsProvider()
            transcriber.transcribe(
                audio,
                if (translateToEnglish == null) configured else configured.copy(translateToEnglish = translateToEnglish),
            )
        } catch (error: Exception) {
            VoiceTranscriptionResult.Failure("Local transcription failed. Try again.", error)
        }
        if (generation != requestGeneration) return
        when (result) {
            is VoiceTranscriptionResult.Success -> {
                if (result.transcript.text.isBlank()) transitionIfCurrent(generation, VoiceInputState.Failed("No speech was recognized. Try again."))
                else transitionIfCurrent(generation, VoiceInputState.TranscriptReady(result.transcript))
            }
            is VoiceTranscriptionResult.Failure -> transitionIfCurrent(generation, VoiceInputState.Failed(result.message))
        }
    }

    private fun idleOrModelRequired(): VoiceInputState = if (hasInstalledModel()) VoiceInputState.Idle else VoiceInputState.ModelRequired

    private fun transitionIfCurrent(generation: Long, next: VoiceInputState) {
        if (generation == requestGeneration) transition(next)
    }

    private fun transition(next: VoiceInputState): VoiceInputState {
        mutableState.value = next
        onStateChanged(next)
        return next
    }
}
