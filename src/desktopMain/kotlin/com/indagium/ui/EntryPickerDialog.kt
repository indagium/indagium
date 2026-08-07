@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.utils.ZipLogCandidate

// Mechanical extraction of the archive picker dialog that used to live inline in App.kt's
// pendingZipPicker?.let {} block, parameterized so both PendingZipPicker (an archive) and
// PendingFolderPicker (a folder) can share one dialog composable — see the plan's "share the
// dialog, not the plumbing" note (AppState.kt's PendingZipPicker/PendingFolderPicker doc
// comments). [sourceLabel] replaces the old direct "${pending.zipFile.name}" text so this has no
// File dependency at all; the archive and folder call sites each pass their own File's .name.
// [truncatedNotice] is new (archive scans are never truncated, only folder scans are — see
// FolderScan.kt) and renders nothing when null, so the archive picker's appearance is unchanged.
@Composable
internal fun EntryPickerDialog(
    sourceLabel: String,
    candidates: List<ZipLogCandidate>,
    videoCandidates: List<ZipLogCandidate>,
    truncatedNotice: String? = null,
    onCancel: () -> Unit,
    onConfirm: (selected: List<ZipLogCandidate>, video: ZipLogCandidate?) -> Unit,
) {
    // Keyed on sourceLabel + candidates (not just candidates) so switching between two different
    // sources that happen to report the exact same candidate list still resets the selection
    // rather than carrying over a stale one from remember's identity-only key comparison.
    var selected by remember(sourceLabel, candidates) {
        mutableStateOf(if (candidates.size == 1) setOf(candidates.single().entryPath) else emptySet())
    }
    // A lone recording is preselected only in this visible dialog. It is never attached by the
    // scan itself or by the confirm callback's implicit state.
    var selectedVideoPath by remember(sourceLabel, videoCandidates) {
        mutableStateOf(videoCandidates.singleOrNull()?.entryPath)
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        val tc2 = tc()
        Column(
            Modifier.width(420.dp).background(tc2.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText(
                if (candidates.size == 1) "Log file found" else "Multiple log files found",
                color = tc2.tx,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            AppText(
                "\"$sourceLabel\" contains ${candidates.size} candidate log file" +
                    if (candidates.size == 1) ". Choose whether to open it." else "s. Choose which to open — each opens as its own tab.",
                color = tc2.td,
                fontSize = 11.sp,
                maxLines = 3,
            )
            if (truncatedNotice != null) {
                Spacer(Modifier.height(4.dp))
                AppText(truncatedNotice, color = tc2.td, fontSize = 10.sp, maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { candidate ->
                    val displayEntryPath = zipEntryPathForDisplay(candidate.entryPath)
                    CheckRow(
                        checked = candidate.entryPath in selected,
                        onToggle = {
                            selected = if (candidate.entryPath in selected) {
                                selected - candidate.entryPath
                            } else {
                                selected + candidate.entryPath
                            }
                        },
                    ) {
                        TooltipArea(
                            tooltip = {
                                Box(
                                    Modifier
                                        .widthIn(max = 560.dp)
                                        .background(tc2.p2, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, tc2.br, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    AppText(
                                        candidate.entryPath,
                                        color = tc2.tx,
                                        fontSize = 11.sp,
                                        fontFamily = MONO,
                                        maxLines = 4,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            AppText(displayEntryPath, color = tc2.tx, fontSize = 11.sp, fontFamily = MONO)
                        }
                    }
                }
            }
            if (videoCandidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppText("Attach a video (optional)", color = tc2.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                AppText(
                    "Choose one recording to attach to every selected log. Leave this unset to open logs without a video.",
                    color = tc2.td,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
                Spacer(Modifier.height(5.dp))
                Column(Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                    ArchiveVideoChoiceRow(
                        label = "No video",
                        selected = selectedVideoPath == null,
                        onClick = { selectedVideoPath = null },
                    )
                    videoCandidates.forEach { candidate ->
                        ArchiveVideoChoiceRow(
                            label = zipEntryPathForDisplay(candidate.entryPath),
                            tooltip = candidate.entryPath,
                            selected = candidate.entryPath == selectedVideoPath,
                            onClick = { selectedVideoPath = candidate.entryPath },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogActionButton(
                    "Open selected",
                    active = selected.isNotEmpty(),
                    enabled = selected.isNotEmpty(),
                ) {
                    onConfirm(
                        candidates.filter { it.entryPath in selected },
                        videoCandidates.firstOrNull { it.entryPath == selectedVideoPath },
                    )
                }
                DialogActionButton("Cancel", active = false) { onCancel() }
            }
        }
    }
}

// Moved here verbatim from App.kt (was private there) — the only caller besides EntryPickerDialog
// itself is App.kt's own pendingZipPicker/pendingFolderPicker call sites, which no longer build
// this row inline.
@Composable
internal fun ArchiveVideoChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, tooltip: String? = null) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = tc.ac, unselectedColor = tc.td),
            modifier = Modifier.size(16.dp),
        )
        TooltipArea(
            tooltip = {
                if (tooltip != null) {
                    Box(
                        Modifier.widthIn(max = 560.dp).background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(tooltip, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 4)
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            AppText(label, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
        }
    }
}
