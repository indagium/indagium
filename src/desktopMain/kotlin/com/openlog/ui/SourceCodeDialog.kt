@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openlog.source.LogCallSite
import com.openlog.source.SourceCodeView

// ── Source code popup (Task 4) ─────────────────────────────────────────
// Shows the enclosing method for a log call site resolved via AppState.readMethodSource.
// Mirrors MdPreviewDialog's modal-dialog idioms (AnnotationPanel.kt) — same Dialog/Column
// chrome, header row, divider, scrollable body with matching scrollbar styling.
@Composable
internal fun SourceCodeDialog(state: AppState, view: SourceCodeView, onDismiss: () -> Unit) {
    val tc = tc()
    var selected by remember(view) { mutableStateOf(view.selected.coerceIn(0, view.matches.lastIndex)) }
    val match = view.matches[selected]
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f)
                .background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(tc.p2, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(match.site.methodName, color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    "Lines ${match.site.methodStartLine}–${match.site.methodEndLine}",
                    color = tc.td,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                )
                if (match.stale) {
                    AppText("source changed", color = tc.ac, fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f))
                if (view.matches.size > 1) {
                    AppButton(
                        "‹",
                        onClick = { selected-- },
                        variant = ButtonVariant.Secondary,
                        enabled = selected > 0,
                        modifier = Modifier.height(24.dp),
                    )
                    AppText("${selected + 1} of ${view.matches.size}", color = tc.td, fontSize = 11.sp)
                    AppButton(
                        "›",
                        onClick = { selected++ },
                        variant = ButtonVariant.Secondary,
                        enabled = selected < view.matches.lastIndex,
                        modifier = Modifier.height(24.dp),
                    )
                }
                TooltipArea(
                    tooltip = {
                        Box(
                            Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AppText("Open this file at the log line in your editor", color = tc.tx, fontSize = 11.sp)
                        }
                    },
                ) {
                    AppButton(
                        "Open",
                        onClick = { state.openInEditor(match.site.filePath, match.site.callLine) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                CloseButton(onClick = onDismiss)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TooltipArea(
                    tooltip = {
                        Box(
                            Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AppText(match.site.filePath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    AppText(
                        match.site.filePath,
                        color = tc.td,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CopyPathButton { state.copyToClipboard(match.site.filePath) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            SourceCodeBody(state, match.site, tc)
        }
    }
}

@Composable
private fun CopyPathButton(onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    TooltipArea(
        tooltip = {
            Box(
                Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                AppText("Copy source path", color = tc.tx, fontSize = 11.sp)
            }
        },
    ) {
        Box(
            Modifier.size(24.dp)
                .background(if (hovered) tc.hv else androidx.compose.ui.graphics.Color.Transparent, CORNER_MD)
                .clip(CORNER_MD)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy source path",
                tint = tc.td,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SourceCodeBody(state: AppState, site: LogCallSite, tc: ThemeColors) {
    val code = remember(site) { state.readMethodSource(site) }
    if (code == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText("Source unavailable (file moved or unreadable). Try Reindex.", color = tc.td)
        }
        return
    }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(12.dp)) {
            SelectionContainer {
                Text(
                    code,
                    color = tc.tx,
                    fontSize = 12.sp,
                    fontFamily = MONO,
                    softWrap = false,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(vScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(6.dp),
            style = appScrollbarStyle(tc),
        )
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(hScroll),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 4.dp).height(6.dp),
            style = appScrollbarStyle(tc),
        )
    }
}
