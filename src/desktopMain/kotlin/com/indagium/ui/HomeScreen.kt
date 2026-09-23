package com.indagium.ui

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.HomeRecentsLayout
import com.indagium.model.LogTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

// ── Pure helpers (unit-testable, no Compose — see ui/HomeScreenTest.kt) ────

/** Which badge/icon a Recent-files entry gets. Decided purely from the filename's extension —
 *  never by sniffing the file's actual bytes (unlike [com.indagium.utils.detectArchiveFormat]) —
 *  because this only drives a small cosmetic badge next to a card that's already just a path the
 *  user themselves opened before; a stat-and-guess per card is not worth an extra file read. */
internal enum class RecentKind {
    LOG,
    ARCHIVE,
    NOTES,
    OTHER,
}

private val RECENT_ARCHIVE_EXTENSIONS = setOf(
    "zip", "7z", "tar", "tgz", "gz", "bz2", "xz", "jar", "apk", "cbz",
)
private val RECENT_NOTES_EXTENSIONS = setOf("ann", "md")
private val RECENT_LOG_EXTENSIONS = setOf("log", "txt")

/** Extension-only classifier — see [RecentKind]'s own doc for why this never opens the file. */
internal fun recentKindForName(name: String): RecentKind {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    return when (ext) {
        in RECENT_NOTES_EXTENSIONS -> RecentKind.NOTES
        in RECENT_ARCHIVE_EXTENSIONS -> RecentKind.ARCHIVE
        in RECENT_LOG_EXTENSIONS -> RecentKind.LOG
        else -> RecentKind.OTHER
    }
}

/** One row of the home tab's Recent files section. [sizeBytes]/[lastModifiedMs] are null and
 *  [exists] is false for a path that no longer resolves on disk — [readRecentEntries] still
 *  returns an entry for it (so the card can show "missing" instead of silently vanishing);
 *  [AppState.pruneMissingRecentFiles] is what eventually drops it from [AppState.recentFiles]
 *  entirely, on its own schedule, not this. */
internal data class RecentEntry(
    val path: String,
    val name: String,
    val kind: RecentKind,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    val exists: Boolean,
)

/** The only filesystem access in this file — every composable below reads through this via
 *  `produceState` on [Dispatchers.IO], never directly, so stat-ing up to 30 paths (the cap
 *  [AppState] already applies to `recentFiles`) never blocks a frame. */
internal fun readRecentEntries(paths: List<String>): List<RecentEntry> = paths.map { path ->
    val file = File(path)
    val exists = file.exists()
    RecentEntry(
        path = path,
        name = file.name,
        kind = recentKindForName(file.name),
        sizeBytes = if (exists) file.length() else null,
        lastModifiedMs = if (exists) file.lastModified() else null,
        exists = exists,
    )
}

/** Matches the file name **and** its containing folder — a blank query returns every entry
 *  unchanged (including missing ones; filtering is not the place that prunes those). */
