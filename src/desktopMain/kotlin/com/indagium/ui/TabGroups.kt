package com.indagium.ui

import androidx.compose.ui.graphics.toArgb
import java.util.UUID

/** Optional semantic icon shown in a group header. */
internal enum class TabGroupIcon {
    NONE,
    FOLDER,
    BOOKMARK,
    BUG,
    CODE,
    DEVICE,
    FLAG,
    STAR,
    SEARCH,
}

/** A named, contiguous run of ordinary log/capture and diagram tabs in the shared strip. */
internal data class TabGroup(
    val id: String,
    val name: String,
    /** The group's colour as packed ARGB: any colour of the app's shared picker, not a fixed palette. */
    val colorArgb: Int,
    val collapsed: Boolean,
    val members: List<TabRef>,
    val icon: TabGroupIcon = TabGroupIcon.NONE,
)

/** The first colour of the shared highlighter palette that no group in [groups] uses; the palette is
 * reused from its start once every entry is taken. */
internal fun defaultTabGroupColorArgb(groups: List<TabGroup>): Int {
    val used = groups.mapTo(HashSet()) { it.colorArgb }
    return HL_COLORS.firstOrNull { it.toArgb() !in used }?.toArgb()
        ?: HL_COLORS[groups.size % HL_COLORS.size].toArgb()
}

/** [base] when no name in [taken] equals it, else "[base] 2", "[base] 3", ... the first free one. */
internal fun uniqueTabGroupName(base: String, taken: Collection<String>): String {
    val clean = base.trim().ifEmpty { DEFAULT_TAB_GROUP_NAME }
    if (clean !in taken) return clean
    return generateSequence(2) { it + 1 }.map { "$clean $it" }.first { it !in taken }
}

private const val DEFAULT_TAB_GROUP_NAME = "Group"

/** A group id captured when an open operation begins. It intentionally does not keep the group
 * alive: if the user removes it before a background open finishes, publication stays ungrouped. */
internal fun AppState.tabGroupIdForOrigin(ref: TabRef?): String? = ref?.let { tabGroupOf(it)?.id }

internal fun AppState.activeStripTabGroupId(): String? = synchronized(stateLock) {
    val ref = when (val surface = activeSurface) {
        is ActiveSurface.Log -> TabRef.Log(surface.tabId)
        is ActiveSurface.Diagram3 -> TabRef.Diagram(surface.sessionId)
        is ActiveSurface.Tests -> null
        null -> activeTabId.takeIf { it.isNotBlank() }?.let(TabRef::Log)
    } ?: return@synchronized null
    if (!isLiveGroupableRef(ref)) return@synchronized null
    tabGroups.firstOrNull { ref in it.members }?.id
}

/** Current canonical strip order, including every live backing tab and keeping each group
 * contiguous. This is a pure read; writes go through [commitTabOrder]. */
internal fun AppState.reconciledTabOrder(): List<TabRef> =
    reconciledTabOrderLocked()

internal fun AppState.reconciledTabOrderLocked(): List<TabRef> {
    val reconciled = reconcileTabOrder(
        previousOrder = tabOrder,
        logTabIds = tabs.map { it.id },
        diagramSessionIds = seq3Sessions.sessions.map { it.id },
        testsOpen = testsTabOpen,
    )
    return canonicalGroupedOrder(reconciled, tabGroups).first
}

/** Commits a strip reorder while retaining every live item, enforcing contiguous groups, and
 * updating each group's [TabGroup.members] list to match the committed order. */
internal fun AppState.commitTabOrder(order: List<TabRef>) = synchronized(stateLock) {
    commitTabOrderLocked(order)
}

internal fun AppState.commitTabOrderLocked(order: List<TabRef>) {
    val reconciled = reconcileTabOrder(
        previousOrder = order,
        logTabIds = tabs.map { it.id },
        diagramSessionIds = seq3Sessions.sessions.map { it.id },
        testsOpen = testsTabOpen,
    )
    val (canonical, normalizedGroups) = canonicalGroupedOrder(reconciled, tabGroups)
    tabOrder = canonical
    tabGroups = normalizedGroups
    // Keep the separate backing stores in the same per-kind order as the strip. Existing close-left/
    // right and keyboard navigation still read these stores, while the mixed strip order remains
    // the source for how log and diagram tabs interleave.
    val (logOrder, diagramOrder) = partitionTabOrder(canonical)
    tabs = logOrder.mapNotNull { id -> tabs.firstOrNull { it.id == id } }
    seq3Sessions.reorderSessions(diagramOrder)
}

