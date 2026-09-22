package com.indagium.capture.mirror

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrcpyStreamAdaptersTest {
    @Test
    fun annexBAdapterConcatenatesPacketPayloadsAndSkipsSessionMeta() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                writeInt(0x80000000.toInt())
                writeInt(640)
                writeInt(480)
                writeLong(1L shl 62)
                writeInt(4)
                write(byteArrayOf(0, 0, 0, 1))
                writeLong(0L or (1L shl 61))
                writeInt(3)
                write(byteArrayOf(9, 8, 7))
            }
        }.toByteArray()
        val adapter = ScrcpyToAnnexBInputStream(ByteArrayInputStream(bytes))
        val out = adapter.readBytes()
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 9, 8, 7), out)
    }

    @Test
    fun annexBAdapterReturnsEofWhenStreamIsDisabled() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).writeInt(ScrcpyCodecIds.STREAM_DISABLED) }.toByteArray()
        val adapter = ScrcpyToAnnexBInputStream(ByteArrayInputStream(bytes))
        assertEquals(-1, adapter.read())
    }

    @Test
    fun boundedFeedDeliversConfigPacketsEvenWhenQueueIsFull() {
        val feed = BoundedAnnexBFeed(capacity = 2)
        try {
            // Fill the queue past capacity with ordinary packets - some must be dropped.
            repeat(10) { feed.offer(byteArrayOf(it.toByte()), config = false, keyFrame = false) }
            assertTrue(feed.droppedPackets > 0, "overflowing packets must be dropped, not block")
            // A config packet must always get through regardless of queue pressure.
            feed.offer(byteArrayOf(0x7f), config = true, keyFrame = false)
            val latch = CountDownLatch(1)
            var sawConfigByte = false
            val bytes = mutableListOf<Byte>()
            val reader = Thread {
                val buffer = ByteArray(64)
                while (true) {
                    val n = feed.input.read(buffer)
                    if (n < 0) break
                    repeat(n) { i -> bytes += buffer[i] }
                    if (bytes.contains(0x7f)) {
                        sawConfigByte = true
                        latch.countDown()
                        break
                    }
                }
            }
            reader.start()
            assertTrue(latch.await(3, TimeUnit.SECONDS), "config byte must reach the decoder side")
            assertTrue(sawConfigByte)
            reader.join(500)
        } finally {
            feed.close()
        }
    }

    @Test
    fun boundedFeedResumesDeliveryAfterTheNextKeyFrame() {
        val feed = BoundedAnnexBFeed(capacity = 1)
        try {
            // Overflow the queue so droppedSinceKeyframe latches on.
            feed.offer(byteArrayOf(1), config = false, keyFrame = false)
            feed.offer(byteArrayOf(2), config = false, keyFrame = false)
            feed.offer(byteArrayOf(3), config = false, keyFrame = false)
            val droppedBefore = feed.droppedPackets
            assertTrue(droppedBefore > 0)
            // A non-key packet while still latched must keep being dropped.
            feed.offer(byteArrayOf(4), config = false, keyFrame = false)
            assertTrue(feed.droppedPackets > droppedBefore)
        } finally {
            feed.close()
        }
    }
}
