package com.openlog

import com.openlog.singleinstance.SingleInstance
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceTest {
    @Test
    fun forwardRoundTripOpensAndRaisesThenSecondProcessExits() {
        val baseDir = createTempDirectory("openlog-si-roundtrip").toFile()
        val file1 = File(baseDir, "a.log").apply { writeText("x") }
        val file2 = File(baseDir, "b.log").apply { writeText("y") }

        val primary = SingleInstance.acquire(baseDir, emptyArray())
        assertNotNull(primary, "first acquire() in an empty dir must be primary")
        assertNotNull(primary.boundPort, "primary must have a bound accept socket")

        val opened = mutableListOf<File>()
        var raiseCount = 0
        primary.startAccepting(onOpenFiles = { opened.addAll(it) }, onRaise = { raiseCount++ })
        try {
            // Same-process second acquire() hits OverlappingFileLockException on the lock — the
            // exact conflict a second real process launched via "Open With" would produce via a
            // plain failed tryLock() — and must forward instead of also becoming primary.
            val second = SingleInstance.acquire(baseDir, arrayOf(file1.absolutePath, file2.absolutePath))
            assertNull(second, "a losing acquire() must forward and return null so the caller exits with no window")

            waitUntil { opened.size == 2 }
            assertEquals(setOf(file1.absolutePath, file2.absolutePath), opened.map { it.absolutePath }.toSet())
            assertEquals(1, raiseCount, "onRaise must fire exactly once per forwarded launch")
        } finally {
            primary.close()
        }
    }

    @Test
    fun wedgedPrimaryFallsBackToNormalLaunch() {
        val baseDir = createTempDirectory("openlog-si-wedged").toFile()
        baseDir.mkdirs()
        // Hold the lock manually, bypassing SingleInstance entirely, so acquire() below takes the
        // "someone else holds it" branch exactly as it would against a real (possibly-crashed but
        // not-yet-reaped) primary process.
        val raf = RandomAccessFile(File(baseDir, "single-instance.lock"), "rw")
        val holderLock = raf.channel.tryLock()
        assertNotNull(holderLock)
        try {
            // A stale port file pointing at a port nothing is listening on (bind-then-release
            // immediately, the repo's standard "hand back a free port" idiom) simulates a primary
            // that died without cleaning up its own port file.
            val deadPort = ServerSocket(0).use { it.localPort }
            File(baseDir, "single-instance.port").writeText("$deadPort sometoken")

            val handle = SingleInstance.acquire(baseDir, emptyArray())
            assertNotNull(handle, "a failed forward must degrade to a runnable handle, never null")
            assertNull(handle.boundPort, "a degraded handle must not claim to be a real primary")
            // startAccepting/close must both be safe no-ops on a degraded handle.
            handle.startAccepting(onOpenFiles = {}, onRaise = {})
            handle.close()
        } finally {
            holderLock.release()
            raf.close()
        }
    }

    @Test
    fun wrongTokenIsRejectedAndDoesNotOpen() {
        val baseDir = createTempDirectory("openlog-si-badtoken").toFile()
        val primary = SingleInstance.acquire(baseDir, emptyArray())
        assertNotNull(primary)
        val opened = mutableListOf<File>()
        primary.startAccepting(onOpenFiles = { opened.addAll(it) }, onRaise = {})
        try {
            val ok = SingleInstance.forward(primary.boundPort!!, "bogus-token", listOf("/tmp/should-not-open"))
            assertFalse(ok, "a wrong-token forward must be rejected, not accepted as OK")
            Thread.sleep(150)
            assertTrue(opened.isEmpty(), "onOpenFiles must never fire for a rejected (wrong-token) connection")
        } finally {
            primary.close()
        }
    }

    @Test
    fun malformedInputDoesNotKillAcceptLoop() {
        val baseDir = createTempDirectory("openlog-si-malformed").toFile()
        val primary = SingleInstance.acquire(baseDir, emptyArray())
        assertNotNull(primary)
        val opened = mutableListOf<File>()
        primary.startAccepting(onOpenFiles = { opened.addAll(it) }, onRaise = {})
        try {
            // No protocol header at all, no trailing newline — just garbage, then a plain socket
            // close. The accept loop must discard this connection and keep serving.
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), primary.boundPort!!), 500)
                s.getOutputStream().write("this is not the openlog-si protocol".toByteArray())
                s.getOutputStream().flush()
            }
            Thread.sleep(150) // let the garbage connection drain through the accept loop once

            val file = File(baseDir, "c.log").apply { writeText("z") }
            val second = SingleInstance.acquire(baseDir, arrayOf(file.absolutePath))
            assertNull(second, "a well-formed forward right after a malformed one must still succeed")
            waitUntil { opened.size == 1 }
            assertEquals(file.absolutePath, opened.first().absolutePath)
        } finally {
            primary.close()
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
