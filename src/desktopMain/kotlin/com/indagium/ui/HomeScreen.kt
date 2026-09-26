@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.HomeRecentsLayout
import com.indagium.model.LogTab
import com.indagium.model.MAX_HOME_RECENT_GRID_COLUMNS
import com.indagium.model.MIN_HOME_RECENT_GRID_COLUMNS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

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

/** Type-filter values for the Recent-files filter row's "Type" pill. */
internal enum class RecentTypeFilter { ALL, LOGS, ARCHIVES, NOTES }

/** "Modified" pill values. [TODAY] compares local calendar days, not a rolling 24h window. */
internal enum class RecentModifiedFilter { ANY_TIME, TODAY, LAST_7_DAYS, LAST_30_DAYS }

/** "Size" pill values. Boundaries: [UNDER_10_MB] is `< 10 MB`, [BETWEEN_10_AND_100_MB] is
 *  `10 MB..100 MB` inclusive, [OVER_100_MB] is `> 100 MB`. */
internal enum class RecentSizeFilter { ANY_SIZE, UNDER_10_MB, BETWEEN_10_AND_100_MB, OVER_100_MB }

/** "Sort" pill values. [RECENTLY_OPENED] is the default and applies no reordering at all — it
 *  trusts the incoming list to already be in `AppState.recentFiles` order. */
internal enum class RecentSortOption { RECENTLY_OPENED, MODIFIED_NEWEST, NAME_AZ, SIZE_LARGEST }

/** The home tab's Recent-files filter-row state: one value per pill. The all-default instance
 *  (used as both the initial state and the "Reset" target) is exactly `RecentFilters()` — see
 *  [isDefault], which every pill's highlight and the "Reset" link's visibility key off of. */
internal data class RecentFilters(
    val type: RecentTypeFilter = RecentTypeFilter.ALL,
    val modified: RecentModifiedFilter = RecentModifiedFilter.ANY_TIME,
    val size: RecentSizeFilter = RecentSizeFilter.ANY_SIZE,
    val sort: RecentSortOption = RecentSortOption.RECENTLY_OPENED,
) {
    val isDefault: Boolean get() = this == RecentFilters()
}

private fun matchesType(kind: RecentKind, filter: RecentTypeFilter): Boolean = when (filter) {
    RecentTypeFilter.ALL -> true
    RecentTypeFilter.LOGS -> kind == RecentKind.LOG
    RecentTypeFilter.ARCHIVES -> kind == RecentKind.ARCHIVE
    RecentTypeFilter.NOTES -> kind == RecentKind.NOTES
}

private fun startOfLocalDay(epochMs: Long): Long =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** A null [lastModifiedMs] (missing file) fails every non-default filter — see [RecentEntry]'s own
 *  KDoc on why a missing entry still exists in the list at all, and [filterRecentEntries]'s KDoc on
 *  why that's the desired exclusion behaviour here specifically. */
private fun matchesModified(lastModifiedMs: Long?, filter: RecentModifiedFilter, now: Long): Boolean {
    if (filter == RecentModifiedFilter.ANY_TIME) return true
    val modified = lastModifiedMs ?: return false
    return when (filter) {
        RecentModifiedFilter.ANY_TIME -> true
        RecentModifiedFilter.TODAY -> startOfLocalDay(modified) == startOfLocalDay(now)
        RecentModifiedFilter.LAST_7_DAYS -> now - modified <= 7 * MILLIS_PER_DAY
        RecentModifiedFilter.LAST_30_DAYS -> now - modified <= 30 * MILLIS_PER_DAY
    }
}

private const val TEN_MB_BYTES = 10L * 1024 * 1024
private const val HUNDRED_MB_BYTES = 100L * 1024 * 1024

private fun matchesSize(sizeBytes: Long?, filter: RecentSizeFilter): Boolean {
    if (filter == RecentSizeFilter.ANY_SIZE) return true
    val size = sizeBytes ?: return false
    return when (filter) {
        RecentSizeFilter.ANY_SIZE -> true
        RecentSizeFilter.UNDER_10_MB -> size < TEN_MB_BYTES
        RecentSizeFilter.BETWEEN_10_AND_100_MB -> size in TEN_MB_BYTES..HUNDRED_MB_BYTES
        RecentSizeFilter.OVER_100_MB -> size > HUNDRED_MB_BYTES
    }
}

/** [RECENTLY_OPENED] passes [entries] through untouched (the incoming order IS that order — see
 *  [RecentSortOption.RECENTLY_OPENED]'s KDoc). The other three put a missing file ([sizeBytes] or
 *  [lastModifiedMs] null) last regardless of direction, via the `== null` boolean key sorting
 *  false-before-true. */
