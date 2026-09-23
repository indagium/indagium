package com.indagium.capture.mirror

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
        val feed = BoundedAnnexBFeed(capacity = 4)
        try {
            // A new feed behaves as though it has already dropped a delta: it must wait for a
            // complete configuration + key frame before delivering deltas.
            feed.offer(byteArrayOf(1), config = true, keyFrame = false)
            feed.offer(byteArrayOf(2), config = false, keyFrame = false)
            val droppedBefore = feed.droppedPackets
            assertTrue(droppedBefore > 0)
            feed.offer(byteArrayOf(9), config = false, keyFrame = false)
            assertTrue(feed.droppedPackets > droppedBefore, "deltas stay suppressed until the key frame")
            feed.offer(byteArrayOf(3), config = false, keyFrame = true)
            feed.offer(byteArrayOf(4), config = false, keyFrame = false)
            feed.finish()
            val delivered = feed.input.readBytes()
            assertTrue(delivered.none { it == 2.toByte() || it == 9.toByte() })
            // The packet pump may already have written the first config before recovery clears
            // its queue, so accepting a duplicate SPS/PPS is valid. The new decodable GOP must
            // nevertheless end with config, keyframe, delta in that order.
            assertContentEquals(byteArrayOf(1, 3, 4), delivered.takeLast(3).toByteArray())
        } finally {
            feed.close()
        }
    }

    @Test
    fun standalonePacketFeedPreservesConfigAndKeyframeBeforeDeltas() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                // Session metadata must never reach the decoder's Annex-B input.
                writeInt(0x80000000.toInt())
                writeInt(320)
                writeInt(240)
                writeLong(1L shl 62)
                writeInt(2)
                write(byteArrayOf(0x01, 0x02))
                writeLong(1L shl 61)
                writeInt(2)
                write(byteArrayOf(0x03, 0x04))
                writeLong(7L)
                writeInt(2)
                write(byteArrayOf(0x05, 0x06))
            }
        }.toByteArray()
        val feed = BoundedScrcpyAnnexBFeed(ByteArrayInputStream(bytes), capacity = 3)
        try {
            val delivered = feed.input.readBytes()
            assertTrue(delivered.asList().windowed(2).any { it == listOf(1.toByte(), 2.toByte()) })
            assertContentEquals(byteArrayOf(3, 4, 5, 6), delivered.takeLast(4).toByteArray())
            assertEquals(0L, feed.droppedPackets)
        } finally {
            feed.close()
        }
    }

    @Test
    fun packetFeedBoundsPacketsAndReplaysConfigAtTheNextKeyFrame() {
        val now = AtomicLong(1_000_000_000L)
        val feed = packetFeed(now)
        try {
            feed.offerPacket(packet(1, config = true, data = byteArrayOf(7), now = now.get()))
            feed.offerPacket(packet(2, keyFrame = true, data = byteArrayOf(1), now = now.get()))
            feed.offerPacket(packet(3, data = byteArrayOf(2), now = now.get()))
            feed.offerPacket(packet(4, data = byteArrayOf(3), now = now.get()))
            assertEquals(3, feed.diagnostics().queuedPackets, "config does not consume one of the three video packet slots")
            assertEquals(1, feed.diagnostics().queuedConfigPackets, "the retained config is accounted separately")

            feed.offerPacket(packet(5, data = byteArrayOf(4), now = now.get()))
            assertTrue(feed.diagnostics().resyncPending, "overflow must wait for a key frame")
            assertEquals(1L, feed.diagnostics().resyncEvents, "overflow is one recovery transition")
            feed.offerPacket(packet(6, data = byteArrayOf(5), now = now.get()))
            feed.offerPacket(packet(7, keyFrame = true, data = byteArrayOf(6), now = now.get()))

            val recoveredConfig = requireNotNull(feed.nextPacket())
            val recoveredKeyFrame = requireNotNull(feed.nextPacket())
            assertTrue(recoveredConfig.config)
            assertContentEquals(byteArrayOf(7), recoveredConfig.data)
            assertTrue(recoveredKeyFrame.keyFrame)
            assertContentEquals(byteArrayOf(6), recoveredKeyFrame.data)
            assertTrue(feed.droppedPackets >= 4, "evicted and resync-suppressed packets are counted")
            assertTrue(!feed.diagnostics().resyncPending)
        } finally {
            feed.close()
        }
    }

    @Test
    fun packetFeedReplacesRepeatedConfigsAndReplaysOnlyTheLatestBeforeRecoveryKeyFrame() {
        val now = AtomicLong(1_000_000_000L)
        val feed = packetFeed(now)
        try {
            repeat(1_000) { index ->
                feed.offerPacket(packet(index.toLong(), config = true, data = byteArrayOf(index.toByte()), now = now.get()))
            }
            val beforeKeyFrame = feed.diagnostics()
            assertEquals(0, beforeKeyFrame.queuedPackets, "config packets do not consume the video queue")
            assertEquals(1, beforeKeyFrame.queuedConfigPackets, "only one config delivery packet remains queued")
            assertEquals(1L, beforeKeyFrame.queuedBytes, "obsolete config payloads are removed from byte accounting")

            feed.offerPacket(packet(1_001, keyFrame = true, data = byteArrayOf(0x65), now = now.get()))
            val replayedConfig = requireNotNull(feed.nextPacket())
            val recoveryKeyFrame = requireNotNull(feed.nextPacket())
            assertTrue(replayedConfig.config)
            assertContentEquals(byteArrayOf((999).toByte()), replayedConfig.data)
            assertTrue(recoveryKeyFrame.keyFrame)
            assertContentEquals(byteArrayOf(0x65), recoveryKeyFrame.data)
        } finally {
            feed.close()
        }
    }

    @Test
    fun packetFeedExpiresAStaleGopAndDropsDeltasUntilKeyFrame() {
        val now = AtomicLong(10_000_000L)
        val feed = packetFeed(now)
        try {
            feed.offerPacket(packet(1, keyFrame = true, data = byteArrayOf(1), now = now.get()))
            feed.offerPacket(packet(2, data = byteArrayOf(2), now = now.get()))
            now.addAndGet(76_000_000L)
            val staleSnapshot = feed.diagnostics()
            assertEquals(0, staleSnapshot.queuedPackets, "a packet older than 75 ms is not decoded")
            assertTrue(staleSnapshot.resyncPending)
            assertEquals(1L, staleSnapshot.resyncEvents)

            feed.offerPacket(packet(3, data = byteArrayOf(3), now = now.get()))
            assertEquals(0, feed.diagnostics().queuedPackets, "deltas stay suppressed after stale data is dropped")
            feed.offerPacket(packet(4, keyFrame = true, data = byteArrayOf(4), now = now.get()))
            assertContentEquals(byteArrayOf(4), feed.nextPacket()?.data)
        } finally {
            feed.close()
        }
    }

    @Test
    fun packetFeedEnforcesTheThirtyTwoMibByteLimit() {
        val now = AtomicLong(1L)
        val feed = packetFeed(now)
        try {
            feed.offerPacket(packet(1, keyFrame = true, data = ByteArray(32 * 1024 * 1024), now = now.get()))
            assertEquals(32L * 1024 * 1024, feed.diagnostics().queuedBytes)
            feed.offerPacket(packet(2, data = byteArrayOf(1), now = now.get()))
            assertEquals(0L, feed.diagnostics().queuedBytes)
            assertTrue(feed.diagnostics().resyncPending, "a packet beyond 32 MiB starts a new GOP")
        } finally {
            feed.close()
        }
    }

    @Test
    fun packetFeedEofDrainsQueuedPacketsAndBlockedReadClosesCleanly() {
        val wire = scrcpyPackets(
            packet(1, config = true, data = byteArrayOf(7), now = 0),
            packet(2, keyFrame = true, data = byteArrayOf(1), now = 0),
        )
        val feed = BoundedScrcpyPacketFeed(ByteArrayInputStream(wire))
        try {
            assertTrue(feed.nextPacket()?.config == true)
            var next = feed.nextPacket()
            while (next?.config == true) next = feed.nextPacket()
            assertTrue(next?.keyFrame == true, "the recovery key frame follows any replayed SPS/PPS")
            assertEquals(null, feed.nextPacket(), "EOF arrives after all accepted packets drain")
        } finally {
            feed.close()
        }

        val enteredRead = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val blockedInput = object : InputStream() {
            override fun read(): Int {
                enteredRead.countDown()
                inputClosed.await()
                return -1
            }

            override fun close() {
                inputClosed.countDown()
            }
        }
        val blockedFeed = BoundedScrcpyPacketFeed(blockedInput)
        try {
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS), "packet pump entered its blocking read")
            val waiting = Thread { blockedFeed.nextPacket() }.also { it.start() }
            blockedFeed.close()
            waiting.join(1_000)
            assertTrue(!waiting.isAlive, "close wakes the decoder waiting for a packet")
        } finally {
            blockedFeed.close()
        }
    }

    @Test
    fun composeFallbackConsumesTheExistingPacketConnection() {
        val socketInput = PipedInputStream(1024)
        val socketOutput = PipedOutputStream(socketInput)
        val feed = BoundedScrcpyPacketFeed(socketInput)
        try {
            DataOutputStream(socketOutput).writeInt(ScrcpyCodecIds.H264)
            writePacket(socketOutput, packet(1, config = true, data = byteArrayOf(7), now = 0))
            assertTrue(feed.nextPacket()?.config == true)

            // Switching creates a decoder-side pipe over the existing socket reader. No transport
            // or second connection is involved; its next key frame starts the fallback GOP.
            val compose = feed.switchToCompose()
            writePacket(socketOutput, packet(2, keyFrame = true, data = byteArrayOf(1), now = 0))
            writePacket(socketOutput, packet(3, data = byteArrayOf(2), now = 0))
            socketOutput.close()
            val delivered = compose.input.readBytes()
            assertContentEquals(byteArrayOf(7, 1, 2), delivered.takeLast(3).toByteArray())
            compose.close()
        } finally {
            feed.close()
            runCatching { socketOutput.close() }
        }
    }

    @Test
    fun composeFallbackAfterFeedCloseReturnsEndedInput() {
        val feed = packetFeed(AtomicLong(0))
        feed.close()
        assertContentEquals(byteArrayOf(), feed.switchToCompose().input.readBytes())
    }

    private fun packetFeed(now: AtomicLong) = BoundedScrcpyPacketFeed(
        rawInput = ByteArrayInputStream(byteArrayOf()),
        nanoTime = now::get,
        startPump = false,
    )

    private fun packet(
        ptsUs: Long,
        config: Boolean = false,
        keyFrame: Boolean = false,
        data: ByteArray,
        now: Long,
    ) = BoundedScrcpyPacketFeed.Packet(ptsUs, config, keyFrame, data, now)

    private fun scrcpyPackets(vararg packets: BoundedScrcpyPacketFeed.Packet): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                packets.forEach { packet -> writePacket(this, packet) }
            }
        }.toByteArray()

    private fun writePacket(out: OutputStream, packet: BoundedScrcpyPacketFeed.Packet) {
        DataOutputStream(out).apply {
            var ptsAndFlags = packet.ptsUs
            if (packet.config) ptsAndFlags = ptsAndFlags or (1L shl 62)
            if (packet.keyFrame) ptsAndFlags = ptsAndFlags or (1L shl 61)
            writeLong(ptsAndFlags)
            writeInt(packet.data.size)
            write(packet.data)
        }
    }

}
