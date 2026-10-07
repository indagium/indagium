package com.indagium.testing.run

import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.IndagiumToolGateway

// The lane gateway the agent actually talks to: the lane tools of buildLaneTools behind a guard. [guard] answers a
// Executes each lane call through [guard], which can reject it before dispatch or reserve the case budget atomically
// with sequence state. The confirmation policy of the wrapped tools (ASK scripts) is carried over unchanged.

internal fun cappedGateway(
    base: IndagiumToolGateway,
    guard: suspend (toolName: String, arguments: Map<String, Any?>, execute: suspend () -> Any?) -> Any?,
): IndagiumToolGateway {
    val tools = base.tools
    val needsConfirmation = tools.filter { base.actionPolicy(it.name) == IndagiumToolActionPolicy.CONFIRMATION_REQUIRED }.mapTo(HashSet()) { it.name }
    val descriptions = tools.mapNotNull { tool -> base.confirmationDescription(tool.name)?.let { tool.name to it } }.toMap()
    val handlers: Map<String, suspend (Map<String, Any?>) -> Any?> = tools.associate { tool ->
        tool.name to { arguments: Map<String, Any?> ->
            guard(tool.name, arguments) { base.executeSuspending(tool.name, arguments) }
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
