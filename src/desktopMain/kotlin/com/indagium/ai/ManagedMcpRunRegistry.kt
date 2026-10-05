package com.indagium.ai

import com.indagium.debug.IndagiumToolGateway
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A capability scoped to one in-panel account-agent run and invalidated when that run ends. */
internal data class ManagedMcpAccess(val token: String)

/**
 * [gateway] is null for an ordinary panel run (the server then exposes the app's whole tool catalogue). A run that
 * brought its own gateway (an AI test-run lane) exposes exactly that gateway's tools, executed by [toolExecutor].
 */
internal data class ManagedMcpRun(
    val access: ManagedMcpAccess,
    val run: AiRun,
    val toolExecutor: AiToolExecutionCoordinator,
    val gateway: IndagiumToolGateway? = null,
)

/**
 * Keeps account-agent MCP sessions tied to the panel request that created them. It intentionally
 * holds no persisted state and never reuses the ControlServer's long-lived user-facing token.
 */
internal class ManagedMcpRunRegistry(
    toolGateway: IndagiumToolGateway,
    private val maxToolResultChars: Int = 12_000,
    private val onCaptureTabChanged: (String, String) -> Unit = { _, _ -> },
) {
    private val toolExecutor = AiToolExecutionCoordinator(toolGateway, maxToolResultChars, onCaptureTabChanged)
    private val runs = ConcurrentHashMap<String, ManagedMcpRun>()

    /** Registers [run]; a non-null [gateway] replaces the app's tool catalogue for this run only. */
    fun register(run: AiRun, gateway: IndagiumToolGateway? = null): ManagedMcpAccess {
        val access = ManagedMcpAccess(newToken())
        val executor = if (gateway == null) toolExecutor else AiToolExecutionCoordinator(gateway, maxToolResultChars, onCaptureTabChanged)
        runs[access.token] = ManagedMcpRun(access, run, executor, gateway)
        return access
    }

    fun get(token: String?): ManagedMcpRun? = token?.let(runs::get)

    fun remove(token: String): ManagedMcpRun? = runs.remove(token)

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TOKEN_BYTES = 16
        val secureRandom = SecureRandom()
    }
}
