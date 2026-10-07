package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.judgeActive
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.PausedStepInfo
import com.indagium.testing.run.PendingTestConfirmation
import com.indagium.testing.run.readBoundedRunArtifact
import com.indagium.testing.run.resolveRunArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The live view of a RUNNING test run, shown at the top of its report: one column per lane (who drives it, on which device,
// what it is doing, its step checklist, its latest tool calls and screenshot), the cards that need the user (a confirmation
// to Allow once or Deny, a pause to Retry, Continue or Stop) and the judge feed. Lanes wrap onto more rows when the window
// is narrow. The screenshot thumbnail is read and decoded on IO and only when the newest file changes; nothing here blocks
// the UI thread. Text that came from the device, an agent or a model is shown, never acted on.

private val THUMBNAIL_MAX_HEIGHT = 140.dp
private const val DETAIL_ALPHA = .75f

@Composable
internal fun TestRunLiveView(run: TestRun, runDir: File, tick: Int) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val coordinator = ui.state.testRunCoordinator
    val profiles = ui.state.settings.aiProviderProfiles
    val paused = coordinator.isPaused(run.id)
    val columns = remember(run, tick) {
        liveColumns(
            run, profiles, { laneId -> coordinator.recentToolCalls(run.id, laneId, LIVE_TOOL_FEED_LINES) },
            coordinator.pendingConfirmations(), coordinator.pausedSteps(),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("Live", color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (paused) TestsBadge("paused: lanes wait at their next step")
        AppButton(if (paused) "Resume all" else "Pause all", onClick = { coordinator.setPaused(run.id, !paused); ui.reclaimFocus() })
        AppButton("Stop", onClick = { coordinator.cancel(run.id); ui.reclaimFocus() }, isDanger = true)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val perRow = liveGridColumns(maxWidth.value, columns.size)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            columns.chunked(perRow).forEach { rowColumns ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowColumns.forEach { LaneColumn(run, it, runDir, tick, Modifier.weight(1f)) }
                    repeat(perRow - rowColumns.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (run.config.judgeActive) JudgeFeed(run)
}

// ── One lane ─────────────────────────────────────────────────────────

@Composable
private fun LaneColumn(run: TestRun, column: LiveLaneColumn, runDir: File, tick: Int, modifier: Modifier) {
    val tc = tc()
    Column(modifier.background(tc.p, CORNER_MD).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText(
                column.title,
                color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(column.status.label(), runColor(column.status))
        }
        AppText(column.deviceSerial, color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = 1)
        column.currentCase?.let { AppText(it, color = tc.ts, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (column.status == RunStatus.RUNNING) {
            column.currentStep?.let { AppText(it, color = tc.tx, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        column.error?.let { TestsErrorText(it) }
        Thumbnail(column.screenshotPath, runDir)
        LaneLogcatPreview(column.logcatPath, runDir, tick)
        column.steps.forEach { StepLine(it) }
        if (column.toolCalls.isNotEmpty()) {
            AppText("LATEST TOOL CALLS", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            column.toolCalls.asReversed().forEach {
                AppText(it, color = tc.ts.copy(alpha = DETAIL_ALPHA), fontSize = 10.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        column.issues.forEach { IssueCreatedCard(it) }
        column.confirmations.forEach { ConfirmationCard(run.id, it) }
        column.pause?.let { PauseCard(run.id, it) }
    }
}

@Composable
private fun LaneLogcatPreview(relativePath: String?, runDir: File, tick: Int) {
    if (relativePath == null) return
    val tc = tc()
    val preview by produceState<String?>(null, relativePath, tick) {
        value = withContext(Dispatchers.IO) { readBoundedLaneLogcat(runDir, relativePath) }
    }
    preview?.takeIf(String::isNotBlank)?.let { text ->
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(tc.p, CORNER_SM).padding(6.dp)) {
            AppText("LIVE LOGCAT · LAST LINES", color = tc.td, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
            AppText(text, color = tc.ts, fontSize = 9.sp, fontFamily = MONO, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun readBoundedLaneLogcat(runDir: File, relativePath: String): String? = runCatching {
    val file = resolveRunArtifact(runDir, relativePath) ?: return@runCatching null
    java.io.RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        val start = (length - MAX_LIVE_LOGCAT_BYTES).coerceAtLeast(0L)
        input.seek(start)
        val bytes = ByteArray((length - start).toInt())
        input.readFully(bytes)
        bytes.toString(Charsets.UTF_8).lineSequence().toList().takeLast(LIVE_LOGCAT_LINES).joinToString("\n").takeLast(MAX_LIVE_LOGCAT_CHARS)
    }
}.getOrNull()

private const val MAX_LIVE_LOGCAT_BYTES = 32 * 1024L
private const val LIVE_LOGCAT_LINES = 16
private const val MAX_LIVE_LOGCAT_CHARS = 4_000
private const val MAX_LIVE_SCREENSHOT_BYTES = 16 * 1024 * 1024
private const val MAX_LIVE_SCREENSHOT_PIXELS = 36_000_000L

@Composable
private fun StepLine(line: LiveStepLine) {
    val tc = tc()
    val color = if (line.status != null) stepColor(line.status) else if (line.current) tc.ac else tc.td
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        AppText(stepGlyph(line), color = color, fontSize = 11.sp, fontFamily = MONO)
        AppText("${line.number}. ${line.action}", color = if (line.current) tc.tx else tc.ts, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun stepGlyph(line: LiveStepLine): String = when {
    line.status == StepStatus.PASS -> "✓"
    line.status == StepStatus.FAIL || line.status == StepStatus.ERROR || line.status == StepStatus.TIMEOUT -> "✕"
    line.status == StepStatus.BLOCKED -> "!"
    line.status == StepStatus.SKIPPED -> "–"
    line.current -> "▶"
    else -> "·"
}

@Composable
private fun Thumbnail(relativePath: String?, runDir: File) {
    if (relativePath == null) return
    val bitmap by produceState<ImageBitmap?>(null, relativePath) {
        value = withContext(Dispatchers.IO) {
            readBoundedRunArtifact(runDir, relativePath, MAX_LIVE_SCREENSHOT_BYTES)?.let { bytes ->
                runCatching {
                    decodeBoundedPreviewImage(bytes, MAX_LIVE_SCREENSHOT_PIXELS)
                }.getOrNull()
            }
        }
    }
    bitmap?.let {
        Image(
            it,
            contentDescription = "Latest screenshot of the lane",
            contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = THUMBNAIL_MAX_HEIGHT).padding(vertical = 4.dp),
        )
    }
}

// ── Cards that need the user ─────────────────────────────────────────

@Composable
private fun ConfirmationCard(runId: String, card: PendingTestConfirmation) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val coordinator = ui.state.testRunCoordinator
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(tc.warnBg, CORNER_MD).padding(8.dp)) {
        AppText("The agent wants to: ${card.description}", color = tc.tx, fontSize = 11.sp, maxLines = 3)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                "Allow once",
                onClick = {
                    coordinator.resolveConfirmation(runId, card.confirmationId, true)
                    ui.reclaimFocus()
                },
                variant = ButtonVariant.Primary,
            )
            AppButton("Deny", onClick = { coordinator.resolveConfirmation(runId, card.confirmationId, false); ui.reclaimFocus() })
        }
    }
}

/** "Issue draft created": the engine made a draft for a step set to create an issue; the card opens it for review. */
@Composable
private fun IssueCreatedCard(line: LiveIssueLine) {
    val tc = tc()
    val ui = LocalTestsUi.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(tc.warnBg, CORNER_MD).padding(8.dp)) {
        AppText("Issue draft created for step ${line.stepNumber}: ${line.action}", color = tc.tx, fontSize = 11.sp, maxLines = 2)
        AppText(line.caseName, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AppButton(
            "Review issue…",
            onClick = { ui.view.issueDialog = IssueDialogTarget.Existing(line.issueId) },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val PAUSE_OBSERVATION_CHARS = 300

@Composable
private fun PauseCard(runId: String, paused: PausedStepInfo) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val coordinator = ui.state.testRunCoordinator

    fun decide(decision: PauseDecision) {
        coordinator.resumePausedStep(runId, paused.laneId, decision)
        ui.reclaimFocus()
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(tc.warnBg, CORNER_MD).padding(8.dp)) {
        AppText("Paused at step ${paused.step.stepNumber}: ${paused.step.action}", color = tc.tx, fontSize = 11.sp, maxLines = 2)
        AppText("${paused.step.status.label()} — ${paused.step.observation.take(PAUSE_OBSERVATION_CHARS)}", color = tc.ts, fontSize = 10.sp, maxLines = 3)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Retry", onClick = { decide(PauseDecision.RETRY) })
            AppButton("Continue", onClick = { decide(PauseDecision.CONTINUE) }, variant = ButtonVariant.Primary)
            AppButton("Stop", onClick = { decide(PauseDecision.STOP) }, isDanger = true)
        }
    }
}

// ── Judge feed ───────────────────────────────────────────────────────

@Composable
private fun JudgeFeed(run: TestRun) {
    val tc = tc()
    val items = remember(run) { judgeFeed(run) }
    TestsSectionTitle("Judge")
    if (items.isEmpty()) {
        TestsHint("No verdict yet. The judge looks at the evidence only and never sees what an agent claimed.")
        return
    }
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                StatusChip(item.verdict.label(), verdictColor(item.verdict))
                Column(Modifier.weight(1f)) {
                    AppText(
                        "${item.lane} · ${item.caseName.ifBlank { "case" }} · step ${item.stepNumber}",
                        color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.reasoning.isNotBlank()) AppText(item.reasoning, color = tc.tx, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun verdictColor(verdict: JudgeVerdict): Color = when (verdict) {
    JudgeVerdict.PASS -> tc().ok
    JudgeVerdict.FAIL -> DANGER_RED
    JudgeVerdict.INCONCLUSIVE -> tc().warn
}