private fun canonicalGroupedOrder(order: List<TabRef>, groups: List<TabGroup>): Pair<List<TabRef>, List<TabGroup>> {
    val present = order.toHashSet()
    val groupIdByRef = linkedMapOf<TabRef, String>()
    groups.forEach { group ->
        group.members.forEach { ref ->
            if (ref in present) groupIdByRef.putIfAbsent(ref, group.id)
        }
    }
    val membersByGroup = groups.associate { group ->
        group.id to order.filter { groupIdByRef[it] == group.id }
    }
    val emittedGroups = HashSet<String>()
    val canonical = buildList {
        order.forEach { ref ->
            val groupId = groupIdByRef[ref]
            if (groupId == null) {
                add(ref)
            } else if (emittedGroups.add(groupId)) {
                addAll(membersByGroup[groupId].orEmpty())
            }
        }
    }
    val normalizedGroups = groups.mapNotNull { group ->
        val members = canonical.filter { groupIdByRef[it] == group.id }
        group.takeIf { members.isNotEmpty() }?.copy(members = members)
    }
    return canonical to normalizedGroups
}

private fun AppState.isLiveGroupableRef(ref: TabRef): Boolean = when (ref) {
    is TabRef.Log -> tabs.any { it.id == ref.tabId && !it.isCaptureLauncher }
    is TabRef.Diagram -> seq3Sessions.sessions.any { it.id == ref.sessionId }
    TabRef.Tests -> false
}

/** A new group's default name: the tab's short display label (the file name, or the diagram's title). */
private fun AppState.defaultTabGroupName(ref: TabRef): String = when (ref) {
    is TabRef.Log -> tabs.firstOrNull { it.id == ref.tabId }?.filename
    is TabRef.Diagram -> seq3Sessions.sessions.firstOrNull { it.id == ref.sessionId }?.document?.title
    TabRef.Tests -> null
}.orEmpty()

/** Creates a one-member group. Home/capture-launcher and Tests tabs are deliberately not groupable. */
internal fun AppState.createTabGroup(ref: TabRef): String? = synchronized(stateLock) {
    if (!isLiveGroupableRef(ref)) return@synchronized null
    val currentOrder = reconciledTabOrderLocked()
    val groupsWithoutRef = tabGroups.mapNotNull { existing ->
        val members = existing.members.filterNot { it == ref }
        existing.takeIf { members.isNotEmpty() }?.copy(members = members)
    }
    // The groups the tab leaves behind (an emptied one disappears) do not count as taken.
    val group = TabGroup(
        id = UUID.randomUUID().toString(),
        name = uniqueTabGroupName(defaultTabGroupName(ref), groupsWithoutRef.map { it.name }),
        colorArgb = defaultTabGroupColorArgb(groupsWithoutRef),
        collapsed = false,
        members = listOf(ref),
    )
    tabGroups = groupsWithoutRef + group
    commitTabOrderLocked(currentOrder)
    group.id
}

/** Moves [ref] into [id], inserting before [before] when it is already in that group, otherwise
 * appending it to the group's run. */
internal fun AppState.assignTabToGroup(ref: TabRef, id: String, before: TabRef? = null) = synchronized(stateLock) {
    val target = tabGroups.firstOrNull { it.id == id } ?: return@synchronized
    if (!isLiveGroupableRef(ref)) return@synchronized
    if (before == ref && ref in target.members) return@synchronized
    if (before == null && target.members.lastOrNull() == ref) return@synchronized

    val withoutRefGroups = tabGroups.mapNotNull { group ->
        val members = group.members.filterNot { it == ref }
        when {
            members.isNotEmpty() -> group.copy(members = members)
            group.id == id -> group.copy(members = emptyList())
            else -> null
        }
    }
    val baseOrder = canonicalGroupedOrder(reconciledTabOrderLocked(), withoutRefGroups).first.filterNot { it == ref }
    val targetMembers = baseOrder.filter { candidate ->
        withoutRefGroups.firstOrNull { it.id == id }?.members?.contains(candidate) == true
    }
    val insertionIndex = before?.takeIf { it in targetMembers }?.let(baseOrder::indexOf)
        ?: targetMembers.lastOrNull()?.let { baseOrder.indexOf(it) + 1 }
        ?: baseOrder.size
    val nextOrder = baseOrder.toMutableList().apply { add(insertionIndex.coerceIn(0, size), ref) }
    tabGroups = withoutRefGroups.map { group ->
        if (group.id == id) group.copy(members = targetMembers + ref) else group
    }
    commitTabOrderLocked(nextOrder)
}

/** Removes one tab from its group and drops the group if it becomes empty. */
internal fun AppState.removeTabFromGroup(ref: TabRef) = synchronized(stateLock) {
    removeTabFromGroupLocked(ref)
}

internal fun AppState.removeTabFromGroupLocked(ref: TabRef) {
    val nextGroups = tabGroups.mapNotNull { group ->
        val members = group.members.filterNot { it == ref }
        group.takeIf { members.isNotEmpty() }?.copy(members = members)
    }
    if (nextGroups != tabGroups) {
        tabGroups = nextGroups
        // A manual ungroup keeps the strip order otherwise intact. If a middle member is removed,
        // the remaining members compress together and the ungrouped tab follows that block.
        commitTabOrderLocked(tabOrder)
    }
}

