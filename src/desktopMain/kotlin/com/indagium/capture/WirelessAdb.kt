package com.indagium.capture

import java.security.SecureRandom
import java.util.Random

/** mDNS service type a phone advertises only while its "Pair device with pairing code" or QR screen is open. */
internal const val ADB_MDNS_PAIRING_TYPE = "_adb-tls-pairing._tcp"

/** mDNS service type a phone advertises while Wireless debugging is on and it is ready to connect. */
internal const val ADB_MDNS_CONNECT_TYPE = "_adb-tls-connect._tcp"

/** One line of `adb mdns services`: an instance name, its service type and where to reach it. */
internal data class AdbMdnsService(
    val instance: String,
    val type: String,
    val host: String,
    val port: Int,
) {
    val isPairing: Boolean get() = type == ADB_MDNS_PAIRING_TYPE
    val isConnect: Boolean get() = type == ADB_MDNS_CONNECT_TYPE

    /** `host:port` as `adb pair`/`adb connect` want it; an IPv6 host is bracketed. */
    val address: String get() = formatHostPort(host, port)
}

internal fun formatHostPort(host: String, port: Int): String =
    if (host.contains(':')) "[$host]:$port" else "$host:$port"

/**
 * Parses `adb mdns services`: a "List of discovered mdns services" header, then one
 * `<instance>\t<type>\t<host:port>` line per service. The address is always the last token, so
 * extra columns a newer adb might add in between do not matter. Malformed lines are dropped
 * rather than failing the whole listing.
 */
internal fun parseAdbMdnsServices(output: String): List<AdbMdnsService> = output.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("List of discovered") }
    .mapNotNull { line ->
        val tokens = line.split(WHITESPACE_REGEX)
        if (tokens.size < MIN_MDNS_TOKENS) return@mapNotNull null
        val type = tokens[1]
        if (!type.startsWith("_")) return@mapNotNull null
        val (host, port) = splitHostPort(tokens.last()) ?: return@mapNotNull null
        AdbMdnsService(instance = tokens[0], type = type, host = host, port = port)
    }
    .toList()

/** Splits `host:port` or `[v6]:port`; null when the port is missing or out of range. */
internal fun splitHostPort(value: String): Pair<String, Int>? {
    val host: String
    val portText: String
    if (value.startsWith('[')) {
        val close = value.indexOf(']')
        if (close < 0 || value.getOrNull(close + 1) != ':') return null
        host = value.substring(1, close)
        portText = value.substring(close + 2)
    } else {
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return null
        host = value.substring(0, separator)
        portText = value.substring(separator + 1)
    }
    val port = portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: return null
    return if (host.isEmpty()) null else host to port
}

internal sealed interface WirelessAdbOutcome {
    /** [detail] is adb's own one-line confirmation, kept for guid extraction and diagnostics. */
    data class Success(val detail: String) : WirelessAdbOutcome

    /** [userMessage] is short, plain and safe to show in the UI. */
    data class Failure(val userMessage: String) : WirelessAdbOutcome
}

/**
 * `adb pair` exits 0 on some failures, so success is judged on the text alone. [secret] (the pairing
 * code) is scrubbed from any adb text that ends up in the message, though adb does not echo it.
 */
internal fun parseAdbPairResult(result: CaptureCommandResult, secret: String? = null): WirelessAdbOutcome {
    if (result.timedOut) return WirelessAdbOutcome.Failure("Pairing timed out. Check the phone is still showing the pairing screen.")
    val text = combinedOutput(result)
    if (text.contains("Successfully paired", ignoreCase = true)) {
        val line = text.lineSequence().firstOrNull { it.contains("Successfully paired", ignoreCase = true) }.orEmpty()
        return WirelessAdbOutcome.Success(line.trim())
    }
    val lower = text.lowercase()
    val message = when {
        "wrong password" in lower || "wrong pairing" in lower ->
            "Wrong pairing code, or the pairing screen closed on the phone."
        "connection refused" in lower || "failed to connect" in lower || "unable to connect" in lower ->
            "Could not reach the phone. Check it is on the same Wi-Fi with the pairing screen open."
        "no route to host" in lower || "network is unreachable" in lower || "unknown host" in lower ->
            "Could not reach the phone. Check it is on the same Wi-Fi."
        "invalid" in lower && ("address" in lower || "host" in lower) ->
            "That address isn't valid. Use the IP address and port shown on the pairing screen."
        else -> genericFailure("Pairing failed", text, secret)
    }
    return WirelessAdbOutcome.Failure(message)
}

