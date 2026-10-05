package com.indagium.testing.run

import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.IndagiumToolGateway

// The lane gateway the agent actually talks to: the lane tools of buildLaneTools behind a guard. [guard] answers a
// tool name with a refusal text (the call then returns `{ "error": text }` and the tool never runs) or null to let the
// call through. The confirmation policy of the wrapped tools (ASK scripts) is carried over unchanged.

internal fun cappedGateway(base: IndagiumToolGateway, guard: (toolName: String) -> String?): IndagiumToolGateway {
    val tools = base.tools
    val needsConfirmation = tools.filter { base.actionPolicy(it.name) == IndagiumToolActionPolicy.CONFIRMATION_REQUIRED }.mapTo(HashSet()) { it.name }
    val descriptions = tools.mapNotNull { tool -> base.confirmationDescription(tool.name)?.let { tool.name to it } }.toMap()
    val handlers: Map<String, suspend (Map<String, Any?>) -> Any?> = tools.associate { tool ->
        tool.name to { arguments: Map<String, Any?> ->
            val refusal = guard(tool.name)
            if (refusal != null) mapOf("error" to refusal) else base.executeSuspending(tool.name, arguments)
        }
    }
    return IndagiumToolGateway(
        catalog = tools,
        handlers = emptyMap(),
        suspendHandlers = handlers,
        extraConfirmationRequired = needsConfirmation,
        confirmationDescriptions = descriptions,
    )
}
