package com.openlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openlog.debug.ControlServer
import com.openlog.debug.regenerateControlToken
import kotlinx.coroutines.CoroutineScope
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
    // Same "private resource handle, guarded start, idempotent stop" shape as AppState's
    // activeTails. @Volatile (A-01): published by applyControlServerState's ioScope completion
    // handler, read by the calling thread elsewhere — guarantees the reader sees the latest write.
    @Volatile
    private var controlServer: ControlServer? = null

    // Guards applyControlServerState's async enable path (S-02): every call — enable or disable —
    // bumps this before doing anything else, so a start that was already in flight becomes provably
    // stale. Its completion handler only publishes/keeps the server if the generation it captured is
    // still current; otherwise it stops the now-unwanted server itself. controlServerStartJob is
    // tracked purely so stopControlServerForShutdown() can cancel a still-binding start rather than
    // let it complete after shutdown began — the generation check is what actually prevents a stale
    // publish even if cancellation doesn't interrupt a synchronous bind in progress. AtomicInteger,
    // not a plain Int (A-01): the increment/compare happens across the calling thread and an
    // ioScope completion callback with no other memory barrier between them.
    private val controlServerStartGeneration = AtomicInteger(0)

    @Volatile
    private var controlServerStartJob: Job? = null

    // Set when applyControlServerState fails to bind (e.g. port already in use); shown next to
    // the Settings toggle. Cleared on the next successful start.
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
    fun applyControlServerState(enabled: Boolean, port: Int) {
        if (enabled) {
            val running = controlServer
            if (running != null && running.boundPort == port) return
            running?.stop()
            controlServer = null
            mcpControlError = null
            val myGeneration = controlServerStartGeneration.incrementAndGet()
            controlServerStartJob = scope.launch {
                val started = runCatching { controlServerFactory(appState, port) }
                started.fold(
                    onSuccess = { server ->
                        // A later enable/disable call already bumped the generation while this bind
                        // was in flight — this start lost the race, so publishing it now would
                        // resurrect a server the caller believes is stopped or superseded. Close it
                        // instead of leaking a live listener nobody references anymore.
                        if (myGeneration == controlServerStartGeneration.get()) {
                            controlServer = server
                            mcpControlError = null
                        } else {
                            server.stop()
                        }
                    },
                    onFailure = { error ->
                        if (myGeneration != controlServerStartGeneration.get()) return@fold
                        mcpControlError = "Could not start automation server on port $port: " +
                            (error.message ?: error::class.simpleName.orEmpty().ifBlank { "unknown error" })
                        // Only the persisted (Settings-toggle) path needs undoing — the ephemeral
                        // debug-env-var path never sets this in the first place, so this is a
                        // no-op there and doesn't touch the setting it deliberately keeps out of.
                        if (appState.settings.mcpControlEnabled) {
                            appState.settings = appState.settings.copy(mcpControlEnabled = false)
                            appState.autosaveNow()
                        }
                    },
                )
            }
        } else {
            controlServerStartGeneration.incrementAndGet() // invalidate any start still in flight from a prior enable
            controlServerStartJob?.cancel()
            controlServerStartJob = null
            controlServer?.stop()
            controlServer = null
        }
    }

    // Settings-UI path: persists the toggle (autosaved) AND applies it immediately. If the bind
    // fails, applyControlServerState reverts settings.mcpControlEnabled and reports the failure
    // via mcpControlError — re-read here so the just-written autosave reflects the outcome that
    // actually happened, not the request.
    fun setMcpControlEnabled(enabled: Boolean, port: Int) {
        val clamped = port.coerceIn(MIN_PORT, MAX_PORT)
        if (appState.settings.mcpControlEnabled != enabled || appState.settings.mcpControlPort != clamped) {
            appState.settings = appState.settings.copy(mcpControlEnabled = enabled, mcpControlPort = clamped)
            appState.autosaveNow()
        }
        applyControlServerState(enabled, clamped)
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
    // natural restart, defeating the point of a user-triggered rotation. A plain re-enable on the
    // same port is a no-op in applyControlServerState (it already treats "same port, already bound"
    // as nothing to do), so this disables first to force the restart through.
    fun rotateControlToken() {
        regenerateControlToken(controlTokenFile)
        if (controlServer != null) {
            val port = appState.settings.mcpControlPort
            applyControlServerState(enabled = false, port = 0)
            applyControlServerState(enabled = true, port = port)
        }
    }
}
