package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.indagium.ui.holiday.HOLIDAY_THEMES
import com.indagium.ui.holiday.PackCard
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState

internal const val WHATS_NEW_TAG = "release-whats-new"
internal const val HOLIDAY_ANNOUNCEMENT_TAG = "release-holiday-themes"

@Composable
internal fun ReleaseAnnouncementDialog(state: AppState) {
    val release = state.releaseAnnouncement ?: return
    val step = state.releaseAnnouncementStep ?: return
    Dialog(
        onDismissRequest = {
            if (step == ReleaseAnnouncementStep.WHATS_NEW) state.finishWhatsNew() else state.finishHolidayAnnouncement()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        when (step) {
            ReleaseAnnouncementStep.WHATS_NEW -> WhatsNewContent(state, release)
            ReleaseAnnouncementStep.HOLIDAY_THEMES -> NewHolidayThemesContent(state, release)
        }
    }
}

@Composable
private fun WhatsNewContent(state: AppState, release: ReleaseAnnouncement) {
    val colors = tc()
    val index = state.releaseAnnouncementPage.coerceIn(0, release.pages.lastIndex)
    val page = release.pages[index]
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(640.dp).height(500.dp).testTag(WHATS_NEW_TAG)
            .background(colors.p, shape).border(1.dp, colors.br, shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppText("What's New in this version", color = colors.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            AppText("${index + 1} / ${release.pages.size}", color = colors.td, fontSize = 11.sp, modifier = Modifier.testTag("release-page-count"))
        }
        AppText(page.title, color = colors.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("release-page-title"))
        key(index) {
            val scroll = rememberScrollState()
            Markdown(
                rememberMarkdownState(content = page.markdown),
                colors = updateMarkdownColors(colors), typography = updateMarkdownTypography(colors),
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).testTag("release-page-body"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            DialogActionButton("Skip", active = false, onClick = state::finishWhatsNew)
            DialogActionButton("Previous", active = false, enabled = index > 0, onClick = state::releaseAnnouncementPrevious)
            DialogActionButton(if (index == release.pages.lastIndex) "Done" else "Next", active = true, onClick = state::releaseAnnouncementNext)
        }
    }
}

@Composable
private fun NewHolidayThemesContent(state: AppState, release: ReleaseAnnouncement) {
    val colors = tc()
    val shape = RoundedCornerShape(8.dp)
    val selectedPack = state.releaseAnnouncementPreviewPack
    val selectedDark = state.releaseAnnouncementPreviewDark
    Column(
        Modifier.width(HOLIDAY_DIALOG_WIDTH).heightIn(max = 600.dp).testTag(HOLIDAY_ANNOUNCEMENT_TAG)
            .background(colors.p, shape).border(1.dp, colors.br, shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("New special themes", color = colors.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        AppText(
            "Choose a new theme, in its light or dark version, to use it now, or skip and keep your current appearance.",
            color = colors.ts,
            fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
            listOf("Light", "Dark").forEach { header ->
                AppText(
                    header, color = colors.td, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).testTag("release-themes-header-${header.lowercase()}"),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            release.themes.mapNotNull { HOLIDAY_THEMES[it] }.forEach { theme ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    listOf(false, true).forEach { dark ->
                        Box(Modifier.weight(1f)) {
                            PackCard(theme, selected = selectedPack == theme.pack && selectedDark == dark, dark = dark) {
                                state.previewAnnouncedTheme(theme.pack, dark)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            DialogActionButton("Skip", active = false) { state.finishHolidayAnnouncement() }
            DialogActionButton("Use theme", active = true, enabled = selectedPack != null) {
                state.finishHolidayAnnouncement(selectedPack, announcedLightness(selectedDark))
            }
        }
    }
}

private val HOLIDAY_DIALOG_WIDTH = 700.dp
private val COLUMN_GAP = 8.dp
