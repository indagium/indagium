package com.indagium.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercises editor replacement while recognizer delivery is queued, without a real microphone. */
class VoiceInputSessionCoordinatorRegressionTest {
    @Test
    fun replacingRegistrationCannotReceiveThePreviousEditorsTranscript() = withFixture { fixture ->
        val first = mutableListOf<String>()
        val replacement = mutableListOf<String>()
        fixture.coordinator.registerTarget("note:tab:block") { first += it.text }
        fixture.coordinator.start("note:tab:block")
        fixture.dispatcher.drain()
        fixture.coordinator.stop("note:tab:block")

        fixture.coordinator.registerTarget("note:tab:block") { replacement += it.text }
        fixture.dispatcher.drain()

        assertTrue(first.isEmpty(), "a disposed editor must not receive queued text")
        assertTrue(replacement.isEmpty(), "reusing a domain ID must not inherit the previous registration's transcript")
    }

    @Test
    fun disposingOldRegistrationDoesNotCancelReplacementRecording() = withFixture { fixture ->
        val first = fixture.coordinator.registerTarget("test:case:step:action") { }
        fixture.coordinator.start("test:case:step:action")
        fixture.dispatcher.drain()
        fixture.coordinator.registerTarget("test:case:step:action") { }
        fixture.coordinator.start("test:case:step:action")
        first.close()
        fixture.dispatcher.drain()

        assertIs<VoiceInputState.Recording>(fixture.coordinator.snapshot.value.state)
    }

    @Test
    fun cancelThenRecordInAnotherEditorDropsTheQueuedTranscript() = withFixture { fixture ->
        val first = mutableListOf<String>()
        val next = mutableListOf<String>()
        fixture.coordinator.registerTarget("note:first") { first += it.text }
        fixture.coordinator.registerTarget("note:next") { next += it.text }
        fixture.coordinator.start("note:first")
        fixture.dispatcher.drain()
        fixture.coordinator.stop("note:first")
        fixture.coordinator.cancel("note:first")
        fixture.coordinator.start("note:next")
        fixture.dispatcher.drain()

        assertTrue(first.isEmpty())
        assertTrue(next.isEmpty())
        assertEquals("note:next", fixture.coordinator.snapshot.value.ownerId)
        assertIs<VoiceInputState.Recording>(fixture.coordinator.snapshot.value.state)

        fixture.coordinator.stop("note:next")
        fixture.dispatcher.drain()
        assertEquals(listOf("transcript"), next)
        assertTrue(first.isEmpty())
    }

    @Test
    fun targetLockedBeforeQueuedDeliveryReceivesNothing() = withFixture { fixture ->
        var enabled = true
        val inserted = mutableListOf<String>()
        fixture.coordinator.registerTarget("note:locked", enabled = { enabled }) { inserted += it.text }
        fixture.coordinator.start("note:locked")
        fixture.dispatcher.drain()
        fixture.coordinator.stop("note:locked")
        enabled = false
        fixture.dispatcher.drain()

        assertTrue(inserted.isEmpty())
        assertTrue(!fixture.coordinator.isBusyForOther("another"))
    }

    @Test
    fun secondEditorCannotStartOrStopTheFirstEditorsCapture() = withFixture { fixture ->
        fixture.coordinator.registerTarget("note:first") { }
        fixture.coordinator.registerTarget("note:other") { }
        fixture.coordinator.start("note:first")
        fixture.dispatcher.drain()

        fixture.coordinator.start("note:other")
        fixture.coordinator.stop("note:other")
        fixture.dispatcher.drain()

        assertEquals(1, fixture.capture.starts)
        assertEquals("note:first", fixture.coordinator.snapshot.value.ownerId)
        assertIs<VoiceInputState.Recording>(fixture.coordinator.snapshot.value.state)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        Fixture().use(test)
    }

    private class Fixture : Closeable {
        val dispatcher = QueuedDispatcher()
        val capture = FakeCapture()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val recognitionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = VoiceInputSessionCoordinator(
            VoiceInputController(
                hasInstalledModel = { true },
                capture = capture,
                transcriber = VoiceTranscriber { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("transcript")) },
                scope = recognitionScope,
                dispatcher = Dispatchers.Unconfined,
            ),
            scope,
        )

        override fun close() {
            coordinator.close()
            scope.cancel()
            recognitionScope.cancel()
            dispatcher.drain()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class FakeCapture : VoiceCapture {
        var starts = 0

        override fun start(): VoiceCaptureStartResult {
            starts++
            return VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                override fun stop(): VoiceCaptureResult = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(1, 0)))

                override fun cancel() = Unit
            })
        }
    }
}
