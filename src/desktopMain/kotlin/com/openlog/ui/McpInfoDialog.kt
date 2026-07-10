package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.debug.ConnectedClientInfo
import com.openlog.debug.McpSessionInfo
import kotlinx.coroutines.delay

internal fun mcpCurlCommand(port: Int, token: String): String =
    "curl -H \"Authorization: Bearer $token\" http://127.0.0.1:$port/tabs"

internal const val COPIED_FEEDBACK_MS = 1500L
internal const val CLIENT_POLL_INTERVAL_MS = 1500L
internal const val AGO_JUST_NOW_MS = 2_000L
internal const val MS_PER_SECOND = 1000L
internal const val MS_PER_MINUTE = 60_000L
internal const val BYTES_PER_SIZE_UNIT = 1024.0

internal fun agoLabel(lastSeenMs: Long): String {
    val delta = System.currentTimeMillis() - lastSeenMs
    return when {
        delta < AGO_JUST_NOW_MS -> "just now"
        delta < MS_PER_MINUTE -> "${delta / MS_PER_SECOND}s ago"
        else -> "${delta / MS_PER_MINUTE}m ago"
    }
}

private enum class McpCopiedField {
    Url,
    Config,
    CurlCommand,
}

@Composable
internal fun McpInfoDialog(state: AppState, port: Int, token: String, onDismiss: () -> Unit) {
    val tc = tc()
    var copiedField by remember { mutableStateOf<McpCopiedField?>(null) }
    LaunchedEffect(copiedField) {
        if (copiedField != null) {
            kotlinx.coroutines.delay(COPIED_FEEDBACK_MS)
            copiedField = null
        }
    }
    var clients by remember { mutableStateOf<List<ConnectedClientInfo>>(state.connectedMcpClients()) }
    var mcpSessions by remember { mutableStateOf<List<McpSessionInfo>>(state.mcpSessions()) }
    // controlServer is a plain (non-Compose-observed) field, so a rotateControlToken() call
    // wouldn't otherwise be reflected here until some unrelated recomposition happened to re-read
    // the `token` param — piggyback the same poll loop that already refreshes clients/sessions.
    var liveToken by remember { mutableStateOf(token) }
    LaunchedEffect(Unit) {
        while (true) {
            clients = state.connectedMcpClients()
            mcpSessions = state.mcpSessions()
            state.controlServerToken()?.let { liveToken = it }
            kotlinx.coroutines.delay(CLIENT_POLL_INTERVAL_MS)
        }
    }
    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
    ) {
        AppText("MCP Connection Info", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        AppText(
            "openLog speaks MCP directly. Point any MCP client (LM Studio, Claude Code, Codex) at " +
                "this URL — nothing to install:",
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        CopyableCodeField(
            text = mcpUrl(port),
            copied = copiedField == McpCopiedField.Url,
            onCopy = {
                state.copyToClipboard(mcpUrl(port))
                copiedField = McpCopiedField.Url
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Copy config for your MCP client",
                onClick = {
                    state.copyToClipboard(mcpConfigSnippet(port, liveToken))
                    copiedField = McpCopiedField.Config
                },
            )
            if (copiedField == McpCopiedField.Config) {
                Spacer(Modifier.width(8.dp))
                AppText("Copied", color = tc.ac, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(10.dp))
        AppText(
            "Paste the copied JSON into your client's MCP config (e.g. ~/.lmstudio/mcp.json), or " +
                "add the URL directly (Claude Code: claude mcp add --transport http openlog " +
                "${mcpUrl(port)}).",
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 4,
        )
        Spacer(Modifier.height(14.dp))
        AppText(
            "Or skip MCP entirely and hit the JSON/HTTP endpoints directly:",
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        CopyableCodeField(
            text = mcpCurlCommand(port, liveToken),
            copied = copiedField == McpCopiedField.CurlCommand,
            onCopy = {
                state.copyToClipboard(mcpCurlCommand(port, liveToken))
                copiedField = McpCopiedField.CurlCommand
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Regenerate token",
                isDanger = true,
                onClick = { state.rotateControlToken() },
            )
            Spacer(Modifier.width(8.dp))
            AppText(
                "Invalidates every client config copied so far — you'll need to re-copy it after this.",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }
        Spacer(Modifier.height(18.dp))
        AppText("Connected clients", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (clients.isEmpty() && mcpSessions.isEmpty()) {
            AppText(
                "None right now.",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 1,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                mcpSessions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            AppText(s.name, color = tc.tx, fontSize = 11.sp)
                            AppText(s.version?.let { "MCP session · v$it" } ?: "MCP session", color = tc.td, fontSize = 10.sp)
                        }
                        AppButton(
                            "Disconnect",
                            isDanger = true,
                            onClick = {
                                state.disconnectMcpSession(s.id)
                                mcpSessions = state.mcpSessions()
                            },
                        )
                    }
                }
                clients.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            AppText(c.name, color = tc.tx, fontSize = 11.sp)
                            AppText(agoLabel(c.lastSeenMs), color = tc.td, fontSize = 10.sp)
                        }
                        AppButton(
                            if (c.blocked) "Unblock" else "Block",
                            isDanger = !c.blocked,
                            onClick = {
                                if (c.blocked) state.unblockMcpClient(c.id) else state.blockMcpClient(c.id)
                                clients = state.connectedMcpClients()
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Close", active = true, onClick = onDismiss)
        }
    }
}

@Composable
internal fun CopyableCodeField(text: String, copied: Boolean, onCopy: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth()
            .background(tc.bg, CORNER_SM)
            .border(1.dp, tc.br, CORNER_SM)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text,
            color = tc.ts,
            fontSize = 11.sp,
            fontFamily = MONO,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.height(30.dp)
                .widthIn(min = 34.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (copied) {
                AppText("Copied", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = tc.ts,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// Keeps the most meaningful (rightmost) path segments and collapses the rest into a leading
// "…/" so long save-folder paths don't overflow or wrap — full path is still shown on hover.