/** `adb connect` also exits 0 on some failures. "already connected to" contains "connected to". */
internal fun parseAdbConnectResult(result: CaptureCommandResult): WirelessAdbOutcome {
    if (result.timedOut) return WirelessAdbOutcome.Failure("Connecting timed out. Check the phone is on the same Wi-Fi.")
    val text = combinedOutput(result)
    val lower = text.lowercase()
    if ("connected to" in lower && "failed" !in lower && "cannot" !in lower) {
        val line = text.lineSequence().firstOrNull { it.contains("connected to", ignoreCase = true) }.orEmpty()
        return WirelessAdbOutcome.Success(line.trim())
    }
    val message = when {
        "connection refused" in lower || "timed out" in lower || "no route to host" in lower ->
            "Could not connect. Check Wireless debugging is on and the phone is on the same Wi-Fi."
        else -> genericFailure("Connecting failed", text, null)
    }
    return WirelessAdbOutcome.Failure(message)
}

/** Pulls the `guid=` value out of `Successfully paired to host:port [guid=adb-XXXX-YYYY]`. */
internal fun parseAdbPairGuid(detail: String): String? =
    PAIR_GUID_REGEX.find(detail)?.groupValues?.get(1)

private fun combinedOutput(result: CaptureCommandResult): String =
    (result.stdoutText() + "\n" + result.stderrText()).trim()

private fun genericFailure(prefix: String, text: String, secret: String?): String {
    var detail = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty()
    if (!secret.isNullOrEmpty()) detail = detail.replace(secret, "***")
    detail = detail.take(MAX_FAILURE_DETAIL_CHARS)
    return if (detail.isEmpty()) prefix else "$prefix: $detail"
}

/**
 * A one-off QR pairing session. The [name] is what the phone advertises as its pairing service
 * instance once it scans the code; [password] is the pairing secret carried in the QR payload.
 * [toString] hides the password so a stray log line or debugger dump cannot leak it.
 */
internal class QrPairingCredentials(val name: String, val password: String) {
    override fun toString(): String = "QrPairingCredentials(name=$name, password=***)"
}

/** Alphanumerics only, so nothing in the QR payload needs escaping. */
internal fun newQrPairingCredentials(random: Random = SecureRandom()): QrPairingCredentials {
    fun token(length: Int) = buildString(length) {
        repeat(length) { append(CREDENTIAL_ALPHABET[random.nextInt(CREDENTIAL_ALPHABET.length)]) }
    }
    return QrPairingCredentials(name = QR_INSTANCE_PREFIX + token(QR_NAME_LENGTH), password = token(QR_PASSWORD_LENGTH))
}

/** The Android Studio-style payload the phone's "Pair device with QR code" camera understands. */
internal fun qrPairingPayload(name: String, password: String): String = "WIFI:T:ADB;S:$name;P:$password;;"

/** True for the mDNS-style serial adb assigns to an auto-connected phone, or a plain `host:port`. */
internal fun isWirelessSerial(serial: String): Boolean =
    serial.contains(".$ADB_MDNS_CONNECT_TYPE") || HOST_PORT_SERIAL_REGEX.matches(serial)

/**
 * Pairing services worth offering as "Ready to pair" rows: pairing-type only, without the QR
 * sessions (this app's own and Android Studio's), whose password is not something to type.
 */
internal fun codePairingCandidates(services: List<AdbMdnsService>): List<AdbMdnsService> = services
    .filter { it.isPairing && !it.instance.startsWith(QR_INSTANCE_PREFIX) && !it.instance.startsWith(STUDIO_QR_INSTANCE_PREFIX) }
    .distinctBy { it.address }

internal const val QR_INSTANCE_PREFIX = "indagium-"
private const val STUDIO_QR_INSTANCE_PREFIX = "studio-"
private const val QR_NAME_LENGTH = 10
private const val QR_PASSWORD_LENGTH = 12
private const val CREDENTIAL_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val MIN_MDNS_TOKENS = 3
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val MAX_FAILURE_DETAIL_CHARS = 160
private val WHITESPACE_REGEX = Regex("\\s+")
private val PAIR_GUID_REGEX = Regex("guid=([^\\]\\s]+)")
private val HOST_PORT_SERIAL_REGEX = Regex("^(\\[[^\\]]+\\]|[^\\s:\\[\\]]+):\\d{1,5}$")
