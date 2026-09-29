package com.indagium.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WirelessPairingFlowTest {
    private val pairedOk = WirelessAdbOutcome.Success("Successfully paired to 10.0.0.5:4000 [guid=adb-X]")
    private val pairingService = AdbMdnsService("adb-X", ADB_MDNS_PAIRING_TYPE, "10.0.0.5", 4000)
    private val connectService = AdbMdnsService("adb-X", ADB_MDNS_CONNECT_TYPE, "10.0.0.5", 4100)
    private val autoDevice = CaptureDevice("adb-X._adb-tls-connect._tcp", "device")

    private class Recorder {
        val pairs = mutableListOf<Pair<String, String>>()
        val connects = mutableListOf<String>()
        val stages = mutableListOf<WirelessPairingStage>()
    }

    private fun TestScope.flow(
        recorder: Recorder,
        devices: () -> List<CaptureDevice> = { emptyList() },
        services: () -> List<AdbMdnsService> = { emptyList() },
        pair: (String, String) -> WirelessAdbOutcome = { _, _ -> pairedOk },
        connect: (String) -> WirelessAdbOutcome = { WirelessAdbOutcome.Success("connected to $it") },
    ) = WirelessPairingFlow(
        listDevices = { devices() },
        mdnsServices = { services() },
        pair = { address, code -> recorder.pairs += address to code; pair(address, code) },
        connect = { address -> recorder.connects += address; connect(address) },
        now = { testScheduler.currentTime },
    )

    @Test
    fun autoConnectWinsAndConnectIsNeverCalled() = runTest {
        val recorder = Recorder()
        val flow = flow(
            recorder,
            devices = { if (testScheduler.currentTime >= 2_000) listOf(autoDevice) else emptyList() },
            services = { listOf(connectService) },
        )

        val result = flow.pairWithCode("10.0.0.5:4000", "123456") { recorder.stages += it }

        assertEquals(WirelessPairingStage.Connected(autoDevice.serial), result)
        assertTrue(recorder.connects.isEmpty())
        assertEquals(listOf("10.0.0.5:4000" to "123456"), recorder.pairs)
        assertEquals(listOf(WirelessPairingStage.Pairing, WirelessPairingStage.Connecting), recorder.stages.take(2))
    }

    @Test
    fun fallsBackToExplicitConnectWhenNothingAutoConnects() = runTest {
        val recorder = Recorder()
        val flow = flow(
            recorder,
            devices = { if (recorder.connects.isNotEmpty()) listOf(CaptureDevice("10.0.0.5:4100", "device")) else emptyList() },
            services = { listOf(connectService) },
        )

        val result = flow.pairWithCode("10.0.0.5:4000", "123456") { recorder.stages += it }

        assertEquals(WirelessPairingStage.Connected("10.0.0.5:4100"), result)
        assertEquals(listOf("10.0.0.5:4100"), recorder.connects)
    }

    @Test
    fun failsWhenThePhoneNeverShowsUp() = runTest {
        val recorder = Recorder()
        val flow = flow(recorder)

        val result = flow.pairWithCode("10.0.0.5:4000", "123456") { recorder.stages += it }

        assertIs<WirelessPairingStage.Failed>(result)
        assertTrue(recorder.connects.isEmpty())
    }

    @Test
    fun aFailedPairShortCircuits() = runTest {
        val recorder = Recorder()
        var listCalls = 0
        val flow = flow(
            recorder,
            devices = { listCalls++; emptyList() },
            pair = { _, _ -> WirelessAdbOutcome.Failure("Wrong pairing code, or the pairing screen closed on the phone.") },
        )

        val result = flow.pairWithCode("10.0.0.5:4000", "000000") { recorder.stages += it }

        assertEquals(WirelessPairingStage.Failed("Wrong pairing code, or the pairing screen closed on the phone."), result)
        assertEquals(1, listCalls, "only the pre-pair snapshot, no connection polling")
        assertTrue(recorder.connects.isEmpty())
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun anInvalidAddressFailsWithoutPairing() = runTest {
        val recorder = Recorder()
        val result = flow(recorder).pairWithCode("not an address", "123456") { recorder.stages += it }

        assertIs<WirelessPairingStage.Failed>(result)
        assertTrue(recorder.pairs.isEmpty())
    }

    @Test
    fun qrFlowIgnoresOtherPhonesAndPairsTheMatchingInstance() = runTest {
        val recorder = Recorder()
        val other = AdbMdnsService("adb-OTHER", ADB_MDNS_PAIRING_TYPE, "10.0.0.9", 4999)
        val mine = AdbMdnsService("indagium-abc", ADB_MDNS_PAIRING_TYPE, "10.0.0.5", 4000)
        val flow = flow(
            recorder,
            devices = { if (recorder.pairs.isNotEmpty()) listOf(autoDevice) else emptyList() },
            services = { if (testScheduler.currentTime >= 3_000) listOf(other, mine) else listOf(other) },
        )

        val result = flow.pairWithQr("indagium-abc", "pw") { recorder.stages += it }

        assertEquals(WirelessPairingStage.Connected(autoDevice.serial), result)
        assertEquals(listOf("10.0.0.5:4000" to "pw"), recorder.pairs)
        assertEquals(WirelessPairingStage.WaitingForPhone, recorder.stages.first())
    }

    @Test
    fun qrFlowTimesOutWhenNobodyScans() = runTest {
        val recorder = Recorder()
        val flow = flow(recorder, services = { listOf(pairingService) })

        val result = flow.pairWithQr("indagium-abc", "pw", timeoutMs = 5_000) { recorder.stages += it }

        assertIs<WirelessPairingStage.Failed>(result)
        assertTrue(recorder.pairs.isEmpty())
        assertTrue(testScheduler.currentTime >= 5_000)
    }

    @Test
    fun cancellingTheCallerStopsAPendingPair() = runTest {
        val recorder = Recorder()
        var cancelledInsidePair = false
        val flow = WirelessPairingFlow(
            listDevices = { emptyList() },
            mdnsServices = { emptyList() },
            pair = { _, _ ->
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelledInsidePair = true
                    throw e
                }
            },
            connect = { WirelessAdbOutcome.Success("connected to $it") },
            now = { testScheduler.currentTime },
        )

        val job = launch { flow.pairWithCode("10.0.0.5:4000", "123456") { recorder.stages += it } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertTrue(cancelledInsidePair)
        assertEquals(listOf<WirelessPairingStage>(WirelessPairingStage.Pairing), recorder.stages)
    }
}
