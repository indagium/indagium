package com.openlog.ai

import com.openlog.debug.OpenLogToolGateway
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A capability scoped to one in-panel account-agent run and invalidated when that run ends. */
internal data class ManagedMcpAccess(val token: String)

internal data class ManagedMcpRun(
    val access: ManagedMcpAccess,
    val run: AiRun,
    val toolExecutor: AiToolExecutionCoordinator,
)

/**
 * Keeps account-agent MCP sessions tied to the panel request that created them. It intentionally
 * holds no persisted state and never reuses the ControlServer's long-lived user-facing token.
 */
internal class ManagedMcpRunRegistry(
    toolGateway: OpenLogToolGateway,
    maxToolResultChars: Int = 12_000,
) {
    private val toolExecutor = AiToolExecutionCoordinator(toolGateway, maxToolResultChars)
    private val runs = ConcurrentHashMap<String, ManagedMcpRun>()

    fun register(run: AiRun): ManagedMcpAccess {
        val access = ManagedMcpAccess(newToken())
        runs[access.token] = ManagedMcpRun(access, run, toolExecutor)
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
