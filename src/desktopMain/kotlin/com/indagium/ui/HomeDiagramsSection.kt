package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.HomeDiagramSegment
import com.indagium.model.HomeDiagramsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// The New tab's Diagrams section: the diagram library as a list or, with Grid | List, as cards with a preview image (the ones without a
// source log, the ones with one, or all), a search box, "+ New diagram" and a row / card menu (Rename, Delete). Rows, search, empty states and
// how a ticket row opens are the pure HomeDiagrams.kt; the library itself is AppState.seq3Sessions. Every click gives root keyboard focus back through [onReclaimFocus]
// (CLAUDE.md's clickable-steals-focus gotcha), around the search field excepted, which is meant to hold it. Previews are drawn only in
// Grid view, on a background thread, by HomeDiagramThumbnails.kt.

private const val HOME_DIAGRAMS_MENU_WIDTH_DP = 170
private const val HOME_DIAGRAMS_MENU_OFFSET_DP = 26
private const val HOME_DIAGRAMS_SCROLLBAR_GUTTER_DP = 12
private const val HOME_DIAGRAMS_SEGMENT_HEIGHT_DP = 24
private const val HOME_DIAGRAMS_RENAME_FAILED = "The diagram library could not save the new name."

/**
 * The body of the New tab's Diagrams card. [launcherTabId] is the New tab it sits on: whatever opens from here closes it, like
 * opening a log does ([AppState.closeLauncherAfterOpen]).
 */
@Composable
internal fun HomeDiagramsSection(
    state: AppState,
    launcherTabId: String?,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segment = state.settings.homeDiagramsSegment
    val view = state.settings.homeDiagramsView
    val query = state.homeDiagramsQuery
    val library = state.seq3Sessions.listLibrary(DiagramTicketFilter.ALL)
    val now = remember(library) { System.currentTimeMillis() }
    val rows = remember(library, segment, query, now) { homeDiagramRows(library, segment, query, now) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().padding(HOME_SECTION_BODY_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedControl(
            options = HomeDiagramSegment.entries.map(::homeDiagramSegmentLabel),
            selectedIndices = setOf(segment.ordinal),
            onToggle = { index ->
                state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.entries[index]) }
                onReclaimFocus()
            },
            modifier = Modifier.fillMaxWidth().testTag("home-diagrams-segments"),
            fillWidth = true,
            segmentHeight = HOME_DIAGRAMS_SEGMENT_HEIGHT_DP.dp,
            segmentFontSize = 11.sp,
            segmentHorizontalPadding = 8.dp,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineField(
                value = query,
                onValue = { state.homeDiagramsQuery = it },
                placeholder = "Search diagrams",
                modifier = Modifier.weight(1f).testTag("home-diagrams-search"),
                onClear = { state.homeDiagramsQuery = "" },
            )
            SegmentedControl(
                options = homeDiagramsViewLabels(),
                selectedIndices = setOf(homeDiagramsViewIndex(view)),
                onToggle = { index ->
                    state.updateSettings { it.copy(homeDiagramsView = homeDiagramsViewAt(index)) }
                    onReclaimFocus()
                },
                modifier = Modifier.testTag("home-diagrams-view"),
            )
            AppButton(
                "+ New diagram",
                onClick = { newDiagram(state, launcherTabId, onReclaimFocus) },
                modifier = Modifier.testTag("home-diagrams-new"),
            )
        }
        if (rows.isEmpty()) {
            val empty = homeDiagramsEmpty(segment, query)
            HomeDiagramsEmptyState(empty, onNew = { newDiagram(state, launcherTabId, onReclaimFocus) }, Modifier.weight(1f))
        } else {
            val onOpen: (String) -> Unit = { id ->
                library.firstOrNull { it.id == id }?.let { state.homeDiagramOpener.open(it, launcherTabId) }
                onReclaimFocus()
            }
            if (view == HomeDiagramsView.GRID) {
                HomeDiagramsGrid(
                    state = state,
                    rows = rows,
                    segment = segment,
                    onOpen = onOpen,
                    onRename = { renameId = it },
                    onDelete = { deleteId = it },
                    onReclaimFocus = onReclaimFocus,
                    modifier = Modifier.weight(1f),
                )
            } else {
                HomeDiagramsList(
                    rows = rows,
                    segment = segment,
                    onOpen = onOpen,
                    onRename = { renameId = it },
                    onDelete = { deleteId = it },
                    onReclaimFocus = onReclaimFocus,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    renameId?.let { id ->
        val item = library.firstOrNull { it.id == id }
        if (item == null) {
            renameId = null
        } else {
            WorkspaceProfileNameDialog(
                title = "Rename diagram",
                confirmLabel = "Rename",
                initialName = item.title,
                takenNames = library.filter { it.id != id && it.source == item.source }.map { it.title },
                noun = "diagram",
                onConfirm = { name ->
                    renameId = null
                    renameDiagram(state, id, name)
                    onReclaimFocus()
                },
                onDismiss = {
                    renameId = null
                    onReclaimFocus()
                },
            )
        }
    }
    deleteId?.let { id ->
        val item = library.firstOrNull { it.id == id }
        if (item == null) {
            deleteId = null
        } else {
            DiagramLibraryDeleteDialog(
                title = item.title,
                openTabNote = state.seq3Sessions.sessions.any { it.libraryItemId == id },
                onConfirm = {
                    // Deleting is permanent, so only this confirmation does it; the row menu only opens the dialog.
                    state.seq3Sessions.deleteLibraryItem(id)
                    HomeDiagramThumbnails.cache.invalidate(id)
                    deleteId = null
                    onReclaimFocus()
                },
                onDismiss = {
                    deleteId = null
                    onReclaimFocus()
                },
            )
        }
    }
}

/** Creates a diagram without a log and switches to its Diagram tab; the New tab it was started from closes. */
private fun newDiagram(state: AppState, launcherTabId: String?, onReclaimFocus: () -> Unit) {
    val origin = launcherTabId?.takeIf { state.tab(it) != null }?.let { TabRef.Log(it) }
    if (state.seq3Sessions.beginStandalone(origin) != null) state.closeLauncherAfterOpen(launcherTabId)
    onReclaimFocus()
}

private fun renameDiagram(state: AppState, id: String, name: String) {
    if (state.seq3Sessions.renameLibraryItem(id, name)) return
    val tooLong = name.trim().length > MAX_DIAGRAM_LIBRARY_TITLE_CHARS
    state.pendingDiagramNotice = DiagramNotice(
        "Couldn't rename diagram",
        if (tooLong) "A diagram name can have at most $MAX_DIAGRAM_LIBRARY_TITLE_CHARS characters." else HOME_DIAGRAMS_RENAME_FAILED,
    )
}

@Composable
private fun HomeDiagramsEmptyState(empty: HomeDiagramsEmpty, onNew: () -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        AppText(empty.message, color = tc.td, fontSize = 12.sp, maxLines = 3, modifier = Modifier.testTag("home-diagrams-empty"))
        if (empty.offersNew) {
            AppButton("New diagram", onClick = onNew, modifier = Modifier.testTag("home-diagrams-empty-new"))
        }
    }
}

@Composable
private fun HomeDiagramsList(
    rows: List<HomeDiagramRow>,
    segment: HomeDiagramSegment,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().testTag("home-diagrams-list")) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = HOME_DIAGRAMS_SCROLLBAR_GUTTER_DP.dp)) {
            rows.forEach { row ->
                key(segment, row.id) {
                    HomeDiagramRowItem(
                        row = row,
                        onOpen = { onOpen(row.id) },
                        onRename = { onRename(row.id) },
                        onDelete = { onDelete(row.id) },
                        onReclaimFocus = onReclaimFocus,
                    )
                }
            }
        }
        ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun HomeDiagramRowItem(
    row: HomeDiagramRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    Row(Modifier.fillMaxWidth().testTag("home-diagrams-row-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        HoverBox(Modifier.weight(1f).testTag("home-diagrams-open-${row.id}"), onClick = onOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(
                        row.title,
                        color = tc.tx,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.noTicketBadge) NoTicketBadge()
                }
                AppText("${row.participants} · ${row.updated}", color = tc.td, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                if (row.ticketLabel.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val kind = if ('›' in row.ticketLabel) RecentKind.ARCHIVE else RecentKind.LOG
                        Icon(recentKindIcon(kind), contentDescription = null, tint = tc.td, modifier = Modifier.size(12.dp))
                        AppText(
                            row.ticketLabel,
                            color = tc.ts,
                            fontSize = 11.sp,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("home-diagrams-ticket-${row.id}"),
                        )
                    }
                }
            }
        }
        HomeDiagramRowMenu(row.id, onRename, onDelete, onReclaimFocus)
    }
}

