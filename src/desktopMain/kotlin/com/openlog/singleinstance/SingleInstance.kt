package com.openlog.singleinstance

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

// Pure JVM — no Compose/AWT imports — so this is headless-testable and independent of the
// windowing toolkit Main.kt boots afterward.
//
// Protocol (UTF-8, \n-framed):
//   OPENLOG-SI 1 <token>\n
//   <abs-path>\n
//   …
//   \n                       ← blank line ends the list (zero paths is valid: raise, don't open)
// Reply is a single line, "OK\n" or "ERR\n".
//
// Loopback is reachable by every local user, not just this one — without a per-launch random
// token in the 0600 port file, another local account could make the app open arbitrary paths
// readable by this user. Low impact (content only renders on the victim's own screen), but real
// cross-user injection that a 128-bit random closes for free. Defense in depth, not a boundary
// against the user themselves.
private const val PROTOCOL_MAGIC = "OPENLOG-SI"
private const val PROTOCOL_VERSION = "1"
private const val LOCK_FILE_NAME = "single-instance.lock"
private const val PORT_FILE_NAME = "single-instance.port"

// Bridges the cold-start race where the winner of tryLock() hasn't written the port file yet.
private const val PORT_FILE_RETRY_COUNT = 10
private const val PORT_FILE_RETRY_DELAY_MS = 100L

private const val SOCKET_TIMEOUT_MS = 3000
private const val CONNECT_TIMEOUT_MS = 500
private const val SERVER_SOCKET_BACKLOG = 50

object SingleInstance {
    /**
     * Non-null → this process is primary; run the Compose app (call [SingleInstanceHandle.startAccepting]
     * once a window exists, and [SingleInstanceHandle.close] on shutdown).
     * Null → [args] were forwarded to an already-running instance; the caller should exit with no window.
     * Never throws — every internal failure degrades to a non-null handle with [SingleInstanceHandle.boundPort]
     * == null, meaning "no single-instance behavior this run, just launch normally." Never hangs, never dies.
     */
    fun acquire(baseDir: File, args: Array<String>): SingleInstanceHandle? =
        runCatching { acquireOrForward(baseDir, args) }.getOrElse { degradedHandle() }

    private fun acquireOrForward(baseDir: File, args: Array<String>): SingleInstanceHandle? {
        baseDir.mkdirs()
        val raf = RandomAccessFile(File(baseDir, LOCK_FILE_NAME), "rw")
        val channel = raf.channel
        // tryLock() throws OverlappingFileLockException (rather than returning null) when a lock
        // on an overlapping region is already held elsewhere *in this same JVM* — a same-process
        // second acquire() (as in tests, or a hypothetical re-entrant call) must be treated exactly
        // like "another process holds it," not allowed to propagate and blow the runCatching above
        // into a false "degraded" read before we've even tried to forward.
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            val forwarded = runCatching { tryForward(baseDir, args) }.getOrDefault(false)
            runCatching { channel.close() }
            runCatching { raf.close() }
            return if (forwarded) null else degradedHandle()
        }
        return runCatching { becomePrimary(baseDir, raf, channel, lock) }.getOrElse {
            runCatching { lock.release() }
            runCatching { channel.close() }
            runCatching { raf.close() }
            degradedHandle()
        }
    }

    private fun becomePrimary(baseDir: File, raf: RandomAccessFile, channel: FileChannel, lock: FileLock): SingleInstanceHandle {
        val serverSocket = ServerSocket(0, SERVER_SOCKET_BACKLOG, InetAddress.getLoopbackAddress())
        val token = generateToken()
        val portFile = File(baseDir, PORT_FILE_NAME)
        portFile.writeText("${serverSocket.localPort} $token")
        runCatching { restrictToOwner(portFile) }
        return SingleInstanceHandle(
            boundPort = serverSocket.localPort,
            serverSocket = serverSocket,
            token = token,
            raf = raf,
            lockChannel = channel,
            lock = lock,
            portFile = portFile,
        )
    }

    private fun degradedHandle(): SingleInstanceHandle =
        SingleInstanceHandle(boundPort = null, serverSocket = null, token = null, raf = null, lockChannel = null, lock = null, portFile = null)

    private fun tryForward(baseDir: File, args: Array<String>): Boolean {
        val portFile = File(baseDir, PORT_FILE_NAME)
        var portToken: Pair<Int, String>? = null
        var retriesLeft = PORT_FILE_RETRY_COUNT
        while (portToken == null && retriesLeft > 0) {
            portToken = readPortToken(portFile)
            if (portToken == null) {
                Thread.sleep(PORT_FILE_RETRY_DELAY_MS)
                retriesLeft--
            }
        }
        val (port, token) = portToken ?: return false
        // Mirror Main.kt's own startup-args filtering (args.map(::File).filter { it.exists() })
        // so a forwarded launch behaves identically to a same-process one.
        val paths = args.map(::File).filter { it.exists() }.map { it.absolutePath }
        return forward(port, token, paths)
    }

    // Exposed at internal visibility (not private) so SingleInstanceTest — compiled as a friend of
    // desktopMain — can drive the wire protocol directly without needing a second real process.
    internal fun forward(port: Int, token: String, paths: List<String>): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            writer.write("$PROTOCOL_MAGIC $PROTOCOL_VERSION $token\n")
            paths.forEach { writer.write("$it\n") }
            writer.write("\n")
            writer.flush()
            val reply = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
            reply == "OK"
        }
    }.getOrDefault(false)

    private fun readPortToken(file: File): Pair<Int, String>? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()?.trim() ?: return null
        val parts = text.split(" ")
        if (parts.size != 2) return null
        val port = parts[0].toIntOrNull() ?: return null
        return port to parts[1]
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Best-effort: throws UnsupportedOperationException on Windows (no POSIX permission model),
    // which the caller already wraps in runCatching.
    private fun restrictToOwner(file: File) {
        val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
    }
}