private fun sortRecentEntries(entries: List<RecentEntry>, sort: RecentSortOption): List<RecentEntry> =
    when (sort) {
        RecentSortOption.RECENTLY_OPENED -> entries
        RecentSortOption.MODIFIED_NEWEST -> entries.sortedWith(
            compareBy<RecentEntry> { it.lastModifiedMs == null }.thenByDescending { it.lastModifiedMs ?: 0L },
        )
        RecentSortOption.NAME_AZ -> entries.sortedBy { it.name.lowercase(Locale.US) }
        RecentSortOption.SIZE_LARGEST -> entries.sortedWith(
            compareBy<RecentEntry> { it.sizeBytes == null }.thenByDescending { it.sizeBytes ?: 0L },
        )
    }

/** Matches the file name **and** its containing folder — a blank query and default [filters] both
 *  pass every entry through unchanged (including missing ones for the *text* filter; missing ones
 *  ARE dropped by a non-default [RecentModifiedFilter]/[RecentSizeFilter] — see [matchesModified]/
 *  [matchesSize]). [now] defaults to the real clock so every existing 2-arg call site (UI code, and
 *  the pre-filter-row tests in ui/HomeScreenTest.kt) is unaffected; tests that exercise date
 *  filtering pass a fixed [now] explicitly. */
internal fun filterRecentEntries(
    entries: List<RecentEntry>,
    query: String,
    filters: RecentFilters = RecentFilters(),
    now: Long = System.currentTimeMillis(),
): List<RecentEntry> {
    val needle = query.trim().lowercase(Locale.US)
    val matched = entries.filter { entry ->
        (needle.isEmpty() ||
            entry.name.lowercase(Locale.US).contains(needle) ||
            (File(entry.path).parent ?: "").lowercase(Locale.US).contains(needle)) &&
            matchesType(entry.kind, filters.type) &&
            matchesModified(entry.lastModifiedMs, filters.modified, now) &&
            matchesSize(entry.sizeBytes, filters.size)
    }
    return sortRecentEntries(matched, filters.sort)
}

/** Display label for a filter pill's current value, e.g. "Type: Logs". */
internal fun recentTypeFilterLabel(filter: RecentTypeFilter): String = when (filter) {
    RecentTypeFilter.ALL -> "All"
    RecentTypeFilter.LOGS -> "Logs"
    RecentTypeFilter.ARCHIVES -> "Archives"
    RecentTypeFilter.NOTES -> "Notes"
}

internal fun recentModifiedFilterLabel(filter: RecentModifiedFilter): String = when (filter) {
    RecentModifiedFilter.ANY_TIME -> "Any time"
    RecentModifiedFilter.TODAY -> "Today"
    RecentModifiedFilter.LAST_7_DAYS -> "Last 7 days"
    RecentModifiedFilter.LAST_30_DAYS -> "Last 30 days"
}

internal fun recentSizeFilterLabel(filter: RecentSizeFilter): String = when (filter) {
    RecentSizeFilter.ANY_SIZE -> "Any size"
    RecentSizeFilter.UNDER_10_MB -> "Under 10 MB"
    RecentSizeFilter.BETWEEN_10_AND_100_MB -> "10–100 MB"
    RecentSizeFilter.OVER_100_MB -> "Over 100 MB"
}

internal fun recentSortOptionLabel(filter: RecentSortOption): String = when (filter) {
    RecentSortOption.RECENTLY_OPENED -> "Recently opened"
    RecentSortOption.MODIFIED_NEWEST -> "Modified, newest first"
    RecentSortOption.NAME_AZ -> "Name A–Z"
    RecentSortOption.SIZE_LARGEST -> "Size, largest first"
}

/** What the Recent-files section body should render, decided purely from whether a stat read has
 *  ever completed and what it found — see [homeRecentSectionMode]. [LOADING] is only reachable
 *  before the very first read finishes in this run (see [AppState.homeRecentEntriesCache]'s own
 *  doc): once any read has completed, a later re-read (tab switch, `recentFiles` changing) shows the
 *  previous result in place rather than reverting to [LOADING], which is the whole point of the
 *  flicker fix (item 1). */
internal enum class HomeRecentSectionMode { LOADING, EMPTY_NO_FILES, EMPTY_FILTERED, RESULTS }

