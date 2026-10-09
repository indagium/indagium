package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A capability scoped to one in-panel account-agent run and invalidated when that run ends. */
internal data class ManagedMcpAccess(val token: String)

/** Who tool calls of a managed MCP session are answered for right now: the run that books them and the executor that applies its policy. */
internal class ManagedMcpTarget(val run: AiRun, val toolExecutor: AiToolExecutionCoordinator)

/**
 * What a managed MCP session serves. [tools] is null for an ordinary panel run (the server then exposes the app's whole tool
 * catalogue); a run that brought its own gateway (an AI test-run lane) exposes exactly that gateway's tools. [targetSource] is
 * fixed for those, and a switchable route (see [SwitchableMcpRoute]) answers with whichever target is active at the moment of the
 * call, or null when none is.
 */
internal data class ManagedMcpRun(
    val access: ManagedMcpAccess,
    val tools: List<IndagiumToolDescriptor>?,
    val targetSource: () -> ManagedMcpTarget?,
) {
    /** The target for a tool call arriving now; null when the session has none, in which case the call is refused. */
    fun currentTarget(): ManagedMcpTarget? = targetSource()
}

/** What a managed tool call is told when its session has no active target (the judge it belonged to has ended). */
internal const val NO_ACTIVE_MCP_TARGET_MESSAGE = "This tool session is not active: the request it belonged to has ended. No tool was run."

/**
 * Where the tool calls of one long-lived managed MCP lease go: a Codex process kept for several judges in a row (see
 * [CodexJudgeHost]) keeps one lease, and each judge points it at its own run and tools while it works. [catalog] is what the
 * session lists (fixed for the lease's life); [activate] and [clear] are called one judge at a time. A call that arrives while
 * no judge is active is refused, so a tool call that a finished judge's model still made cannot reach the next judge's evidence.
 */
internal class SwitchableMcpRoute(val catalog: List<IndagiumToolDescriptor>) {
    @Volatile
    private var target: ManagedMcpTarget? = null

    /** Sends tool calls to [run], executed on [gateway], until [clear] or the next [activate]. */
    fun activate(run: AiRun, gateway: IndagiumToolGateway) {
        target = ManagedMcpTarget(run, AiToolExecutionCoordinator(gateway))
    }

    /** From now on calls are refused. */
    fun clear() {
        target = null
    }

    fun current(): ManagedMcpTarget? = target
}

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
        val executor = if (gateway == null) toolExecutor else AiToolExecutionCoordinator(gateway, maxToolResultChars, onCaptureTabChanged)
        val target = ManagedMcpTarget(run, executor)
        return add(gateway?.tools) { target }
    }

    /** Registers a session that serves [route]'s catalogue and answers for whichever target the route has at each call. */
    fun register(route: SwitchableMcpRoute): ManagedMcpAccess = add(route.catalog, route::current)

    private fun add(tools: List<IndagiumToolDescriptor>?, targetSource: () -> ManagedMcpTarget?): ManagedMcpAccess {
        val access = ManagedMcpAccess(newToken())
        runs[access.token] = ManagedMcpRun(access, tools, targetSource)
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
