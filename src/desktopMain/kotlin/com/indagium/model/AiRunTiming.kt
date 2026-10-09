package com.indagium.model

import java.util.Locale

private const val MILLIS_PER_SECOND = 1_000.0

/**
 * Where the time of one agent run went (a judge, a recording rewrite, a step draft). Every duration is measured from the moment the
 * run was created, on the run's own clock, so the parts add up to what the caller waited for. Null means the run never got there
 * (or, for [verdictMs], that the run has no verdict). The definitions of each part are on `aiRunTiming` in the `ai` package.
 */
data class AiRunTiming(
    /** Run created until the agent was ready to take its prompt (process and MCP server up; an in-app model has nothing to start). */
    val startupMs: Long? = null,
    /** Run created until the model's first reply, narration or tool call. */
    val firstResponseMs: Long? = null,
    /** How many model responses the run received. A lower bound where the provider reports less than one signal per response. */
    val modelTurns: Int = 0,
    /** How many tool calls the model requested. */
    val toolCalls: Int = 0,
    /** Run created until the model submitted its answer through the final tool (a judge's verdict); null when it never did. */
    val verdictMs: Long? = null,
    /** Run created until the caller had everything back, including winding the agent down. */
    val totalMs: Long = 0L,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** The run ended without its answer (cancelled, failed, timed out): the other parts cover only what happened. */
    val partial: Boolean = false,
)

/** "4.2 s (2 model turns)": the line a person reads next to a judge's verdict. */
fun AiRunTiming.summaryLabel(): String {
    val seconds = String.format(Locale.ROOT, "%.1f s", totalMs / MILLIS_PER_SECOND)
    val turns = "$modelTurns model turn${if (modelTurns == 1) "" else "s"}"
    return "$seconds ($turns${if (partial) ", partial" else ""})"
}