/** Pure decision behind [HomeRecentSection]'s `when` — see [HomeRecentSectionMode]'s own doc for
 *  what each value means and why [entriesLoaded] (not `entries.isEmpty()`) is what gates [LOADING]. */
internal fun homeRecentSectionMode(
    entriesLoaded: Boolean,
    entries: List<RecentEntry>,
    filtered: List<RecentEntry>,
): HomeRecentSectionMode = when {
    !entriesLoaded -> HomeRecentSectionMode.LOADING
    entries.isEmpty() -> HomeRecentSectionMode.EMPTY_NO_FILES
    filtered.isEmpty() -> HomeRecentSectionMode.EMPTY_FILTERED
    else -> HomeRecentSectionMode.RESULTS
}

/** The Recent-files empty state message: only mentions the text query when one is actually active,
 *  per item 1's ask that a filter-only miss doesn't read as if it were a text-search miss. */
internal fun homeRecentEmptyMessage(query: String, filters: RecentFilters): String {
    val trimmedQuery = query.trim()
    return when {
        trimmedQuery.isNotEmpty() && !filters.isDefault -> "Nothing matches \"$trimmedQuery\" with these filters"
        trimmedQuery.isNotEmpty() -> "Nothing matches \"$trimmedQuery\""
        else -> "Nothing matches these filters"
    }
}

/** Shared LOG/ARCHIVE/NOTES/OTHER -> icon mapping for [RecentGridCard]'s centered pictogram and
 *  [RecentListRow]'s small leading icon — one mapping so grid and list never drift apart. */
