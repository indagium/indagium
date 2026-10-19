package com.indagium.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.Closeable

/** A single app-wide recording/transcription owner. Target IDs are domain identities, never list positions. */
class VoiceInputSessionCoordinator(
    private val controller: VoiceInputController,
    private val scope: CoroutineScope,
) : Closeable {
    private data class Target(
        val token: Long,
        val enabled: () -> Boolean,
        val insert: (VoiceTranscript) -> Unit,
    )

    data class Snapshot(val ownerId: String?, val state: VoiceInputState)

    private val lock = Any()
    private val targets = mutableMapOf<String, Target>()
    private var nextToken = 0L
    private var activeOwnerId: String? = null
    private var activeOwnerToken: Long? = null
    private var lastOwnerId: String? = null
    private var closed = false
    private val mutableSnapshot = MutableStateFlow(Snapshot(null, controller.state.value))
    val snapshot: StateFlow<Snapshot> = mutableSnapshot.asStateFlow()
    private val collector: Job = scope.launch {
        controller.stateEvent.collect(::onControllerState)
    }

    /** Register while the editor exists; disposing the handle cancels a session owned by it. */
    fun registerTarget(
        id: String,
        enabled: () -> Boolean = { true },
        insert: (VoiceTranscript) -> Unit,
    ): Closeable {
        require(id.isNotBlank())
        val token = synchronized(lock) {
            check(!closed) { "Voice dictation coordinator is closed" }
            if (activeOwnerId == id) {
                // Reusing the same domain ID after the editor instance was replaced must not
                // transfer an in-flight transcript to the replacement draft.
                activeOwnerId = null
                activeOwnerToken = null
                controller.cancel()
            }
            (++nextToken).also { targets[id] = Target(it, enabled, insert) }
        }
        return Closeable { unregister(id, token) }
    }

    /** Starts only for a live, enabled target. A second editor cannot take over the microphone. */
    fun start(id: String): VoiceInputState = synchronized(lock) {
        if (closed) return controller.state.value
        val target = targets[id] ?: return VoiceInputState.Failed("This editor is no longer available.")
        if (!target.enabled()) return VoiceInputState.Failed("This editor is read-only.")
        if (activeOwnerId != null && activeOwnerId != id) return controller.state.value
        activeOwnerId = id
        activeOwnerToken = target.token
        lastOwnerId = id
        val next = controller.startRecording()
        if (next !is VoiceInputState.Recording && next !is VoiceInputState.Transcribing) {
            activeOwnerId = null
            activeOwnerToken = null
        }
        publish(next)
        next
    }

    fun stop(id: String): Job? = synchronized(lock) {
        if (activeOwnerId != id) return null
        controller.stopAndTranscribe()
    }

    fun cancel(id: String) {
        synchronized(lock) {
            if (activeOwnerId != id && lastOwnerId != id) return
            activeOwnerId = null
            activeOwnerToken = null
            lastOwnerId = id
            controller.cancel()
            publish(controller.state.value)
        }
    }

    fun isBusyForOther(id: String): Boolean = synchronized(lock) {
        activeOwnerId != null && activeOwnerId != id
    }

    fun isOwner(id: String): Boolean = synchronized(lock) { activeOwnerId == id }

    private fun onControllerState(event: VoiceInputStateEvent) {
        synchronized(lock) {
            if (closed) return
            if (controller.stateEvent.value.revision != event.revision) return
            val next = event.state
            if (next is VoiceInputState.TranscriptReady) {
                val owner = activeOwnerId
                val target = owner?.let(targets::get)?.takeIf { it.token == activeOwnerToken }
                // Consume and insert while holding the ownership lock. A cancel or new owner cannot
                // interleave between the generation check and delivery to a reused editor identity.
                if (owner != null && target != null && runCatching(target.enabled).getOrDefault(false)) {
                    controller.consumeTranscript()?.let { transcript ->
                        runCatching { target.insert(transcript) }
                    }
                } else {
                    controller.cancel()
                }
                activeOwnerId = null
                activeOwnerToken = null
                publish(controller.state.value)
                return
            }
            if (next is VoiceInputState.Failed || next == VoiceInputState.Idle || next == VoiceInputState.ModelRequired) {
                activeOwnerId = null
                activeOwnerToken = null
            }
            publish(next)
        }
    }

    private fun unregister(id: String, token: Long) {
        synchronized(lock) {
            if (targets[id]?.token != token) return
            targets.remove(id)
            if (activeOwnerId == id) {
                activeOwnerId = null
                activeOwnerToken = null
                controller.cancel()
                publish(controller.state.value)
            }
        }
    }

    private fun publish(state: VoiceInputState) {
        mutableSnapshot.value = Snapshot(activeOwnerId ?: lastOwnerId, state)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeOwnerId = null
            activeOwnerToken = null
            targets.clear()
            controller.cancel()
            mutableSnapshot.value = Snapshot(null, controller.state.value)
        }
        collector.cancel()
    }
}