class SingleInstanceHandle internal constructor(
    val boundPort: Int?,
    private val serverSocket: ServerSocket?,
    private val token: String?,
    private val raf: RandomAccessFile?,
    private val lockChannel: FileChannel?,
    private val lock: FileLock?,
    private val portFile: File?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val accepting = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    /** No-op on a degraded handle (boundPort == null) — there is no bound socket to accept on. */
    fun startAccepting(onOpenFiles: (List<File>) -> Unit, onRaise: () -> Unit) {
        val socket = serverSocket ?: return
        if (!accepting.compareAndSet(false, true)) return
        acceptThread = Thread({
            while (!closed.get()) {
                val conn = try {
                    socket.accept()
                } catch (_: java.io.IOException) {
                    // close() interrupts a blocked accept() by closing the socket underneath it,
                    // which surfaces here as an IOException — the only signal this loop needs to
                    // tell "shutting down" apart from "one bad connection attempt."
                    if (closed.get()) return@Thread else continue
                }
                // Each connection is isolated in its own runCatching so one malformed/garbage
                // sender can never kill the accept loop for subsequent, well-formed callers.
                runCatching { handleConnection(conn, onOpenFiles, onRaise) }
            }
        }, "openlog-single-instance-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun isValidHeader(parts: List<String>?): Boolean =
        parts != null && parts.size >= 3 && parts[0] == PROTOCOL_MAGIC && parts[1] == PROTOCOL_VERSION && parts[2] == token

    private fun handleConnection(conn: Socket, onOpenFiles: (List<File>) -> Unit, onRaise: () -> Unit) {
        conn.use {
            conn.soTimeout = SOCKET_TIMEOUT_MS
            val reader = conn.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val parts = reader.readLine()?.split(" ")
            if (!isValidHeader(parts)) {
                reply(conn, ok = false)
                return
            }
            val paths = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: run {
                    reply(conn, ok = false)
                    return
                }
                if (line.isEmpty()) break
                paths.add(line)
            }
            onOpenFiles(paths.map(::File))
            onRaise()
            reply(conn, ok = true)
        }
    }

    private fun reply(conn: Socket, ok: Boolean) {
        runCatching {
            val writer = conn.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            writer.write(if (ok) "OK\n" else "ERR\n")
            writer.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        acceptThread?.let { runCatching { it.join(SOCKET_TIMEOUT_MS.toLong()) } }
        runCatching { lock?.release() }
        runCatching { lockChannel?.close() }
        runCatching { raf?.close() }
        runCatching { portFile?.delete() }
    }
}