/** Returns the live group containing [ref], if any. */
internal fun AppState.tabGroupOf(ref: TabRef): TabGroup? = synchronized(stateLock) {
    if (!isLiveGroupableRef(ref)) return@synchronized null
    tabGroups.firstOrNull { ref in it.members }
}

internal fun AppState.renameTabGroup(id: String, name: String) = synchronized(stateLock) {
    val cleaned = name.trim()
    if (cleaned.isEmpty()) return@synchronized
    tabGroups = tabGroups.map { if (it.id == id) it.copy(name = cleaned) else it }
}

internal fun AppState.setTabGroupColor(id: String, colorArgb: Int) = synchronized(stateLock) {
    tabGroups = tabGroups.map { if (it.id == id) it.copy(colorArgb = colorArgb) else it }
}

internal fun AppState.setTabGroupIcon(id: String, icon: TabGroupIcon) = synchronized(stateLock) {
    tabGroups = tabGroups.map { if (it.id == id) it.copy(icon = icon) else it }
}

internal fun AppState.toggleTabGroupCollapsed(id: String) = synchronized(stateLock) {
    tabGroups = tabGroups.map { if (it.id == id) it.copy(collapsed = !it.collapsed) else it }
}

/** Removes the group wrapper while leaving its tabs at their current positions. */
internal fun AppState.ungroupTabs(id: String) = synchronized(stateLock) {
    if (tabGroups.none { it.id == id }) return@synchronized
    tabGroups = tabGroups.filterNot { it.id == id }
    commitTabOrderLocked(tabOrder)
}

/** Moves the whole contiguous group run before [before], or to the end when null. */
internal fun AppState.moveTabGroup(id: String, before: TabRef?) = synchronized(stateLock) {
    val group = tabGroups.firstOrNull { it.id == id } ?: return@synchronized
    val order = reconciledTabOrderLocked()
    val block = order.filter { it in group.members }
    if (block.isEmpty() || (before != null && before in block)) return@synchronized
    val rest = order.filterNot { it in group.members }
    val insertion = before?.let(rest::indexOf)?.takeIf { it >= 0 } ?: rest.size
    commitTabOrderLocked(rest.take(insertion) + block + rest.drop(insertion))
}

/** Adds a newly-created ref to the origin's still-live group as one atomic order/member update. */
internal fun AppState.inheritTabGroupForOpenedRef(originGroupId: String?, ref: TabRef) = synchronized(stateLock) {
    if (originGroupId == null || !isLiveGroupableRef(ref)) return@synchronized
    val target = tabGroups.firstOrNull { it.id == originGroupId } ?: return@synchronized
    if (tabGroups.any { ref in it.members }) return@synchronized
    val order = reconciledTabOrderLocked().filterNot { it == ref }
    val targetMembers = order.filter { candidate -> candidate in target.members }
    val insertion = targetMembers.lastOrNull()?.let { order.indexOf(it) + 1 } ?: order.size
    val nextOrder = order.toMutableList().apply { add(insertion, ref) }
    tabGroups = tabGroups.map { group ->
        if (group.id == originGroupId) group.copy(members = targetMembers + ref) else group
    }
    commitTabOrderLocked(nextOrder)
}

/** Called when a direct explicit activation targets a ref, including diagram activation paths. */
internal fun AppState.revealTabGroupForActivation(ref: TabRef) = synchronized(stateLock) {
    val groupId = tabGroups.firstOrNull { ref in it.members }?.id ?: return@synchronized
    tabGroups = tabGroups.map { if (it.id == groupId) it.copy(collapsed = false) else it }
}

/** The strip has separate backing stores; this routes activation through their existing paths. */
internal fun AppState.activateStripTab(ref: TabRef) {
    when (ref) {
        is TabRef.Log -> activateTab(ref.tabId)
        is TabRef.Diagram -> {
            revealTabGroupForActivation(ref)
            seq3Sessions.activate(ref.sessionId)
        }
        TabRef.Tests -> openTestsTab()
    }
}

/** Used by a backing-store close path so group membership and persisted strip order cannot retain a
 * dead log or diagram reference. */
internal fun AppState.stripTabClosed(ref: TabRef) = synchronized(stateLock) {
    removeTabFromGroupLocked(ref)
    commitTabOrderLocked(tabOrder.filterNot { it == ref })
}

/** Drops closed log refs from group membership and strip order in one bounded close-path helper. */
internal fun AppState.stripTabsClosed(tabIds: Set<String>) = synchronized(stateLock) {
    if (tabIds.isEmpty()) return@synchronized
    val refs = tabIds.mapTo(HashSet()) { TabRef.Log(it) }
    val nextGroups = tabGroups.mapNotNull { group ->
        val members = group.members.filterNot { it in refs }
        group.takeIf { members.isNotEmpty() }?.copy(members = members)
    }
    if (nextGroups != tabGroups) tabGroups = nextGroups
    commitTabOrderLocked(tabOrder.filterNot { it in refs })
}
