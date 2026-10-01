@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// UI pieces of Settings search (index: ui/SettingsSearchIndex.kt): the anchor registry that lets a
// clicked result scroll to and flash its row, the nav search field, and the results list.

/** Where each searchable row currently is, so a result click can scroll the section to it. Only
 *  the composed section's rows are registered; coordinates are read live at scroll time (rather
 *  than cached as numbers) so they stay right while the content column scrolls or relayouts. */
internal class SettingsAnchors {
    private val coords = HashMap<String, LayoutCoordinates>()

    /** The scrolled content column of the dialog (measured after verticalScroll, so its origin is
     *  the top of the whole content, not of the viewport). */
    var content: LayoutCoordinates? = null

    /** Key of the row being flashed, and how strong the flash still is (1 -> 0). */
    var flashKey by mutableStateOf<String?>(null)
    val flash = Animatable(0f)

    // Bumped only when a key first appears, never on the per-scroll position updates.
    var registrations by mutableIntStateOf(0)
        private set

    fun register(key: String, c: LayoutCoordinates) {
        if (coords.put(key, c) == null) registrations++
    }

    fun unregister(key: String) {
        coords.remove(key)
    }

    /** Y of [key]'s row within the scrolled content, or null while it isn't laid out. */
    fun yInContent(key: String): Float? {
        val row = coords[key]?.takeIf { it.isAttached } ?: return null
        val root = content?.takeIf { it.isAttached } ?: return null
        return root.localPositionOf(row, Offset.Zero).y
    }
}

internal val LocalSettingsAnchors = staticCompositionLocalOf<SettingsAnchors?> { null }

/** Marks a row as the target for the search result with the matching anchor key (see
 *  SettingsSearchEntry.anchor). A no-op outside the Settings dialog, so shared controls (the
 *  capture options also shown on the New tab) can carry it freely. */
@Composable
internal fun Modifier.settingsAnchor(key: String): Modifier {
    val anchors = LocalSettingsAnchors.current
    if (anchors == null) return this
    val accent = tc().ac
    DisposableEffect(anchors, key) { onDispose { anchors.unregister(key) } }
    return this
        .onGloballyPositioned { anchors.register(key, it) }
        .drawBehind {
            if (anchors.flashKey != key) return@drawBehind
            val strength = anchors.flash.value
            if (strength <= 0f) return@drawBehind
            val padX = 6.dp.toPx()
            val padY = 4.dp.toPx()
            drawRoundRect(
                color = accent.copy(alpha = FLASH_MAX_ALPHA * strength),
                topLeft = Offset(-padX, -padY),
                size = Size(size.width + 2 * padX, size.height + 2 * padY),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
}

private const val FLASH_MAX_ALPHA = 0.28f

/** Search field at the top of the Settings nav: search icon, placeholder, a ⌘F hint while empty
 *  and a clear button once there is text. [focusRequester] lets the dialog's ⌘F focus it. */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().height(30.dp)
            .background(tc.bg, shape).border(1.dp, tc.br, shape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp), tint = tc.td)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = LocalUiFontFamily.current),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.weight(1f)
                .testTag("settings-search-field")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) AppText("Search settings", color = tc.td, fontSize = 12.sp)
                    inner()
                }
            },
        )
        if (query.isEmpty()) {
            AppText(if (isMacOs) "⌘F" else "Ctrl F", color = tc.td, fontSize = 10.sp, fontFamily = UI)
        } else {
            SquareIconButton(
                "×", fontSize = 12.sp,
                onClick = {
                    onQueryChange("")
                    // The clear button is clickable, so it took keyboard focus with the click.
                    runCatching { focusRequester.requestFocus() }
                },
                size = 16.dp,
            )
        }
    }
}

/** Results of a settings search, grouped by section. */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<SettingsSearchEntry>,
    onOpen: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val scroll = rememberScrollState()
    val tokens = searchTokens(query)
    val shown = query.trim()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (results.isEmpty()) {
                AppText(
                    "No settings match “$shown”", color = tc.tx, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                )
                AppText(
                    "Try fewer or shorter words, or a related word such as “folder”, “theme” or “font”.",
                    color = tc.td, fontSize = 11.sp, maxLines = 2,
                )
            } else {
                AppText(
                    "${results.size} ${if (results.size == 1) "setting matches" else "settings match"} “$shown”",
                    color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                )
                SettingsSection.entries.forEach { section ->
                    val inSection = results.filter { it.section == section }
                    if (inSection.isEmpty()) return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppText(
                            section.title.uppercase(), color = tc.td, fontSize = 10.sp,
                            fontFamily = UI, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        inSection.forEach { entry -> SettingsSearchResultRow(entry, tokens, onOpen) }
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
private fun SettingsSearchResultRow(entry: SettingsSearchEntry, tokens: List<String>, onOpen: (SettingsSearchEntry) -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val label = entry.label.replace('\n', ' ')
    val highlighted = remember(label, tokens, tc.searchMatchBg) {
        buildAnnotatedString {
            var pos = 0
            for (range in matchRanges(label, tokens)) {
                append(label.substring(pos, range.first))
                withStyle(SpanStyle(background = tc.searchMatchBg)) { append(label.substring(range.first, range.last + 1)) }
                pos = range.last + 1
            }
            append(label.substring(pos))
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered) tc.hv else tc.p2.copy(alpha = 0f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(entry) }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            androidx.compose.material3.Text(
                highlighted, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AppText(entry.hint, color = tc.ts, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AppText("${entry.section.title} ›", color = tc.td, fontSize = 10.sp, fontFamily = UI, maxLines = 1)
    }
}