internal fun recentKindIcon(kind: RecentKind): ImageVector = when (kind) {
    RecentKind.LOG -> Icons.AutoMirrored.Outlined.Subject
    RecentKind.ARCHIVE -> Icons.Outlined.FolderZip
    RecentKind.NOTES -> Icons.Outlined.EditNote
    RecentKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
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
        CaptureLauncherContent(
            state = state,
            launcherTabId = tab.id,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onReclaimFocus = onReclaimFocus,
        )
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
    // Seeded from AppState.homeRecentEntriesCache (survives this composable's own disposal on tab
    // switch — see that field's doc) rather than emptyList(), so re-entering the New tab paints the
    // previous grid/list on the very first frame instead of "No recent files yet" while this re-stats
    // in the background (item 1 of the flicker fix). Only null before any read has ever completed.
    val entries by produceState(initialValue = state.homeRecentEntriesCache, state.recentFiles) {
        value = withContext(Dispatchers.IO) { readRecentEntries(state.recentFiles) }.also { state.homeRecentEntriesCache = it }
    }
    val resolvedEntries = entries.orEmpty()
    val now = remember(resolvedEntries) { System.currentTimeMillis() }
    val filters = state.homeRecentFilters
    val filtered = remember(resolvedEntries, state.homeRecentFilter, filters, now) {
        filterRecentEntries(resolvedEntries, state.homeRecentFilter, filters, now)
    }
    val mode = homeRecentSectionMode(entriesLoaded = entries != null, entries = resolvedEntries, filtered = filtered)
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
            if (layout == HomeRecentsLayout.GRID) {
                HomeRecentGridColumnsStepper(
                    columns = state.settings.homeRecentGridColumns,
                    onColumns = { columns ->
                        state.updateSettings { it.copy(homeRecentGridColumns = columns) }
                        onReclaimFocus()
                    },
                )
            }
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
        HomeRecentFilterRow(
            filters = filters,
            onFilters = { state.homeRecentFilters = it },
            onReclaimFocus = onReclaimFocus,
        )
        when (mode) {
            // Nothing has ever been read yet (only reachable on this run's very first stat pass —
            // see HomeRecentSectionMode's own doc): reserve the space rather than claiming there are
            // no recent files, which would immediately be contradicted a frame later.
            HomeRecentSectionMode.LOADING -> Box(Modifier.weight(1f))
            HomeRecentSectionMode.EMPTY_NO_FILES -> HomeRecentEmptyState("No recent files yet", Modifier.weight(1f))
            HomeRecentSectionMode.EMPTY_FILTERED -> HomeRecentEmptyState(
                homeRecentEmptyMessage(state.homeRecentFilter, filters),
                Modifier.weight(1f),
            )
            HomeRecentSectionMode.RESULTS -> if (layout == HomeRecentsLayout.GRID) {
                RecentGrid(
                    entries = filtered,
                    now = now,
                    preferredColumns = state.settings.homeRecentGridColumns,
                    onOpen = { entry -> state.openPath(File(entry.path)) },
                    onReclaimFocus = onReclaimFocus,
                    modifier = Modifier.weight(1f),
                )
            } else {
                RecentList(
                    entries = filtered,
                    now = now,
                    onOpen = { entry -> state.openPath(File(entry.path)) },
                    onReclaimFocus = onReclaimFocus,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeRecentEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AppText(message, color = tc().td, fontSize = 12.sp)
    }
}

/** The compact filter-pill row under the "Filter recent files" field: one [RecentFilterPill] per
 *  [RecentFilters] field, plus a "Reset" link that only appears once any pill has left its
 *  default. */
@Composable
private fun HomeRecentFilterRow(
    filters: RecentFilters,
    onFilters: (RecentFilters) -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val defaults = remember { RecentFilters() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RecentFilterPill(
            label = "Type",
            values = RecentTypeFilter.entries,
            selected = filters.type,
            displayName = ::recentTypeFilterLabel,
            isDefault = filters.type == defaults.type,
            onSelect = { onFilters(filters.copy(type = it)) },
            onReclaimFocus = onReclaimFocus,
        )
        RecentFilterPill(
            label = "Modified",
            values = RecentModifiedFilter.entries,
            selected = filters.modified,
            displayName = ::recentModifiedFilterLabel,
            isDefault = filters.modified == defaults.modified,
            onSelect = { onFilters(filters.copy(modified = it)) },
            onReclaimFocus = onReclaimFocus,
        )
        RecentFilterPill(
            label = "Size",
            values = RecentSizeFilter.entries,
            selected = filters.size,
            displayName = ::recentSizeFilterLabel,
            isDefault = filters.size == defaults.size,
            onSelect = { onFilters(filters.copy(size = it)) },
            onReclaimFocus = onReclaimFocus,
        )
        RecentFilterPill(
            label = "Sort",
            values = RecentSortOption.entries,
            selected = filters.sort,
            displayName = ::recentSortOptionLabel,
            isDefault = filters.sort == defaults.sort,
            onSelect = { onFilters(filters.copy(sort = it)) },
            onReclaimFocus = onReclaimFocus,
        )
        if (!filters.isDefault) {
            HomeRecentFilterResetLink(onClick = { onFilters(RecentFilters()); onReclaimFocus() })
        }
    }
}

/** One "Label: value" pill. Click toggles a small menu (the package-local [Popup] from
 *  ui/MirrorOccludingLayers.kt, per this screen's own convention) listing every value in
 *  [values]; picking one closes the menu and reclaims root focus, same as every other clickable on
 *  this screen (see [HomeTile]'s KDoc on why that matters). Highlighted with an accent border/tint
 *  whenever [isDefault] is false. */
@Composable
private fun <T> RecentFilterPill(
    label: String,
    values: List<T>,
    selected: T,
    displayName: (T) -> String,
    isDefault: Boolean,
    onSelect: (T) -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(if (!isDefault) tc.ac.copy(alpha = 0.14f) else Color.Transparent, shape)
                .border(1.dp, if (!isDefault) tc.ac else tc.br, shape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AppText("$label: ${displayName(selected)}", color = if (!isDefault) tc.ac else tc.ts, fontSize = 10.sp)
            AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 9.sp)
        }
        if (expanded) {
            val density = LocalDensity.current.density
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, (28 * density).roundToInt()),
                onDismissRequest = { expanded = false; onReclaimFocus() },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(170.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp),
                ) {
                    values.forEach { value ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelect(value); expanded = false; onReclaimFocus() },
                        ) {
                            AppText(
                                displayName(value),
                                color = if (value == selected) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecentFilterResetLink(onClick: () -> Unit) {
    val tc = tc()
    HoverBox(modifier = Modifier.clip(RoundedCornerShape(4.dp)), onClick = onClick) {
        AppText(
            "Reset",
            color = tc.ac,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

private const val RECENT_GRID_MIN_CARD_WIDTH_DP = 110

/** The user's chosen density (3–8 per row, `AppSettings.homeRecentGridColumns`), clamped down so a
 *  card never renders narrower than [RECENT_GRID_MIN_CARD_WIDTH_DP] — a narrow window wins over the
 *  stored preference rather than letting cards overflow or squeeze unreadably. Pure so it's testable
 *  without a composition (see HomeScreenGridColumnsTest). */
internal fun recentGridColumnCount(availableWidthDp: Float, preferredColumns: Int): Int {
    val wanted = preferredColumns.coerceIn(MIN_HOME_RECENT_GRID_COLUMNS, MAX_HOME_RECENT_GRID_COLUMNS)
    val maxByWidth = (availableWidthDp / RECENT_GRID_MIN_CARD_WIDTH_DP).toInt().coerceAtLeast(1)
    return wanted.coerceAtMost(maxByWidth)
}

/** Compact -/+ stepper next to the Grid/List toggle (only shown in Grid layout) for choosing how
 *  many recent-file cards sit per row, 3 through 8. Mirrors the stepper affordance rather than a
 *  6-way SegmentedControl, which at this row's width would crowd the Grid/List toggle beside it. */
@Composable
private fun HomeRecentGridColumnsStepper(columns: Int, onColumns: (Int) -> Unit) {
    val tc = tc()
    Row(
        Modifier.border(1.dp, tc.br, RoundedCornerShape(6.dp)).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StepperButton("−", enabled = columns > MIN_HOME_RECENT_GRID_COLUMNS) {
            onColumns((columns - 1).coerceAtLeast(MIN_HOME_RECENT_GRID_COLUMNS))
        }
        AppText(
            "$columns/row", color = tc.td, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        StepperButton("+", enabled = columns < MAX_HOME_RECENT_GRID_COLUMNS) {
            onColumns((columns + 1).coerceAtMost(MAX_HOME_RECENT_GRID_COLUMNS))
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, color = if (enabled) tc.tx else tc.td.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Chunked `Row`s inside one `verticalScroll`, not `LazyVerticalGrid` — recents are capped at 30
 *  entries by `AppState.rememberRecentFile`, and a lazy grid nested inside a scrolling `Column`
 *  (this section sits inside [HomeOpenZone]'s own scroll-free layout, but the pattern is avoided
 *  everywhere in this file on principle) hits Compose's classic nested-infinite-constraint crash. */
@Composable
private fun RecentGrid(
    entries: List<RecentEntry>,
    now: Long,
    preferredColumns: Int,
    onOpen: (RecentEntry) -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = recentGridColumnCount(maxWidth.value, preferredColumns)
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
                                columns = columns,
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

/** Above this column count, cards shrink their pictogram/padding/text so 7–8/row still fits without
 *  overflow — the same breakpoint feel as [RecentTypeBadge]'s already-small 9sp label. */
private const val RECENT_GRID_COMPACT_COLUMNS = 6

@Composable
private fun RecentGridCard(
    entry: RecentEntry,
    now: Long,
    columns: Int,
    onOpen: () -> Unit,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val compact = columns > RECENT_GRID_COMPACT_COLUMNS
    val iconSize = if (compact) 26.dp else 40.dp
    val padding = if (compact) 6.dp else 10.dp
    val nameFontSize = if (compact) 10.sp else 12.sp
    val metaFontSize = if (compact) 9.sp else 10.sp
    HoverBox(
        modifier = modifier.aspectRatio(1.3f).border(1.dp, tc.br, CORNER_MD).clip(CORNER_MD),
        onClick = {
            onOpen()
            onReclaimFocus()
        },
    ) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            RecentTypeBadge(entry.kind)
            // Fills the space between the badge and the name/meta block below with a centered
            // pictogram (item 2) instead of leaving it empty.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    recentKindIcon(entry.kind),
                    contentDescription = null,
                    tint = tc.td.copy(alpha = if (entry.exists) 1f else 0.5f),
                    modifier = Modifier.size(iconSize),
                )
            }
            RecentEntryTooltipArea(entry) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText(
                        entry.name,
                        color = if (entry.exists) tc.tx else tc.td,
                        fontSize = nameFontSize,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        formatRecentMeta(entry, now), color = tc.td, fontSize = metaFontSize,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Hover tooltip for a recent-file name/path that can truncate ([RecentGridCard]'s name at narrow
 *  grid widths, [RecentListRow]'s single-line name and path cells) — same bordered-box style as
 *  SettingsDialog.kt's own `appDataPath` tooltip, showing the full name and full path together so
 *  a click-worthy card never hides which file it actually is. */
@Composable
private fun RecentEntryTooltipArea(entry: RecentEntry, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tc = tc()
    TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .background(tc.p2, RoundedCornerShape(4.dp))
                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText(entry.name, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    AppText(entry.path, color = tc.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        modifier = modifier,
        content = content,
    )
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
            Icon(
                recentKindIcon(entry.kind),
                contentDescription = null,
                tint = tc.td.copy(alpha = if (entry.exists) 1f else 0.5f),
                modifier = Modifier.size(18.dp),
            )
            RecentEntryTooltipArea(entry, modifier = Modifier.weight(1f)) {
                Column {
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
