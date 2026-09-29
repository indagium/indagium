package com.indagium.capture

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WirelessAdbTest {
    private fun result(stdout: String, stderr: String = "", exit: Int = 0, timedOut: Boolean = false) =
        CaptureCommandResult(exit, stdout.toByteArray(), stderr.toByteArray(), timedOut)

    @Test
    fun parsesTabSeparatedMdnsServices() {
        val services = parseAdbMdnsServices(
            "List of discovered mdns services\n" +
                "adb-R5CT1234-AbCdEf\t_adb-tls-connect._tcp\t192.168.1.23:37831\n" +
                "adb-R5CT1234-AbCdEf\t_adb-tls-pairing._tcp\t192.168.1.23:41234\n",
        )

        assertEquals(2, services.size)
        assertEquals("adb-R5CT1234-AbCdEf", services[0].instance)
        assertTrue(services[0].isConnect)
        assertFalse(services[0].isPairing)
        assertEquals("192.168.1.23:37831", services[0].address)
        assertTrue(services[1].isPairing)
        assertEquals(41234, services[1].port)
    }

    @Test
    fun parsesIpv6AndBracketsItAgain() {
        val services = parseAdbMdnsServices("phone\t_adb-tls-connect._tcp\t[fe80::1234:abcd]:5555")

        assertEquals("fe80::1234:abcd", services.single().host)
        assertEquals(5555, services.single().port)
        assertEquals("[fe80::1234:abcd]:5555", services.single().address)
    }

    @Test
    fun headerOnlyAndGarbageLinesYieldNothing() {
        assertEquals(emptyList(), parseAdbMdnsServices("List of discovered mdns services\n"))
        assertEquals(emptyList(), parseAdbMdnsServices(""))
        assertEquals(
            emptyList(),
            parseAdbMdnsServices(
                "garbage\nonly two\ttokens\nname\tnot-a-type\t1.2.3.4:5\nname\t_t._tcp\tno-port\nname\t_t._tcp\t1.2.3.4:99999\n",
            ),
        )
    }

    @Test
    fun pairResultIsJudgedOnTextNotExitCode() {
        val ok = parseAdbPairResult(result("Successfully paired to 192.168.1.23:41234 [guid=adb-R5CT1234-AbCdEf]"))
        assertIs<WirelessAdbOutcome.Success>(ok)
        assertEquals("adb-R5CT1234-AbCdEf", parseAdbPairGuid(ok.detail))

        val wrong = parseAdbPairResult(result("Failed: Wrong password or connection was dropped.", exit = 0))
        assertIs<WirelessAdbOutcome.Failure>(wrong)
        assertTrue(wrong.userMessage.contains("Wrong pairing code"))

        assertIs<WirelessAdbOutcome.Failure>(parseAdbPairResult(result("", timedOut = true, exit = -1)))
        assertIs<WirelessAdbOutcome.Failure>(parseAdbPairResult(result("", "error: something odd", exit = 1)))
    }

    @Test
    fun genericPairFailureNeverEchoesTheCode() {
        val failure = parseAdbPairResult(result("Failed: 123456 was rejected"), secret = "123456")

        assertIs<WirelessAdbOutcome.Failure>(failure)
        assertFalse(failure.userMessage.contains("123456"))
    }

    @Test
    fun connectResultRecognisesConnectedAndAlreadyConnected() {
        assertIs<WirelessAdbOutcome.Success>(parseAdbConnectResult(result("connected to 192.168.1.23:37831")))
        assertIs<WirelessAdbOutcome.Success>(parseAdbConnectResult(result("already connected to 192.168.1.23:37831")))
        assertIs<WirelessAdbOutcome.Failure>(
            parseAdbConnectResult(result("failed to connect to '192.168.1.23:37831': Connection refused", exit = 0)),
        )
        assertIs<WirelessAdbOutcome.Failure>(parseAdbConnectResult(result("", timedOut = true, exit = -1)))
    }

    @Test
    fun qrPayloadAndCredentialsUseOnlyAlphanumerics() {
        assertEquals("WIFI:T:ADB;S:indagium-abc;P:secret;;", qrPairingPayload("indagium-abc", "secret"))

        val credentials = newQrPairingCredentials(Random(7))
        assertTrue(credentials.name.startsWith("indagium-"))
        assertEquals(10, credentials.name.removePrefix("indagium-").length)
        assertEquals(12, credentials.password.length)
        assertTrue(credentials.name.removePrefix("indagium-").all { it.isLetterOrDigit() })
        assertTrue(credentials.password.all { it.isLetterOrDigit() })
        assertFalse(credentials.toString().contains(credentials.password))
    }

    @Test
    fun recognisesWirelessSerials() {
        assertTrue(isWirelessSerial("adb-R5CT1234-AbCdEf._adb-tls-connect._tcp"))
        assertTrue(isWirelessSerial("192.168.1.23:37831"))
        assertTrue(isWirelessSerial("[fe80::1]:5555"))
        assertFalse(isWirelessSerial("R5CT1234"))
        assertFalse(isWirelessSerial("emulator-5554"))
        assertTrue(CaptureDevice("192.168.1.23:37831", "device").wireless)
        assertFalse(CaptureDevice("emulator-5554", "device").wireless)
    }

    @Test
    fun wirelessGuidanceMentionsWifi() {
        assertTrue(deviceStateGuidance("offline", wireless = true).orEmpty().contains("Wi-Fi"))
        assertTrue(deviceStateGuidance("offline", wireless = false).orEmpty().contains("Reconnect"))
        assertNull(deviceStateGuidance("device", wireless = true))
    }

    @Test
    fun candidatesDropQrSessionsConnectServicesAndDuplicates() {
        val candidates = codePairingCandidates(
            listOf(
                AdbMdnsService("adb-A", ADB_MDNS_PAIRING_TYPE, "10.0.0.5", 4000),
                AdbMdnsService("adb-A-dup", ADB_MDNS_PAIRING_TYPE, "10.0.0.5", 4000),
                AdbMdnsService("indagium-xyz", ADB_MDNS_PAIRING_TYPE, "10.0.0.6", 4001),
                AdbMdnsService("studio-xyz", ADB_MDNS_PAIRING_TYPE, "10.0.0.7", 4002),
                AdbMdnsService("adb-B", ADB_MDNS_CONNECT_TYPE, "10.0.0.8", 4003),
            ),
        )

        assertEquals(listOf("10.0.0.5:4000"), candidates.map { it.address })
    }
}
