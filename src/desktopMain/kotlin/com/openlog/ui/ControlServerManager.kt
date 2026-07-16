package com.openlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openlog.debug.ControlServer
import com.openlog.debug.regenerateControlToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// Extracted from AppState (Task 12 slice 1, mechanical — no behavior change): owns the MCP/debug
// control server's lifecycle (start/stop/rebind, the S-02/A-01 generation-counter race guard) and
// the small set of read-only accessors the Settings UI's "Connection info" dialog uses.
// AppState still owns settings persistence/autosave, and controlServerFactory still takes the
// owning AppState (ControlServer's MCP tool handlers call back into it to open/close/filter tabs)
// — this groups the control-server's own state and lifecycle methods into one place without
// changing that coupling, which is a design question for a later slice, not this one.
internal class ControlServerManager(
    private val appState: AppState,
    private val scope: CoroutineScope,
    private val controlTokenFile: File,
    private val controlServerFactory: suspend (AppState, Int) -> ControlServer,
) {
    private enum class RuntimeSource {
        PERSISTED,
        SESSION_ONLY,
    }

    private data class RuntimeRequest(
        val port: Int,
        val source: RuntimeSource,
    )

    private val lifecycleLock = Any()

    // Same "private resource handle, guarded start, idempotent stop" shape as AppState's
    // activeTails. @Volatile (A-01): published by the ioScope completion handler and read by
    // Settings/UI callers — guarantees those readers see the latest write.
    @Volatile
    private var controlServer: ControlServer? = null

    // Guards the async enable path (S-02): every replacement or disable bumps this, so a start
    // already in flight becomes provably stale. Its completion handler only publishes/keeps the
    // server if the generation it captured is still current; otherwise it stops the unwanted
    // server itself. The Job lets shutdown cancel a still-binding start, while the generation
    // remains the authority when cancellation cannot interrupt a synchronous factory/bind.
    private val controlServerStartGeneration = AtomicInteger(0)

    private var controlServerStartJob: Job? = null

    // The requested runtime state is deliberately separate from the published server handle.
    // In particular, a still-binding start is already "desired and active" for restart purposes:
    // CORS/token changes must supersede it using this exact port/source instead of consulting the
    // persisted settings (which would silently change a session-only server's semantics).
    private var desiredRuntime: RuntimeRequest? = null

    // Set when the current desired start fails to bind (e.g. port already in use); shown next to
    // the Settings toggle. Cleared when a new start begins and on its successful publication.
    var mcpControlError by mutableStateOf<String?>(null)
        private set

    // Actually starts/stops the server, independent of whether the transition should be
    // persisted — see setMcpControlEnabled (persists) vs startControlServerForThisSessionOnly
    // (doesn't). Guards against double-starting the same port (ControlServer.start() isn't
    // safely re-callable while already running) and rebinds if the port actually changed.
    //
    // ControlServer.start() binds a real socket (HttpServer.create). Two distinct failure modes:
    // (1) it throws fast — typically BindException: Address already in use — handled below by
    // catching and reverting the persisted toggle so a failed bind can't crash-loop every future
    // launch (a past version let this propagate uncaught and crash the whole JVM); (2) it can
    // also just be SLOW rather than fail — e.g. macOS's first-time "accept incoming connections"
    // firewall prompt, or VPN/security software intercepting the bind — with no fixed bound on
    // how slow. This function is called both from a Settings-toggle click and from Main.kt's
    // startup DisposableEffect, both of which used to run it synchronously on the Compose UI
    // thread, so a slow bind froze the entire window for as long as it took, indistinguishable
    // from a real hang. Every other blocking operation in AppState already runs on ioScope for
    // exactly this reason (see openFile, mergeTabs, etc.) — the control server was the one
    // exception. Only the START side needs this: stop() just tears down an already-bound socket
    // (no network I/O to block on) and must stay synchronous so the Main.kt shutdown path
    // (stopControlServerForShutdown, called from onDispose right before the process exits) can't
    // race a fire-and-forget coroutine that might never get scheduled before exit.
    private fun startDesiredLocked(request: RuntimeRequest, forceRestart: Boolean = false) {
        val existingJob = controlServerStartJob
        if (!forceRestart &&
            desiredRuntime == request &&
            (existingJob?.isActive == true || controlServer?.boundPort == request.port)
        ) {
            return
        }

        desiredRuntime = request
        mcpControlError = null
        val myGeneration = controlServerStartGeneration.incrementAndGet()
        existingJob?.cancel()
        controlServerStartJob = null
        controlServer?.stop()
        controlServer = null

        // LAZY makes assignment deterministic: even a factory that returns immediately cannot
        // finish and clear the current job before this field points at its generation.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = runCatching { controlServerFactory(appState, request.port) }
            started.fold(
                onSuccess = { server ->
                    val isCurrent = synchronized(lifecycleLock) {
                        if (myGeneration == controlServerStartGeneration.get() && desiredRuntime == request) {
                            controlServer = server
                            controlServerStartJob = null
                            mcpControlError = null
                            true
                        } else {
                            false
                        }
                    }
                    // Cancellation cannot interrupt every synchronous bind/factory. If an older
                    // generation nevertheless succeeds, it owns cleanup of its unpublishable
                    // listener and must not touch any newer generation's fields.
                    if (!isCurrent) server.stop()
                },
                onFailure = { error ->
                    synchronized(lifecycleLock) {
                        if (myGeneration != controlServerStartGeneration.get() || desiredRuntime != request) {
                            return@fold
                        }
                        controlServerStartJob = null
                        desiredRuntime = null
                        mcpControlError = "Could not start automation server on port ${request.port}: " +
                            (error.message ?: error::class.simpleName.orEmpty().ifBlank { "unknown error" })
                        // A session/environment request never owns the persisted toggle, even if
                        // settings happen to say enabled for some separate saved configuration.
                        if (request.source == RuntimeSource.PERSISTED && appState.settings.mcpControlEnabled) {
                            appState.settings = appState.settings.copy(mcpControlEnabled = false)
                            appState.autosaveNow()
                        }
                    }
                },
            )
        }
        controlServerStartJob = job
        job.start()
    }

    private fun restartDesiredLocked() {
        desiredRuntime?.let { startDesiredLocked(it, forceRestart = true) }
    }

    private fun stopDesiredLocked() {
        desiredRuntime = null
        controlServerStartGeneration.incrementAndGet()
        controlServerStartJob?.cancel()
        controlServerStartJob = null
        controlServer?.stop()
        controlServer = null
    }

    // Settings-UI path: persists the toggle (autosaved) AND applies it immediately. If this
    // persisted enable request fails, startDesiredLocked reverts settings.mcpControlEnabled and
    // reports the failure via mcpControlError.
    fun setMcpControlEnabled(enabled: Boolean, port: Int) {
        val clamped = port.coerceIn(MIN_PORT, MAX_PORT)
        synchronized(lifecycleLock) {
            if (appState.settings.mcpControlEnabled != enabled || appState.settings.mcpControlPort != clamped) {
                appState.settings = appState.settings.copy(mcpControlEnabled = enabled, mcpControlPort = clamped)
                appState.autosaveNow()
            }
            if (enabled) {
                startDesiredLocked(RuntimeRequest(clamped, RuntimeSource.PERSISTED))
            } else {
                stopDesiredLocked()
            }
        }
    }

    fun startControlServerForThisSessionOnly(port: Int) {
        synchronized(lifecycleLock) {
            startDesiredLocked(RuntimeRequest(port.coerceIn(MIN_PORT, MAX_PORT), RuntimeSource.SESSION_ONLY))
        }
    }

    fun stopControlServer() {
        synchronized(lifecycleLock) {
            stopDesiredLocked()
        }
    }

    // (SEC-1) CORS is a Ktor plugin installed once at server start — toggling the setting while
    // the server is active has no effect until the server restarts, so this replaces the current
    // desired generation even while its bind is still in flight. controlServerFactory reads the
    // new setting value fresh on that restart (see AppState's factory default). A no-op
    // restart-wise when no runtime server is desired.
    fun setMcpAllowBrowserClients(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (appState.settings.mcpAllowBrowserClients != enabled) {
                appState.settings = appState.settings.copy(mcpAllowBrowserClients = enabled)
                appState.autosaveNow()
            }
            restartDesiredLocked()
        }
    }

    // Read/mutated by the Settings "Connection info" popup — in-process passthrough to the
    // running ControlServer, no HTTP round trip needed since both live in the same JVM.
    fun connectedMcpClients() = controlServer?.connectedClients() ?: emptyList()

    fun blockMcpClient(id: String) = controlServer?.blockClient(id)

    fun unblockMcpClient(id: String) = controlServer?.unblockClient(id)

    fun mcpSessions() = controlServer?.mcpSessions() ?: emptyList()

    fun disconnectMcpSession(id: String) = controlServer?.disconnectMcpSession(id)

    // Null while the server isn't running (including mid-start) — the Connection Info dialog only
    // has something to show once this is non-null.
    fun controlServerToken(): String? = controlServer?.token

    // Settings "Regenerate token" action: writes a brand-new persisted token (invalidating every
    // previously-copied MCP client config), then restarts the live server so it actually starts
    // enforcing the new token immediately — otherwise the old one would stay valid until the next
    // natural restart, defeating the point of a user-triggered rotation. The forced replacement
    // also supersedes an in-flight start that may already have captured the old token.
    fun rotateControlToken() {
        synchronized(lifecycleLock) {
            regenerateControlToken(controlTokenFile)
            restartDesiredLocked()
        }
    }
}
