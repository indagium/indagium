package com.indagium.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Where a Wi-Fi pairing attempt is, for the pairing dialogs' one-line stage text. */
internal sealed interface WirelessPairingStage {
    /** QR flow only: the code is showing and no phone has scanned it yet. */
    data object WaitingForPhone : WirelessPairingStage

    data object Pairing : WirelessPairingStage

    data object Connecting : WirelessPairingStage

    data class Connected(val serial: String) : WirelessPairingStage

    data class Failed(val message: String) : WirelessPairingStage
}

/**
 * Pairs a phone over Wi-Fi and waits until it shows up as a usable device. Every side effect comes
 * in as a lambda so the sequencing (which is where the subtle bugs live) is testable with a
 * virtual clock and without a real adb or a [CaptureService]. The lambdas are suspending so the
 * caller can run its blocking adb calls interruptibly: cancelling the calling coroutine (the
 * dialog closing) then stops a pending `adb pair` instead of leaving it to time out.
 */
internal class WirelessPairingFlow(
    private val listDevices: suspend () -> List<CaptureDevice>,
    private val mdnsServices: suspend () -> List<AdbMdnsService>,
    private val pair: suspend (address: String, code: String) -> WirelessAdbOutcome,
    private val connect: suspend (address: String) -> WirelessAdbOutcome,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Pairs with a code typed by the user. Returns the final stage, also reported through [onStage]. */
    suspend fun pairWithCode(
        address: String,
        code: String,
        onStage: (WirelessPairingStage) -> Unit,
    ): WirelessPairingStage {
        val host = splitHostPort(address.trim())?.first
            ?: return fail("That address isn't valid. Use the IP address and port shown on the pairing screen.", onStage)
        val known = knownSerials()
        onStage(WirelessPairingStage.Pairing)
        return when (val outcome = pair(address.trim(), code)) {
            is WirelessAdbOutcome.Failure -> fail(outcome.userMessage, onStage)
            is WirelessAdbOutcome.Success -> awaitConnection(host, parseAdbPairGuid(outcome.detail), known, onStage)
        }
    }

    /**
     * Pairs through the QR session [name]/[password]: waits for the phone to advertise a pairing
     * service whose instance is [name] (other phones' pairing screens are ignored), pairs with it,
     * then waits for the connection.
     */
    suspend fun pairWithQr(
        name: String,
        password: String,
        timeoutMs: Long = QR_TIMEOUT_MS,
        onStage: (WirelessPairingStage) -> Unit,
    ): WirelessPairingStage {
        val known = knownSerials()
        onStage(WirelessPairingStage.WaitingForPhone)
        val service = pollFor(timeoutMs) {
            safely { mdnsServices() }.firstOrNull { it.isPairing && it.instance == name }
        } ?: return fail("The QR code expired before the phone scanned it.", onStage)
        onStage(WirelessPairingStage.Pairing)
        return when (val outcome = pair(service.address, password)) {
            is WirelessAdbOutcome.Failure -> fail(outcome.userMessage, onStage)
            is WirelessAdbOutcome.Success -> awaitConnection(service.host, parseAdbPairGuid(outcome.detail), known, onStage)
        }
    }

    /**
     * After a successful pair, adb's mDNS auto-connect normally attaches the phone by itself, so
     * first just watch the device list. Only when nothing appears is `adb connect` run against the
     * phone's `_adb-tls-connect` service. The order matters: connecting explicitly while
     * auto-connect also runs leaves a duplicate `ip:port` entry next to the `adb-….` one.
     */
    private suspend fun awaitConnection(
        host: String,
        guid: String?,
        knownSerials: Set<String>,
        onStage: (WirelessPairingStage) -> Unit,
    ): WirelessPairingStage {
        onStage(WirelessPairingStage.Connecting)
        pollFor(AUTO_CONNECT_WAIT_MS) { findDevice(host, guid, knownSerials) }?.let { return connected(it, onStage) }

        val service = pollFor(CONNECT_SERVICE_WAIT_MS) {
            safely { mdnsServices() }.firstOrNull { it.isConnect && it.host == host }
        } ?: return fail(NOT_FOUND_AFTER_PAIR, onStage)
        when (val outcome = connect(service.address)) {
            is WirelessAdbOutcome.Failure -> return fail(outcome.userMessage, onStage)
            is WirelessAdbOutcome.Success -> Unit
        }
        // An already-connected phone was in [knownSerials]; after an explicit connect any match counts.
        val device = pollFor(POST_CONNECT_WAIT_MS) { findDevice(host, guid, knownSerials = null) }
            ?: return fail(NOT_FOUND_AFTER_PAIR, onStage)
        return connected(device, onStage)
    }

    /** Runs [probe] once per [POLL_INTERVAL_MS] until it returns non-null or [budgetMs] has passed. */
    private suspend fun <T : Any> pollFor(budgetMs: Long, probe: suspend () -> T?): T? {
        val deadline = now() + budgetMs
        while (true) {
            probe()?.let { return it }
            if (now() >= deadline) return null
            sleep(POLL_INTERVAL_MS)
        }
    }

    private suspend fun findDevice(host: String, guid: String?, knownSerials: Set<String>?): CaptureDevice? {
        val candidates = safely { listDevices() }
            .filter { it.wireless && it.available && (knownSerials == null || it.serial !in knownSerials) }
        val matching = candidates.firstOrNull { device ->
            device.serial.startsWith("$host:") ||
                device.serial.startsWith("[$host]:") ||
                (guid != null && device.serial.startsWith(guid))
        }
        // Without a guid there is nothing to tie an `adb-….` serial to this phone, so a lone new
        // wireless device is the best available answer.
        return matching ?: if (guid == null) candidates.singleOrNull { !HOST_PORT_SERIAL_PREFIX.containsMatchIn(it.serial) } else null
    }

    private suspend fun knownSerials(): Set<String> = safely { listDevices() }.mapTo(HashSet()) { it.serial }

    private fun connected(device: CaptureDevice, onStage: (WirelessPairingStage) -> Unit): WirelessPairingStage =
        WirelessPairingStage.Connected(device.serial).also(onStage)

    private fun fail(message: String, onStage: (WirelessPairingStage) -> Unit): WirelessPairingStage =
        WirelessPairingStage.Failed(message).also(onStage)

    /** A discovery hiccup (adb or mDNS erroring for one poll) is retried by the loops, not fatal. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignored: Exception) {
        emptyList()
    }
}

internal const val QR_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1_000L
private const val AUTO_CONNECT_WAIT_MS = 6_000L
private const val CONNECT_SERVICE_WAIT_MS = 6_000L
private const val POST_CONNECT_WAIT_MS = 4_000L
private const val NOT_FOUND_AFTER_PAIR =
    "Paired, but the phone didn't connect. Check Wireless debugging is on and the phone is on the same Wi-Fi."
private val HOST_PORT_SERIAL_PREFIX = Regex("^(\\[[^\\]]+\\]|[^\\s:\\[\\]]+):\\d+$")
