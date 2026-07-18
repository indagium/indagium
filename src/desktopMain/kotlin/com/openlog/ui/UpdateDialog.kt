package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.openlog.generated.BuildInfo
import com.openlog.update.assetForCurrentOs
import java.awt.Desktop
import java.net.URI

/** Modeled on LicenseAgreementDialog.kt — a show-once-per-release popup driven by AppState.updateDialogVisible. */
@Composable
internal fun UpdateDialog(state: AppState) {
    val release = state.availableUpdate ?: return
    val colors = tc()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(8.dp)
    val download = state.updateDownload

    Dialog(
        onDismissRequest = { state.dismissUpdateForNow() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.width(600.dp).height(540.dp)
                .background(colors.p, shape)
                .border(1.dp, colors.br, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText("Update available", color = colors.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "openLog ${BuildInfo.APP_VERSION} → ${release.version}",
                color = colors.td,
                fontSize = 11.sp,
                fontFamily = MONO,
            )
            if (release.body.isNotBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, colors.br, shape)) {
                    val markdownState = rememberMarkdownState(content = release.body)
                    Markdown(
                        markdownState,
                        colors = updateMarkdownColors(colors),
                        typography = updateMarkdownTypography(colors),
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).padding(end = 8.dp),
                    )
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                        style = appScrollbarStyle(colors),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            when (download) {
                is UpdateDownloadState.InProgress -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("Downloading… ${(download.fraction * 100).toInt()}%", color = colors.td, fontSize = 11.sp)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(colors.br, RoundedCornerShape(2.dp))) {
                        Box(
                            Modifier.fillMaxWidth(download.fraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(colors.ac, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                is UpdateDownloadState.Done ->
                    AppText("Downloaded to ${download.file.absolutePath}", color = colors.td, fontSize = 11.sp)
                is UpdateDownloadState.Failed -> AppText(download.reason, color = DANGER_RED, fontSize = 11.sp)
                UpdateDownloadState.Idle -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Skip this version", onClick = { state.skipUpdate() }, variant = ButtonVariant.Secondary)
                AppButton("Later", onClick = { state.dismissUpdateForNow() }, variant = ButtonVariant.Secondary)
                when {
                    download is UpdateDownloadState.Done ->
                        AppButton("Close", onClick = { state.dismissUpdateForNow() }, variant = ButtonVariant.Primary)
                    assetForCurrentOs(release.assets) != null -> AppButton(
                        "Download",
                        onClick = { state.downloadUpdate() },
                        variant = ButtonVariant.Primary,
                        enabled = download !is UpdateDownloadState.InProgress,
                    )
                    else -> AppButton("View on GitHub", onClick = { openUpdateUrl(release.htmlUrl) }, variant = ButtonVariant.Primary)
                }
            }
        }
    }
}

private fun openUpdateUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

@Composable
private fun updateMarkdownColors(colors: ThemeColors) = markdownColor(
    text = colors.tx,
    codeBackground = colors.p2,
    inlineCodeBackground = colors.p2,
    dividerColor = colors.br,
    tableBackground = colors.p2,
)

@Composable
private fun updateMarkdownTypography(colors: ThemeColors): MarkdownTypography {
    val body = TextStyle(color = colors.tx, fontSize = 12.sp, fontFamily = UI)
    val code = TextStyle(color = colors.tx, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val heading = body.copy(fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = heading.copy(fontSize = 15.sp),
        h2 = heading.copy(fontSize = 14.sp),
        h3 = heading.copy(fontSize = 13.sp),
        h4 = heading.copy(fontSize = 12.sp),
        h5 = heading.copy(fontSize = 12.sp),
        h6 = heading.copy(fontSize = 12.sp),
        text = body,
        code = code,
        inlineCode = code,
        quote = body.copy(fontStyle = FontStyle.Italic),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
    )
}
