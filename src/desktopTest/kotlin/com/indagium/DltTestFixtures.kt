package com.indagium

private const val DLT_V1_EXTENDED_HEADER = 0x21
private const val DLT_HTYP_MSBF = 0x02
private const val DLT_TYPE_STRING = 0x200

/** Small spec-shaped DLT v1 fixtures shared by parser and AppState regression tests. */
internal fun dltTestFrame(
    message: String = "hello",
    msbf: Boolean = true,
    ecu: String? = null,
    session: Int? = null,
    timestamp: Long? = null,
    msin: Int = 0x41,
    appId: String = "APP1",
    contextId: String = "CTX1",
): ByteArray {
    var htyp = DLT_V1_EXTENDED_HEADER
    if (msbf) htyp = htyp or DLT_HTYP_MSBF
    val optional = buildList {
        if (ecu != null) addAll(ecu.padEnd(4, '\u0000').take(4).toByteArray().toList())
        if (session != null) addAll(dltU32(session.toLong(), true).toList())
        if (timestamp != null) addAll(dltU32(timestamp, true).toList())
    }.toByteArray()
    if (ecu != null) htyp = htyp or 0x04
    if (session != null) htyp = htyp or 0x08
    if (timestamp != null) htyp = htyp or 0x10
    val payload = dltU32(DLT_TYPE_STRING.toLong(), msbf) + dltU16(message.toByteArray().size + 1, msbf) +
        (message + '\u0000').toByteArray()
    val body = optional + byteArrayOf(msin.toByte(), 1) + appId.padEnd(4, '\u0000').take(4).toByteArray() +
        contextId.padEnd(4, '\u0000').take(4).toByteArray() + payload
    val length = 4 + body.size
    return byteArrayOf(htyp.toByte(), 1, (length ushr 8).toByte(), length.toByte()) + body
}

internal fun dltStorageHeader(
    ecu: String = "ECU1",
    seconds: Long = 1_700_000_000,
    micros: Long = 123_000,
): ByteArray {
    // Storage timestamps are little-endian seconds/microseconds. Keep the explicit byte writes
    // here so the fixture remains independent of the payload-endianness bit.
    return byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1) +
        dltU32(seconds, false) + dltU32(micros, false) + ecu.padEnd(4, '\u0000').take(4).toByteArray()
}

/** A valid raw-v1 frame whose header, ids, and non-verbose payload deliberately contain no NUL. */
internal fun dltNulFreeRawFrame(payloadSize: Int = 260): ByteArray {
    require(payloadSize >= 256)
    // One argument keeps the standard extended header entirely NUL-free; the payload is
    // intentionally opaque and parser-tolerant, so it need not be a verbose string.
    val body = byteArrayOf(0x40, 0x01) + "APP1".toByteArray() + "CTX1".toByteArray() +
        ByteArray(payloadSize) { 'A'.code.toByte() }
    val length = 4 + body.size
    return byteArrayOf(0x23, 0x01, (length ushr 8).toByte(), length.toByte()) + body
}

private fun dltU16(value: Int, bigEndian: Boolean): ByteArray =
    if (bigEndian) byteArrayOf((value ushr 8).toByte(), value.toByte())
    else byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun dltU32(value: Long, bigEndian: Boolean): ByteArray {
    val bytes = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )
    return if (bigEndian) bytes else bytes.reversedArray()
}
