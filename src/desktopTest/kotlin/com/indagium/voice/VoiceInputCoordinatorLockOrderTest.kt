package com.indagium.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A recognizer worker publishes TranscriptReady while holding the controller's lock. When the
 * coordinator's collector runs inline (an unconfined scope), that worker then needs the
 * coordinator's lock. Any coordinator call that held its own lock while calling into the
 * controller used to deadlock against it; app quit during a transcription is one such call.
 */
class VoiceInputCoordinatorLockOrderTest {
    @Test
    fun closeWhileTranscriptIsPublishedDoesNotDeadlock() = assertNoDeadlock { fixture, _ ->
        fixture.coordinator.close()
    }

    @Test
    fun cancelWhileTranscriptIsPublishedDoesNotDeadlock() = assertNoDeadlock { fixture, _ ->
        fixture.coordinator.cancel(TARGET)
    }

    @Test
    fun unregisterWhileTranscriptIsPublishedDoesNotDeadlock() = assertNoDeadlock { _, registration ->
        registration.close()
    }

    @Test
    fun replacingRegistrationWhileTranscriptIsPublishedDoesNotDeadlock() = assertNoDeadlock { fixture, _ ->
        fixture.coordinator.registerTarget(TARGET) { }
    }

    private fun assertNoDeadlock(action: (Fixture, Closeable) -> Unit) {
        val fixture = Fixture()
        try {
            val registration = fixture.coordinator.registerTarget(TARGET) { }
            fixture.coordinator.start(TARGET)
            val worker = checkNotNull(fixture.coordinator.stop(TARGET)) { "stop did not start recognition" }
            worker.invokeOnCompletion { fixture.workerFinished.countDown() }
            assertTrue(fixture.workerHoldsControllerLock.await(5, TimeUnit.SECONDS), "worker never published the transcript")

            val caller = Thread({ action(fixture, registration) }, "coordinator-caller").apply { isDaemon = true; start() }
            // The caller is now blocked on the controller's lock, either with or without the
            // coordinator's lock held. Only the former can deadlock once the worker resumes.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (caller.state != Thread.State.BLOCKED && caller.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            fixture.releaseWorker.countDown()

            caller.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(caller.isAlive, "coordinator call deadlocked against the recognizer worker")
            assertTrue(fixture.workerFinished.await(5, TimeUnit.SECONDS), "recognizer worker deadlocked")
        } finally {
            fixture.releaseWorker.countDown()
            fixture.close()
        }
    }

    private class Fixture : Closeable {
        val workerHoldsControllerLock = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        private val workerExecutor = Executors.newSingleThreadExecutor { Thread(it, "voice-worker").apply { isDaemon = true } }
        private val workerScope = CoroutineScope(SupervisorJob() + workerExecutor.asCoroutineDispatcher())
        private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop(): VoiceCaptureResult = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(1, 0)))

                    override fun cancel() = Unit
                })
            },
            transcriber = VoiceTranscriber { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("transcript")) },
            scope = workerScope,
            dispatcher = workerExecutor.asCoroutineDispatcher(),
        )

        init {
            // Runs inline inside the controller's transition, i.e. on the worker with the
            // controller's lock held, before the state event reaches the coordinator.
            observerScope.launch {
                controller.state.collect { state ->
                    if (state is VoiceInputState.TranscriptReady && Thread.currentThread().name.startsWith("voice-worker")) {
                        workerHoldsControllerLock.countDown()
                        releaseWorker.await()
                    }
                }
            }
        }

        val coordinator = VoiceInputSessionCoordinator(controller, coordinatorScope)

        override fun close() {
            // On a regression the deadlocked threads still hold the locks; never block the runner on them.
            Thread({ runCatching { coordinator.close() } }, "coordinator-close").apply { isDaemon = true; start() }.join(TimeUnit.SECONDS.toMillis(1))
            coordinatorScope.cancel()
            observerScope.cancel()
            workerScope.cancel()
            workerExecutor.shutdownNow()
        }
    }

    private companion object {
        const val TARGET = "note:lock-order"
    }
}
