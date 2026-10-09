package com.indagium.ai

import com.indagium.debug.ControlServer
import com.indagium.debug.IndagiumToolGateway
import com.indagium.ui.AppState
import java.io.Closeable

/**
 * A private localhost MCP endpoint for one account-agent request. Its bearer token is random,
 * never persisted, and becomes invalid as soon as the request ends.
 */
internal class ManagedMcpServerLease private constructor(
    private val server: ControlServer,
    private val access: ManagedMcpAccess,
) : Closeable {
    val url: String get() = server.managedMcpUrl()
    val token: String get() = access.token

    override fun close() {
        server.releaseManagedMcpRun(access)
        server.stop()
    }

    companion object {
        /** [gateway] null: the run sees the app's whole tool catalogue; otherwise exactly that gateway's tools. */
        fun start(appState: AppState, run: AiRun, gateway: IndagiumToolGateway? = null): ManagedMcpServerLease =
            start(appState) { server -> server.registerManagedMcpRun(run, gateway) }

        /**
         * A lease that outlives one run: it serves [route]'s tools and answers each call for the target the route has at that moment
         * (see [SwitchableMcpRoute]), refusing it when there is none.
         */
        fun startSwitchable(appState: AppState, route: SwitchableMcpRoute): ManagedMcpServerLease =
            start(appState) { server -> server.registerManagedMcpRoute(route) }

        private fun start(appState: AppState, register: (ControlServer) -> ManagedMcpAccess?): ManagedMcpServerLease {
            val server = ControlServer(appState, port = 0)
            server.start()
            val access = try {
                checkNotNull(register(server)) { "Managed MCP server did not start." }
            } catch (error: Exception) {
                server.stop()
                throw error
            }
            return ManagedMcpServerLease(server, access)
        }
    }
}
