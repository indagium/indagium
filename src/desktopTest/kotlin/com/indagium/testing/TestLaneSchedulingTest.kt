package com.indagium.testing

import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.MAX_PARALLEL_DEVICES
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.deviceSharingWarnings
import com.indagium.testing.run.groupLanesBySerial
import com.indagium.testing.run.runLaneGroups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 10L
private const val SETTLE_MS = 300L

class TestLaneSchedulingTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun lane(serial: String) = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = serial)

    // ── The scheduler on its own ────────────────────────────────────

    @Test
    fun lanesAreGroupedBySerialInFirstSeenOrder() {
        val a1 = lane("SER-A")
        val b1 = lane("SER-B")
        val a2 = lane("SER-A")
        assertEquals(listOf(listOf(a1, a2), listOf(b1)), groupLanesBySerial(listOf(a1, b1, a2)))
    }

    @Test
    fun twoSerialsRunAtTheSameTimeProvedByEachWaitingForTheOther() = runBlocking(Dispatchers.Default) {
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        withTimeout(AWAIT_MS) {
            runLaneGroups(listOf(lane("SER-A"), lane("SER-B")), MAX_PARALLEL_DEVICES) { config ->
                // Sequential scheduling would leave the first lane waiting for a second one that never starts.
                if (config.deviceSerial == "SER-A") {
                    startedA.complete(Unit)
                    startedB.await()
                } else {
                    startedB.complete(Unit)
                    startedA.await()
                }
            }
        }
        assertTrue(startedA.isCompleted && startedB.isCompleted)
    }

    @Test
    fun lanesThatShareASerialRunOneAfterAnotherInOrderWhileOtherSerialsRunInParallel() = runBlocking(Dispatchers.Default) {
        val active = HashMap<String, AtomicInteger>().apply { listOf("SER-A", "SER-B").forEach { put(it, AtomicInteger()) } }
        val maxOnSerial = AtomicInteger()
        val order = ConcurrentLinkedQueue<String>()
        val a1 = lane("SER-A")
        val a2 = lane("SER-A")
        val b1 = lane("SER-B")
        val otherStarted = CompletableDeferred<Unit>()
        withTimeout(AWAIT_MS) {
            runLaneGroups(listOf(a1, a2, b1), MAX_PARALLEL_DEVICES) { config ->
                val counter = checkNotNull(active[config.deviceSerial])
                val now = counter.incrementAndGet()
                maxOnSerial.updateAndGet { maxOf(it, now) }
                order += config.id
                if (config.id == a1.id) {
                    otherStarted.await() // the other serial must be able to start while this lane is busy
                } else if (config.id == b1.id) {
                    otherStarted.complete(Unit)
                }
                delay(POLL_MS)
                counter.decrementAndGet()
            }
        }
        assertEquals(1, maxOnSerial.get(), "a device never has two lanes at once")
        val sequence = order.toList()
        assertTrue(sequence.indexOf(a1.id) < sequence.indexOf(a2.id), "lanes of a serial keep their order: $sequence")
    }

    @Test
    fun moreThanFourSerialsAreCappedAtFourAtATime() = runBlocking(Dispatchers.Default) {
        val serials = (1..6).map { "SER-$it" }
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val done = async { runLaneGroups(serials.map(::lane), MAX_PARALLEL_DEVICES) { _ ->
            started.incrementAndGet()
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            release.await()
            running.decrementAndGet()
        } }
        withTimeout(AWAIT_MS) { while (started.get() < MAX_PARALLEL_DEVICES) delay(POLL_MS) }
        delay(SETTLE_MS)
        assertEquals(MAX_PARALLEL_DEVICES, started.get(), "the fifth and sixth wait for a free slot")
        release.complete(Unit)
        withTimeout(AWAIT_MS) { done.await() }
        assertEquals(6, started.get())
        assertEquals(MAX_PARALLEL_DEVICES, peak.get())
    }

    @Test
    fun sharedDevicesAndMoreDevicesThanSlotsAreWarnedAbout() {
        val lanes = listOf(lane("SER-A"), lane("SER-B"), lane("SER-A"))
        val warnings = deviceSharingWarnings(lanes)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Lanes 1, 3") && warnings.single().contains("one after another"), warnings.toString())
        assertTrue(deviceSharingWarnings(listOf(lane("SER-A"), lane("SER-B"))).isEmpty())
        val many = deviceSharingWarnings((1..5).map { lane("SER-$it") })
        assertTrue(many.single().contains("5 devices") && many.single().contains("only 4"), many.toString())
    }

    // ── Through the coordinator ─────────────────────────────────────

    private val suite = suiteOf(caseOf("Login", step("Open the app"), step("Look at the home screen")))

    private fun parallelHarness(farm: DeviceFarm, deviceProblem: (String) -> String? = { null }): RunHarness {
        val provider = AutoAgentProvider(listOf("pass" to "ok", "pass" to "ok"))
        return RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(profileOf(LANE_B_PROFILE_ID)),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to provider, LANE_B_PROFILE_ID to provider)),
            openDevice = farm.opener,
            deviceProblem = deviceProblem,
        ).also { harness = it }
    }

    private fun RunHarness.start(vararg lanes: LaneConfig): StartRunResult.Started = startLanes(lanes.toList())

    private fun RunHarness.startLanes(lanes: List<LaneConfig>): StartRunResult.Started {
        val config = config(library.suites.single()).copy(lanes = lanes)
        return assertIs<StartRunResult.Started>(runBlocking { coordinator.start(config) })
    }

    private fun RunHarness.finish(started: StartRunResult.Started): TestRun =
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(coordinator.awaitFinished(started.runId)) } }

    @Test
    fun twoLanesOnDifferentDevicesOpenTheirDevicesAtTheSameTimeAndBothPass() {
        // Each device only opens once the other one is open too: only a parallel engine gets past this.
        val opened = AtomicInteger()
        val bothOpen = CompletableDeferred<Unit>()
        val farm = DeviceFarm(beforeOpen = {
            if (opened.incrementAndGet() == 2) bothOpen.complete(Unit)
            bothOpen.await()
        })
        val h = parallelHarness(farm)
        val run = h.finish(h.start(agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-B")))

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(listOf(RunStatus.PASSED, RunStatus.PASSED), run.lanes.map { it.status })
        run.lanes.forEach { assertEquals(CaseStatus.PASS, it.cases.single().status) }
    }

    @Test
    fun twoLanesOnTheSameDeviceRunOneAfterAnother() {
        val farm = DeviceFarm()
        val h = parallelHarness(farm)
        val run = h.finish(h.start(agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-A")))

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val (first, second) = run.lanes
        assertTrue(assertNotNull(second.startedAt) >= assertNotNull(first.finishedAt), "the second lane started only after the first finished")
    }

    @Test
    fun moreThanFourDevicesWaitForASlotAndAllLanesStillRun() {
        val opened = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val farm = DeviceFarm(beforeOpen = {
            opened.incrementAndGet()
            gate.await()
        })
        val h = parallelHarness(farm)
        val started = h.startLanes((1..5).map { agentLane(LANE_A_PROFILE_ID, "SER-$it") })

        runBlocking {
            withTimeout(AWAIT_MS) { while (opened.get() < MAX_PARALLEL_DEVICES) delay(POLL_MS) }
            delay(SETTLE_MS)
            assertEquals(MAX_PARALLEL_DEVICES, opened.get(), "the fifth device waits for a slot")
            assertEquals(1, h.coordinator.run(started.runId)!!.lanes.count { it.status == RunStatus.QUEUED }, "and shows as queued")
            gate.complete(Unit)
        }
        val run = h.finish(started)
        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(5, run.lanes.count { it.status == RunStatus.PASSED })
    }

    @Test
    fun theLiveCaptureDeviceIsRefusedUpFrontAndAtOpenTimeWithoutStoppingTheOtherLane() {
        val refusedUpFront = parallelHarness(DeviceFarm(), deviceProblem = { if (it == "SER-LIVE") "Device $it is held by the live capture." else null })
        val rejected = runBlocking {
            refusedUpFront.coordinator.start(refusedUpFront.config(suite, agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-LIVE")))
        }
        assertTrue(assertIs<StartRunResult.Rejected>(rejected).errors.single().contains("live capture"), rejected.toString())
        assertTrue(refusedUpFront.coordinator.runsFlow.value.isEmpty(), "nothing started")
        refusedUpFront.close()

        // The capture started after the start-time check: the lane's own open refuses; the other lane carries on.
        val h = parallelHarness(DeviceFarm(isLiveCaptureSerial = { it == "SER-LIVE" }))
        val run = h.finish(h.start(agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-LIVE")))
        val (good, refused) = run.lanes
        assertEquals(RunStatus.PASSED, good.status, "a lane that cannot open its device does not stop the other: ${good.error}")
        assertEquals(RunStatus.ERROR, refused.status)
        assertTrue(refused.error.orEmpty().contains("live capture"), refused.error)
        assertEquals(RunStatus.ERROR, run.status)
    }

    // ── Pause all ───────────────────────────────────────────────────

    @Test
    fun pauseAllHoldsALaneAtTheNextStepBoundaryUntilResumed() {
        lateinit var runIdForPause: String
        var coordinatorRef: RunHarness? = null
        val provider = AutoAgentProvider(listOf("pass" to "one", "pass" to "two")) { answered ->
            // The model is asked for step 1's report: pause now, so the answer to finish_step is held.
            if (answered == 0) coordinatorRef!!.coordinator.setPaused(runIdForPause, true)
        }
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to provider)),
            openDevice = DeviceFarm().opener,
        ).also { harness = it }
        coordinatorRef = h
        val gateOpen = CompletableDeferred<Unit>()
        val started = runBlocking {
            val result = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suite, agentLane(LANE_A_PROFILE_ID, "SER-A"))))
            runIdForPause = result.runId
            gateOpen.complete(Unit)
            result
        }
        runBlocking {
            withTimeout(AWAIT_MS) { while (!h.coordinator.isPaused(started.runId)) delay(POLL_MS) }
            delay(SETTLE_MS)
            val held = h.coordinator.run(started.runId)!!
            assertEquals(RunStatus.RUNNING, held.status, "the run waits while paused")
            assertEquals(1, held.lanes.single().cases.single().steps.size, "step 1 was recorded; step 2 has not been handed out")
            assertTrue(h.coordinator.setPaused(started.runId, false))
        }
        val run = h.finish(started)
        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(2, run.lanes.single().cases.single().steps.size)
    }
}