internal fun filterRecentEntries(entries: List<RecentEntry>, query: String): List<RecentEntry> {
    val needle = query.trim().lowercase(Locale.US)
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        entry.name.lowercase(Locale.US).contains(needle) ||
            (File(entry.path).parent ?: "").lowercase(Locale.US).contains(needle)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
private const val RECENT_DAYS_BEFORE_ABSOLUTE_DATE = 7L

private fun formatRelativeTime(deltaMs: Long, epochMs: Long): String {
    val delta = deltaMs.coerceAtLeast(0L)
    return when {
        delta < MILLIS_PER_MINUTE -> "just now"
        delta < MILLIS_PER_HOUR -> "${delta / MILLIS_PER_MINUTE} min ago"
        delta < MILLIS_PER_DAY -> "${delta / MILLIS_PER_HOUR} hr ago"
        delta < RECENT_DAYS_BEFORE_ABSOLUTE_DATE * MILLIS_PER_DAY -> "${delta / MILLIS_PER_DAY} d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(java.util.Date(epochMs))
    }
}

/** "size · modified" for a Recent card/row, e.g. "4.2 MB · 3 hr ago". [now] is a parameter (not
 *  `System.currentTimeMillis()` read internally) purely so this stays unit-testable with a fixed
 *  clock — see ui/HomeScreenTest.kt. A missing file (see [RecentEntry.exists]) shows neither. */
internal fun formatRecentMeta(entry: RecentEntry, now: Long): String {
    if (!entry.exists) return "File not found"
    val size = entry.sizeBytes?.let(::formatByteSize) ?: "—"
    val modified = entry.lastModifiedMs?.let { formatRelativeTime(now - it, it) } ?: "—"
    return "$size · $modified"
}

// ── Composables ──────────────────────────────────────────────────────────

/** Shared "Open" file-picker action for the home tab's Open-file and Bug-report/archive tiles,
 *  and (TabBar.kt) the toolbar's own Open button — one FileDialog implementation instead of two.
 *  No `setFilenameFilter`: it's unreliable on macOS (the native NSOpenPanel doesn't consistently
 *  invoke it), which greyed out files that would open fine by drag-and-drop. Show everything and
 *  validate after the pick — [AppState.openPathOrShowError] already routes an archive to the
 *  entry picker. */
internal fun pickLogFileAndOpen(state: AppState, title: String) {
    val fd = FileDialog(null as Frame?, title, FileDialog.LOAD)
    fd.isVisible = true
    fd.file?.let { state.openPathOrShowError(File(fd.directory, it)) }
}

private fun pickNoteFileAndOpen(state: AppState) {
    val fd = FileDialog(null as Frame?, "Open Notes File", FileDialog.LOAD)
    fd.isVisible = true
    fd.file?.let { state.openNoteFileInNewTab(File(fd.directory, it)) }
}

/** The home tab: `+` in the tab strip opens this (see [AppState.openHomeTab]/[AppState.ensureHomeTab]),
 *  a fixed 50/50 split of "Open a log" (left) and the device-capture launcher (right, unchanged
 *  behaviour — see [CaptureLauncherContent]). A static [Box] rule divides them, not [VDivider]:
 *  the split is fixed, [VDivider] is a drag handle for something meant to be resizable.
 *
 *  Routed directly from App.kt's surface `when` (never through [FileView]) so filter/notes/AI/
 *  capture-strip chrome — all of which assume a real log tab — never mounts for this tab at all. */
@Composable
internal fun HomeScreen(
    state: AppState,
    tab: LogTab,
    modifier: Modifier = Modifier,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    Row(modifier.fillMaxSize()) {
        HomeOpenZone(state = state, tab = tab, onReclaimFocus = onReclaimFocus, modifier = Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.fillMaxHeight().width(1.dp).background(tc.br))
        CaptureLauncherContent(state = state, launcherTabId = tab.id, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

private const val HOME_TILE_MAX_SIZE_DP = 128

@Composable
private fun HomeOpenZone(
    state: AppState,
    tab: LogTab,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Open a log", fontSize = 20.sp, color = tc.tx)
            AppText(
                "Start from a file, a bug report or archive, saved notes, or a past case.",
                color = tc.td,
                fontSize = 12.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile(
                label = "Open file…",
                icon = Icons.Outlined.FolderOpen,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            ) { pickLogFileAndOpen(state, "Open Log File") }
            HomeTile(
                label = "Bug report / archive",
                icon = Icons.Outlined.FolderZip,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            ) { pickLogFileAndOpen(state, "Open Bug Report or Archive") }
            HomeTile(
                label = "Open notes (.ann)",
                icon = Icons.AutoMirrored.Outlined.StickyNote2,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            ) { pickNoteFileAndOpen(state) }
            HomeTile(
                label = "Past cases",
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            ) { state.openCaseLibrary(tab.id) }
        }
        HomeRecentSection(state = state, onReclaimFocus = onReclaimFocus, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** One of the four square "Open a log" buttons. Reclaims root keyboard focus on click (see
 *  CLAUDE.md's clickable-steals-focus gotcha) — never around [InlineField], which is why this
 *  wiring lives here rather than uniformly across every clickable in the home tab. */
@Composable
private fun HomeTile(
    label: String,
    icon: ImageVector,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier
            .aspectRatio(1f)
            .widthIn(max = HOME_TILE_MAX_SIZE_DP.dp)
            .border(1.dp, tc.br, CORNER_MD)
            .clip(CORNER_MD),
        onClick = {
            onClick()
            onReclaimFocus()
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            AppText(label, color = tc.tx, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Recent files: filter field + grid/list toggle (persisted in `AppSettings.homeRecentsLayout`)
 *  above a scrollable grid or list. [AppState.homeRecentFilter] is deliberately session-only (see
 *  its own KDoc) so a stale search string never survives a restart. */
@Composable
private fun HomeRecentSection(
    state: AppState,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { state.pruneMissingRecentFiles() }
    val entries by produceState(initialValue = emptyList<RecentEntry>(), state.recentFiles) {
        value = withContext(Dispatchers.IO) { readRecentEntries(state.recentFiles) }
    }
    val filtered = remember(entries, state.homeRecentFilter) { filterRecentEntries(entries, state.homeRecentFilter) }
    val now = remember(entries) { System.currentTimeMillis() }
    val layout = state.settings.homeRecentsLayout

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineField(
                value = state.homeRecentFilter,
                onValue = { state.homeRecentFilter = it },
                placeholder = "Filter recent files",
                modifier = Modifier.weight(1f),
                onClear = { state.homeRecentFilter = "" },
            )
            SegmentedControl(
                options = listOf("Grid", "List"),
                selectedIndices = setOf(if (layout == HomeRecentsLayout.GRID) 0 else 1),
                onToggle = { index ->
                    val next = if (index == 0) HomeRecentsLayout.GRID else HomeRecentsLayout.LIST
                    state.updateSettings { it.copy(homeRecentsLayout = next) }
                    onReclaimFocus()
                },
            )
        }
        when {
            entries.isEmpty() -> HomeRecentEmptyState("No recent files yet", Modifier.weight(1f))
            filtered.isEmpty() -> HomeRecentEmptyState(
                "Nothing matches \"${state.homeRecentFilter}\"",
                Modifier.weight(1f),
            )
            layout == HomeRecentsLayout.GRID -> RecentGrid(
                entries = filtered,
                now = now,
                onOpen = { entry -> state.openPath(File(entry.path)) },
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            )
            else -> RecentList(
                entries = filtered,
                now = now,
                onOpen = { entry -> state.openPath(File(entry.path)) },
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeRecentEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AppText(message, color = tc().td, fontSize = 12.sp)
    }
}

private const val RECENT_GRID_CARD_TARGET_WIDTH_DP = 190
private const val RECENT_GRID_MAX_COLUMNS = 4

/** Chunked `Row`s inside one `verticalScroll`, not `LazyVerticalGrid` — recents are capped at 30
 *  entries by `AppState.rememberRecentFile`, and a lazy grid nested inside a scrolling `Column`
 *  (this section sits inside [HomeOpenZone]'s own scroll-free layout, but the pattern is avoided
 *  everywhere in this file on principle) hits Compose's classic nested-infinite-constraint crash. */
@Composable
private fun RecentGrid(
    entries: List<RecentEntry>,
    now: Long,
    onOpen: (RecentEntry) -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / RECENT_GRID_CARD_TARGET_WIDTH_DP.dp).toInt().coerceIn(1, RECENT_GRID_MAX_COLUMNS)
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.chunked(columns).forEach { rowEntries ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowEntries.forEach { entry ->
                            RecentGridCard(
                                entry = entry,
                                now = now,
                                onOpen = { onOpen(entry) },
                                onReclaimFocus = onReclaimFocus,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad a short final row so its cards match the width of every full row above.
                        repeat(columns - rowEntries.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = appScrollbarStyle(tc()),
            )
        }
    }
}

@Composable
private fun RecentList(
    entries: List<RecentEntry>,
    now: Long,
    onOpen: (RecentEntry) -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp)) {
            entries.forEach { entry ->
                RecentListRow(entry = entry, now = now, onOpen = { onOpen(entry) }, onReclaimFocus = onReclaimFocus)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = appScrollbarStyle(tc()),
        )
    }
}

@Composable
private fun RecentGridCard(
    entry: RecentEntry,
    now: Long,
    onOpen: () -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier.aspectRatio(1.3f).border(1.dp, tc.br, CORNER_MD).clip(CORNER_MD),
        onClick = {
            onOpen()
            onReclaimFocus()
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RecentTypeBadge(entry.kind)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(
                    entry.name,
                    color = if (entry.exists) tc.tx else tc.td,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppText(formatRecentMeta(entry, now), color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecentListRow(
    entry: RecentEntry,
    now: Long,
    onOpen: () -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onOpen()
            onReclaimFocus()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecentTypeBadge(entry.kind)
            Column(Modifier.weight(1f)) {
                AppText(
                    entry.name,
                    color = if (entry.exists) tc.tx else tc.td,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppText(
                    truncatePathForDisplay(File(entry.path).parent ?: entry.path),
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppText(formatRecentMeta(entry, now), color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecentTypeBadge(kind: RecentKind) {
    val tc = tc()
    val label = when (kind) {
        RecentKind.LOG -> "LOG"
        RecentKind.ARCHIVE -> "ZIP"
        RecentKind.NOTES -> "NOTES"
        RecentKind.OTHER -> "FILE"
    }
    Box(Modifier.background(tc.hv, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AppText(label, color = tc.ts, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}
