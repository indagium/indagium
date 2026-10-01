package com.indagium.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Shared installed-font chooser for appearance settings and per-rule font overrides. */
@Composable
internal fun SearchableFontDropdown(
    label: String?,
    selectedFamily: String?,
    defaultLabel: String,
    fallbackFamily: FontFamily,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String,
    showPreview: Boolean = true,
) {
    val tc = tc()
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (windowSize.width.toDp() - 20.dp).coerceAtLeast(180.dp) }
    val maxHeight = with(density) { (windowSize.height.toDp() - 24.dp).coerceAtLeast(120.dp) }
    val popupHeight = minOf(360.dp, maxHeight)
    var expanded by remember(testTagPrefix) { mutableStateOf(false) }
    var query by remember(testTagPrefix) { mutableStateOf("") }
    var anchorWidth by remember(testTagPrefix) { mutableIntStateOf(0) }
    val fontListState = rememberLazyListState()
    val matches = remember(query) {
        val needle = query.trim()
        FontCatalog.families.filter { needle.isBlank() || it.contains(needle, ignoreCase = true) }
    }
    val listHeight = (popupHeight - 72.dp).coerceAtLeast(80.dp)
    val shownName = selectedFamily ?: defaultLabel
    val previewFamily = FontCatalog.resolve(selectedFamily, fallbackFamily)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { anchorWidth = it.size.width }) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (expanded) tc.ac else tc.br, RoundedCornerShape(6.dp))
                    .background(tc.p2, RoundedCornerShape(6.dp))
                    .clickable {
                        query = ""
                        expanded = !expanded
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("$testTagPrefix-trigger"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    shownName,
                    color = tc.tx,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                )
                AppText(if (expanded) "▴" else "▾", color = tc.ts, fontSize = 11.sp)
            }
            if (expanded) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, with(density) { 35.dp.roundToPx() }),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier.width(with(density) { anchorWidth.toDp() }.coerceAtMost(maxWidth))
                            .heightIn(max = popupHeight)
                            .background(tc.p, RoundedCornerShape(7.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                            .padding(6.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    expanded = false
                                    true
                                } else {
                                    false
                                }
                            }
                            .testTag("$testTagPrefix-popup"),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        InlineField(
                            query,
                            { query = it },
                            "Search installed fonts",
                            Modifier.fillMaxWidth().testTag("$testTagPrefix-search"),
                            onClear = { query = "" },
                            onCancel = { expanded = false },
                        )
                        Row(Modifier.fillMaxWidth().heightIn(max = listHeight).weight(1f, fill = false)) {
                            LazyColumn(
                                Modifier.weight(1f).heightIn(max = listHeight).padding(end = 6.dp),
                                state = fontListState,
                            ) {
                                item {
                                    FontChoiceRow(
                                        name = defaultLabel,
                                        family = fallbackFamily,
                                        selected = selectedFamily == null,
                                        onClick = { onSelect(null); expanded = false },
                                        testTag = "$testTagPrefix-option-default",
                                    )
                                }
                                itemsIndexed(matches, key = { _, family -> family }) { index, family ->
                                    FontChoiceRow(
                                        name = family,
                                        family = FontCatalog.resolve(family, fallbackFamily),
                                        selected = family.equals(selectedFamily, ignoreCase = true),
                                        onClick = { onSelect(family); expanded = false },
                                        testTag = "$testTagPrefix-option-$index",
                                    )
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(fontListState),
                                modifier = Modifier.fillMaxHeight().width(6.dp).testTag("$testTagPrefix-scrollbar"),
                                style = appScrollbarStyle(tc),
                            )
                        }
                    }
                }
            }
        }
        if (showPreview) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppText(
                    "AaBb 012345",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = previewFamily,
                    modifier = Modifier.testTag("$testTagPrefix-preview"),
                )
                if (selectedFamily != null && FontCatalog.resolveOrNull(selectedFamily) == null) {
                    AppText("Unavailable; using fallback", color = tc.td, fontSize = 9.sp)
                }
            }
        } else if (selectedFamily != null && FontCatalog.resolveOrNull(selectedFamily) == null) {
            AppText("Unavailable; using fallback", color = tc.td, fontSize = 9.sp)
        }
    }
}

@Composable
private fun FontChoiceRow(
    name: String,
    family: FontFamily,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) tc.abg else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(
            name,
            color = tc.ts,
            fontSize = 11.sp,
            fontFamily = family,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) AppText("✓", color = tc.ac, fontSize = 11.sp)
    }
}
