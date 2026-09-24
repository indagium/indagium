@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.indagium.update.PROJECT_REPO_URL
import java.awt.Desktop
import java.net.URI

/**
 * "Do you like Indagium?" support popup: shown at most once every ten days (see
 * AppState.maybeShowSupportPromptOnStartup/supportPromptDue) and also reachable on demand from the
 * Settings footer's "Support project" link (SettingsDialog.kt). Every action closes the dialog
 * through [AppState.dismissSupportDialog] — Star/Sponsor open a browser tab first. Not open source,
 * so the copy below is careful to talk about GitHub and being free to use, never "open source".
 */
@Composable
internal fun SupportDialog(state: AppState) {
    val tc = tc()
    val shape = RoundedCornerShape(12.dp)

    Dialog(
        onDismissRequest = state::dismissSupportDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(460.dp)
                .shadow(8.dp, shape)
                .clip(shape)
                .background(tc.p)
                .border(1.dp, tc.br, shape),
        ) {
            SupportDialogHeader(tc)
            SupportDialogBody(state, tc)
            SupportDialogFooter(tc)
        }
    }
}

@Composable
private fun SupportDialogHeader(tc: ThemeColors) {
    Row(
        Modifier.fillMaxWidth().background(tc.p2).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).background(tc.ac, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppText("I", color = Color.White, fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        AppText("Indagium", color = tc.tx, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        AppText("every 10 days", color = tc.td, fontSize = 10.5.sp, fontFamily = MONO)
    }
    Divider()
}

@Composable
private fun SupportDialogBody(state: AppState, tc: ThemeColors) {
    Column(Modifier.padding(top = 26.dp, start = 32.dp, end = 32.dp, bottom = 24.dp)) {
        AppText("Do you like Indagium?", color = tc.tx, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "You've been reading logs with Indagium for a while now. It's free to use and built " +
                    "by an independent developer in spare evenings.",
                color = tc.ts,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
            val sponsorSuffix = if (state.sponsorUrl != null) {
                " Or, if it saves you time, consider sponsoring its development."
            } else {
                ""
            }
            Text(
                "A star on GitHub takes two seconds and helps other engineers find the tool.$sponsorSuffix",
                color = tc.ts,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
        }
        Row(
            Modifier.padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                "★ Star on GitHub",
                onClick = { openExternalUrl(PROJECT_REPO_URL); state.dismissSupportDialog() },
                variant = ButtonVariant.Primary,
                horizontalPadding = 14.dp,
            )
            state.sponsorUrl?.let { url ->
                AppButton(
                    "♥ Sponsor",
                    onClick = { openExternalUrl(url); state.dismissSupportDialog() },
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 14.dp,
                )
            }
            AppButton(
                "Already starred",
                onClick = { state.dismissSupportDialog() },
                variant = ButtonVariant.Secondary,
                horizontalPadding = 14.dp,
            )
            Spacer(Modifier.weight(1f))
            AppButton(
                "Not now",
                onClick = { state.dismissSupportDialog() },
                variant = ButtonVariant.Ghost,
                horizontalPadding = 14.dp,
            )
        }
    }
}

@Composable
private fun SupportDialogFooter(tc: ThemeColors) {
    Divider()
    // Not clickable by design: this preference isn't available on the free version, so the whole
    // row exists only to show the disabled checkbox + lock + a hover tooltip explaining why.
    TooltipArea(tooltip = { ToolbarTooltip("Not available on the free version") }) {
        Row(
            Modifier.fillMaxWidth().background(tc.p2).padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(13.dp).border(1.dp, tc.td.copy(alpha = 0.5f), RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = tc.td.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(6.dp))
            AppText("Don't remind me again", color = tc.td.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = MONO)
        }
    }
}

/** Shared browser-open helper — same Desktop.browse + runCatching shape as
 *  SettingsDialog.kt's openProjectRepository and UpdateDialog.kt's openUpdateUrl. */
internal fun openExternalUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
