package com.indagium.ai

import com.indagium.model.AiRunTiming

private const val MILLIS_PER_SECOND = 1_000.0

/**
 * Where the time of [run] went, the one calculation behind the judge's `judge_finished` line and the app-log line of a recording
 * rewrite or a step draft. Every duration is measured from [AiRun.createdAt] on the run's own clock:
 *
 * - `startupMs`: until the agent was ready to take its prompt ([AiRun.agentReadyAt]). Account agents: Codex has its process up,
 *   its managed MCP server connected (or has accepted the turn), Claude Code has printed its first stream line. An in-app model
 *   over HTTP has nothing to start, so it is ready as the first request goes out (about 0 ms).
 * - `firstResponseMs`: until the first model reply, narration or tool call ([AiRun.firstResponseAt]).
 * - `modelTurns`: model responses received ([AiRun.modelResponseTimes]): an HTTP stream that completed, a Codex token-usage
 *   report with a new total, a Claude Code assistant turn. A lower bound, never an estimate; 0 for an agent that reports none.
 * - `toolCalls`: tool calls the model requested, whether or not they were allowed to run.
 * - `verdictMs`: until the model first requested one of [answerToolNames] (a judge's `submit_verdict`); null when it never did.
 * - `totalMs`: until [endedAt], which is when the caller got its answer back, so it includes winding the agent down.
 * - tokens: the run's reported usage ([aiUsageFromHistory]); null when the provider reports none.
 *
 * A [partial] run (cancelled, failed, timed out, or one that never answered) is flagged so nobody compares it with a complete one.
 */
internal fun aiRunTiming(
    run: AiRun,
    endedAt: Long = run.now(),
    answerToolNames: Set<String> = emptySet(),
    partial: Boolean = false,
): AiRunTiming {
    val started = run.createdAt
    val timed = run.timedHistory

    fun since(at: Long?): Long? = at?.let { (it - started).coerceAtLeast(0L) }
    val usage = aiUsageFromHistory(timed.map { it.event })
    val verdictAt = timed.firstOrNull { (it.event as? AiRunEvent.ToolRequested)?.call?.name in answerToolNames }?.at
    return AiRunTiming(
        startupMs = since(run.agentReadyAt),
        firstResponseMs = since(run.firstResponseAt),
        modelTurns = run.modelResponseTimes.size,
        toolCalls = timed.count { it.event is AiRunEvent.ToolRequested },
        verdictMs = since(verdictAt),
        totalMs = (endedAt - started).coerceAtLeast(0L),
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        partial = partial,
    )
}

/** One line for the app log: `startup 2.1 s, first response 3.0 s, 4 model turns, 3 tool calls, verdict 8.8 s, total 9.8 s, ...`. */
internal fun AiRunTiming.logSummary(): String = listOfNotNull(
    startupMs?.let { "startup ${seconds(it)}" },
    firstResponseMs?.let { "first response ${seconds(it)}" },
    "$modelTurns model turns",
    "$toolCalls tool calls",
    verdictMs?.let { "verdict ${seconds(it)}" },
    "total ${seconds(totalMs)}",
    "tokens in ${inputTokens ?: "n/a"} out ${outputTokens ?: "n/a"}",
    "partial".takeIf { partial },
).joinToString(", ")

private fun seconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f s", ms / MILLIS_PER_SECOND)
