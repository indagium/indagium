package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.CrashKind
import com.openlog.model.CrashSite
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeStackTraceGroups

private fun CrashSite.accentColor(): Color = when (kind) {
    CrashKind.EXCEPTION -> DANGER_RED
    CrashKind.ANR -> LogLevel.W.defaultColor
}

private fun CrashSite.kindLabel(): String = when (kind) {
    CrashKind.EXCEPTION -> "Exception"
    CrashKind.ANR -> "ANR"
}

@Composable
fun CrashPanel(
    tab: LogTab,
    width: Float,
    onNavigate: (CrashSite) -> Unit,
) {
    val tc = tc()
    val mono = monoFont()
    // Detected fresh from the whole (unfiltered) file every time it changes — this panel is meant
    // to be a complete inventory regardless of the active filter, matching AnnotationPanel's
    // "derive, don't cache" pattern already used for computeItems().
    val sites = remember(tab.logData) {
        computeCrashSites(tab.logData, computeStackTraceGroups(tab.logData))
    }

    Column(
        Modifier.width(width.dp).fillMaxHeight().background(tc.p).border(BorderStroke(1.dp, tc.br)),
    ) {
        Box(
            Modifier.fillMaxWidth().height(36.dp).background(tc.p2).border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp),
        ) {
            AppText(
                "Crashes & ANRs (${sites.size})",
                color = tc.ts,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        if (sites.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("◆", color = tc.td.copy(.33f), fontSize = 22.sp)
                AppText("No exceptions or ANRs\nfound in this file", color = tc.td, fontSize = 11.sp, maxLines = 2)
            }
        } else {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    sites.forEach { site ->
                        CrashSiteRow(site, mono, tc, onClick = { onNavigate(site) })
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

@Composable
private fun CrashSiteRow(
    site: CrashSite,
    mono: FontFamily,
    tc: ThemeColors,
    onClick: () -> Unit,
) {
    val accent = site.accentColor()
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            Modifier.fillMaxWidth()
                .border(BorderStroke(1.dp, tc.br.copy(.4f)))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.background(accent.copy(.15f), CORNER_SM).border(1.dp, accent.copy(.4f), CORNER_SM)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { AppText(site.kindLabel(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
                AppText(site.entry.ts, color = tc.td, fontSize = 9.sp, fontFamily = mono)
                AppText(site.entry.tag, color = tc.td, fontSize = 9.sp, fontFamily = mono,
                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
            }
            AppText(
                site.entry.msg, color = tc.tx, fontSize = 11.sp, fontFamily = mono,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
