package com.indagium.capture.mirror

/** Compressed packet copied from the mirror's existing scrcpy video stream, before any decoder. */
internal data class MirrorVideoPacket(
    /** Source PTS from the current scrcpy connection. It may reset after reconnect. */
    val sourcePtsUs: Long,
    val config: Boolean,
    val keyFrame: Boolean,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    /** Transport receipt anchors; they are approximate observations, not device capture timestamps. */
    val receivedAtMs: Long,
    val receivedAtNanos: Long,
    val connectionEpoch: Long,
    val sequence: Long,
)
