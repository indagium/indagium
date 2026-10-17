package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.CtrlFTarget
import com.indagium.model.SearchScopeBehavior

/**
 * Search scope choice shared by Settings and the setup assistant. [onInteraction] lets a host
 * restore its keyboard focus after the clickable selector or its popup receives a pointer event.
 */
@Composable
internal fun SearchScopeBehaviorSelector(
    selected: SearchScopeBehavior,
    onSelected: (SearchScopeBehavior) -> Unit,
    onInteraction: () -> Unit = {},
) {
    val tc = tc()
    val density = LocalDensity.current
    val behaviors = listOf(
        SearchScopeBehavior.FILTERED,
        SearchScopeBehavior.UNFILTERED,
        SearchScopeBehavior.TWO_SEARCHES,
        SearchScopeBehavior.LAST_USED,
        SearchScopeBehavior.FOLLOW_VIEW,
    )
    val labels = listOf("Filtered", "Unfiltered", "Two searches", "Last search mode", "Auto-switch by open view")
    val selectedIndex = behaviors.indexOf(selected).coerceAtLeast(0)
    var open by remember { mutableStateOf(false) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    Box(Modifier.fillMaxWidth()) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(26.dp).testTag("search-scope-behavior-selector")
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                    open = !open
                    onInteraction()
                }
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    labels[selectedIndex], color = tc.tx, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                    onInteraction()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.widthIn(min = 176.dp, max = 220.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    behaviors.forEachIndexed { index, behavior ->
                        val active = behavior == selected
                        HoverBox(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("search-scope-behavior-option-${behavior.name.lowercase()}")
                                .clip(RoundedCornerShape(5.dp)),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = {
                                open = false
                                onSelected(behavior)
                                onInteraction()
                            },
                        ) {
                            AppText(
                                labels[index],
                                color = if (active) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ctrl+F destination shared by Settings and setup; legacy MESSAGE_RULE remains routable but hidden. */
@Composable
internal fun CtrlFTargetSelector(
    selected: CtrlFTarget,
    onSelected: (CtrlFTarget) -> Unit,
    onInteraction: () -> Unit = {},
) {
    val tc = tc()
    val density = LocalDensity.current
    val targets = listOf(CtrlFTarget.FIND_BAR, CtrlFTarget.TAGS, CtrlFTarget.KEYWORD_REGEX)
    val labels = listOf("Find bar", "Tags", "Regex")
    var open by remember { mutableStateOf(false) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    val selectedIndex = targets.indexOf(selected)
    Box(Modifier.fillMaxWidth()) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(26.dp).testTag("ctrl-f-target-selector")
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                    open = !open
                    onInteraction()
                }
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    labels.getOrElse(selectedIndex) { labels.first() },
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                    onInteraction()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(104.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    targets.forEachIndexed { index, target ->
                        val active = index == selectedIndex
                        HoverBox(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("ctrl-f-target-option-${target.name.lowercase()}")
                                .clip(RoundedCornerShape(5.dp)),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = {
                                open = false
                                onSelected(target)
                                onInteraction()
                            },
                        ) {
                            AppText(
                                labels[index],
                                color = if (active) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