private const val HOME_DIAGRAMS_GRID_GAP_DP = 8
private const val HOME_DIAGRAMS_PREVIEW_ASPECT = 1.6f
private const val HOME_DIAGRAMS_PREVIEW_ALPHA = 0.5f
private const val HOME_DIAGRAMS_MENU_BACKDROP_ALPHA = 0.85f

/** Cards in chunked rows inside one scroll column, like the recents grid (no lazy grid inside a scrolling tile). */
@Composable
private fun HomeDiagramsGrid(
    state: AppState,
    rows: List<HomeDiagramRow>,
    segment: HomeDiagramSegment,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag("home-diagrams-grid")) {
        val columns = homeDiagramGridColumns(maxWidth.value - HOME_DIAGRAMS_SCROLLBAR_GUTTER_DP, HOME_DIAGRAMS_GRID_GAP_DP.toFloat())
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(end = HOME_DIAGRAMS_SCROLLBAR_GUTTER_DP.dp),
                verticalArrangement = Arrangement.spacedBy(HOME_DIAGRAMS_GRID_GAP_DP.dp),
            ) {
                rows.chunked(columns).forEach { rowCards ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HOME_DIAGRAMS_GRID_GAP_DP.dp)) {
                        rowCards.forEach { row ->
                            key(segment, row.id) {
                                HomeDiagramCard(
                                    state = state,
                                    row = row,
                                    onOpen = { onOpen(row.id) },
                                    onRename = { onRename(row.id) },
                                    onDelete = { onDelete(row.id) },
                                    onReclaimFocus = onReclaimFocus,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // Pad a short final row so its cards match the width of every full row above.
                        repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * One card: the preview, the title (with the "No ticket" badge in All), the participants, the update time and the ticket; the "⋯" menu sits
 * over the preview's corner.
 */
@Composable
private fun HomeDiagramCard(
    state: AppState,
    row: HomeDiagramRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    Box(modifier.border(1.dp, tc.br, CORNER_MD).clip(CORNER_MD).testTag("home-diagrams-row-${row.id}")) {
        HoverBox(Modifier.fillMaxWidth().testTag("home-diagrams-open-${row.id}"), onClick = onOpen) {
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeDiagramThumbnail(state, row.id, row.updatedAt)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(
                        row.title,
                        color = tc.tx,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.noTicketBadge) NoTicketBadge()
                }
                AppText(row.participants, color = tc.td, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppText(row.updated, color = tc.td, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.ticketLabel.isNotEmpty()) HomeDiagramTicketLine(row)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp).background(tc.p.copy(alpha = HOME_DIAGRAMS_MENU_BACKDROP_ALPHA), CORNER_MD)) {
            HomeDiagramRowMenu(row.id, onRename, onDelete, onReclaimFocus)
        }
    }
}

@Composable
private fun HomeDiagramTicketLine(row: HomeDiagramRow) {
    val tc = tc()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val kind = if ('›' in row.ticketLabel) RecentKind.ARCHIVE else RecentKind.LOG
        Icon(recentKindIcon(kind), contentDescription = null, tint = tc.td, modifier = Modifier.size(12.dp))
        AppText(
            row.ticketLabel,
            color = tc.ts,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("home-diagrams-ticket-${row.id}"),
        )
    }
}

/**
 * The preview of the diagram [id] as of [updatedAt]: a neutral glyph while it is drawn (or when it cannot be), then the image. A cached
 * thumbnail shows on the first frame; otherwise [HomeDiagramThumbnails.load] draws it off the UI thread.
 */
@Composable
private fun HomeDiagramThumbnail(state: AppState, id: String, updatedAt: Long) {
    val tc = tc()
    val settings = state.settings
    val key = HomeDiagramThumbKey(id, updatedAt, settings.theme.ordinal)
    val image by produceState<ImageBitmap?>(HomeDiagramThumbnails.cache.get(key)?.toComposeImageBitmap(), key) {
        if (value == null) {
            value = HomeDiagramThumbnails.load(
                key = key,
                theme = { document -> resolveSeq3ThemeColors(document, settings).toSeq3RasterTheme() },
                documentOf = { itemId -> state.seq3Sessions.libraryItem(itemId)?.parsed?.document },
            )?.let { drawn -> withContext(Dispatchers.Default) { drawn.toComposeImageBitmap() } }
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(HOME_DIAGRAMS_PREVIEW_ASPECT).clip(CORNER_MD).background(tc.bg).border(1.dp, tc.br, CORNER_MD),
        contentAlignment = Alignment.Center,
    ) {
        val ready = image
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = "Diagram preview",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize().testTag("home-diagrams-thumb-$id"),
            )
        } else {
            Icon(
                Icons.Outlined.AccountTree,
                contentDescription = null,
                tint = tc.td.copy(alpha = HOME_DIAGRAMS_PREVIEW_ALPHA),
                modifier = Modifier.size(28.dp).testTag("home-diagrams-thumb-placeholder-$id"),
            )
        }
    }
}

@Composable
private fun NoTicketBadge() {
    val tc = tc()
    Box(Modifier.background(tc.hv, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("home-diagrams-no-ticket")) {
        AppText("No ticket", color = tc.ts, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

/** The "⋯" button of a row and its menu (Rename…, Delete…). Closing it, by choosing an entry or by dismissing it, gives root focus back. */
@Composable
private fun HomeDiagramRowMenu(id: String, onRename: () -> Unit, onDelete: () -> Unit, onReclaimFocus: () -> Unit) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    Box {
        HoverBox(
            Modifier.size(28.dp).clip(CORNER_MD).testTag("home-diagrams-menu-$id"),
            onClick = { open = !open },
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = "Diagram actions", tint = tc.ts, modifier = Modifier.size(16.dp))
            }
        }
        if (open) {
            val density = LocalDensity.current.density
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (HOME_DIAGRAMS_MENU_OFFSET_DP * density).roundToInt()),
                onDismissRequest = {
                    open = false
                    onReclaimFocus()
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(HOME_DIAGRAMS_MENU_WIDTH_DP.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp)
                        .testTag("home-diagrams-menu"),
                ) {
                    CtxItem(Icons.Outlined.DriveFileRenameOutline, "Rename…", tag = "home-diagrams-rename-$id") {
                        open = false
                        onRename()
                        onReclaimFocus()
                    }
                    CtxItem(Icons.Outlined.Delete, "Delete…", tag = "home-diagrams-delete-$id") {
                        open = false
                        onDelete()
                        onReclaimFocus()
                    }
                }
            }
        }
    }
}
