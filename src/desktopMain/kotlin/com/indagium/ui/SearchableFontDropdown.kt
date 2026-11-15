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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

/**
 * A default that sits above the installed families in [SearchableFontDropdown]'s list, such as "Monospace · default": [key] names it in
 * the `-option-<tagSuffix>` test tag and in `selectedPinnedKey`, [family] draws its row.
 */
internal data class PinnedFontDefault(val key: String, val label: String, val family: FontFamily)

/**
 * Shared installed-font chooser for appearance settings and per-rule font overrides.
 *
 * With no [pinnedDefaults] the list starts with one "default" row ([defaultLabel], picking it calls `onSelect(null)`). With
 * [pinnedDefaults] those rows replace it, followed by a divider: [selectedPinnedKey] says which one is current while
 * [selectedFamily] is null, picking one calls [onSelectPinned], and a named family still calls [onSelect]. [triggerInFont] draws the
 * closed field's text in the selected font. [onInteraction] runs after every open, close and pick so a host with its own key handling
 * can reclaim the keyboard focus that the popup took.
 */
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
    pinnedDefaults: List<PinnedFontDefault> = emptyList(),
    selectedPinnedKey: String? = null,
    onSelectPinned: (PinnedFontDefault) -> Unit = {},
    triggerInFont: Boolean = false,
    onInteraction: () -> Unit = {},
) {
    val tc = tc()
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (windowSize.width.toDp() - 20.dp).coerceAtLeast(180.dp) }
    val maxHeight = with(density) { (windowSize.height.toDp() - 24.dp).coerceAtLeast(120.dp) }
    val popupHeight = minOf(360.dp, maxHeight)
    var expanded by remember(testTagPrefix) { mutableStateOf(false) }
    var query by remember(testTagPrefix) { mutableStateOf("") }
    val fontListState = rememberLazyListState()
    val matches = remember(query) {
        val needle = query.trim()
        // Hidden system families (".AppleSystemUIFont" and the like) are not for picking.
        FontCatalog.families.filter { !it.startsWith(".") && (needle.isBlank() || it.contains(needle, ignoreCase = true)) }
    }
    val listHeight = (popupHeight - 72.dp).coerceAtLeast(80.dp)
    val pinnedSelected = if (selectedFamily == null) pinnedDefaults.firstOrNull { it.key == selectedPinnedKey } else null
    val shownName = selectedFamily ?: pinnedSelected?.label ?: defaultLabel
    val previewFamily = FontCatalog.resolve(selectedFamily, pinnedSelected?.family ?: fallbackFamily)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        SettingsDropdownField(
            text = shownName,
            open = expanded,
            onClick = {
                query = ""
                expanded = !expanded
                onInteraction()
            },
            modifier = Modifier.fillMaxWidth(),
            minWidth = 0.dp,
            testTag = "$testTagPrefix-trigger",
            textFontFamily = if (triggerInFont) previewFamily else null,
        ) { fieldWidth, popupOffset ->
            Popup(
                alignment = Alignment.TopStart,
                offset = popupOffset,
                onDismissRequest = {
                    expanded = false
                    onInteraction()
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(fieldWidth.coerceAtMost(maxWidth))
                        .heightIn(max = popupHeight)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(6.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                expanded = false
                                onInteraction()
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
                        onCancel = {
                            expanded = false
                            onInteraction()
                        },
                    )
                    Row(Modifier.fillMaxWidth().heightIn(max = listHeight).weight(1f, fill = false)) {
                        LazyColumn(
                            Modifier.weight(1f).heightIn(max = listHeight).padding(end = 6.dp),
                            state = fontListState,
                        ) {
                            if (pinnedDefaults.isEmpty()) {
                                item {
                                    FontChoiceRow(
                                        name = defaultLabel,
                                        family = fallbackFamily,
                                        selected = selectedFamily == null,
                                        onClick = {
                                            onSelect(null)
                                            expanded = false
                                            onInteraction()
                                        },
                                        testTag = "$testTagPrefix-option-default",
                                    )
                                }
                            } else {
                                items(pinnedDefaults, key = { "pinned-${it.key}" }) { pinned ->
                                    FontChoiceRow(
                                        name = pinned.label,
                                        family = pinned.family,
                                        selected = selectedFamily == null && pinned.key == selectedPinnedKey,
                                        onClick = {
                                            onSelectPinned(pinned)
                                            expanded = false
                                            onInteraction()
                                        },
                                        testTag = "$testTagPrefix-option-default-${pinned.key}",
                                    )
                                }
                                item(key = "pinned-divider") {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(tc.br))
                                }
                            }
                            itemsIndexed(matches, key = { _, family -> family }) { index, family ->
                                FontChoiceRow(
                                    name = family,
                                    family = FontCatalog.resolve(family, fallbackFamily),
                                    selected = family.equals(selectedFamily, ignoreCase = true),
                                    onClick = {
                                        onSelect(family)
                                        expanded = false
                                        onInteraction()
                                    },
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
                    AppText("Unavailable; using fallback", color = tc.ts, fontSize = 10.sp)
                }
            }
        } else if (selectedFamily != null && FontCatalog.resolveOrNull(selectedFamily) == null) {
            AppText("Unavailable; using fallback", color = tc.ts, fontSize = 10.sp)
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
        // The same option style as SettingsDropdown: the chosen one in accent semibold on the accent wash, the rest in the text colour.
        AppText(
            name,
            color = if (selected) tc.ac else tc.tx,
            fontSize = 11.sp,
            fontFamily = family,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
        )
    }
}
