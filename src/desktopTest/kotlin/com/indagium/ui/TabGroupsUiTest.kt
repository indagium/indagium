package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import com.indagium.model.LogTab
import com.indagium.model.TabSizeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabGroupsUiTest {
    private companion object {
        const val NARROW_AVAILABLE_PX = 120f
        const val MIN_TAB_WIDTH_PX = 80f
        const val GROUP_HEADER_WIDTH_PX = 180f
        const val ROOMY_AVAILABLE_PX = 400f
        const val ROOMY_TAB_WIDTH_PX = 220f
        const val POPUP_WINDOW_WIDTH_PX = 1000
        const val POPUP_WINDOW_HEIGHT_PX = 800
        const val POPUP_WIDTH_PX = 200
        const val POPUP_HEIGHT_PX = 100
        const val HEADER_LEFT_PX = 400
        const val HEADER_RIGHT_PX = 500
        const val HEADER_TOP_PX = 300
        const val HEADER_HEIGHT_PX = 36
        const val CLAMPED_HEADER_LEFT_PX = 950
        const val CLAMPED_HEADER_RIGHT_PX = 990
        const val CLAMPED_HEADER_TOP_PX = 790
        const val CLAMPED_HEADER_BOTTOM_PX = 800
        const val POPUP_TEST_WIDTH_DP = 1000
        const val POPUP_TEST_HEIGHT_DP = 400
        const val POPUP_TEST_LEFT_PADDING_DP = 80
        const val POPUP_TEST_TOP_PADDING_DP = 12
        const val FIXED_GROUP_TEST_WIDTH_DP = 500
        const val FIXED_GROUP_TEST_HEIGHT_DP = 44
    }

    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState(vararg tabs: LogTab): AppState {
        return newState(tabs.toList())
    }

    private fun newState(tabs: List<LogTab>): AppState {
        directory = createTempDirectory("tab-groups-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = tabs.toList()
        tabs.firstOrNull()?.let { state.activateTab(it.id) }
        return state
    }

    private fun tab(id: String, label: String = "$id.log"): LogTab = mkTab(id, label, emptyList())

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun setStrip(width: Int = 1000, height: Int = 44, vertical: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    TabStrip(
                        state,
                        if (vertical) ChromeOrientation.VERTICAL else ChromeOrientation.HORIZONTAL,
                        Modifier.fillMaxSize().testTag("group-test-strip"),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun createFromContextActionOpensSelectedNameAndPaletteThenMixedMembersCollapseTogether() {
        val a = tab("a")
        val b = tab("b")
        newState(a, b)
        state.updateSettings { it.copy(tabSizeMode = TabSizeMode.FIT) }
        var editingId by mutableStateOf<String?>(null)
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalOpenTabGroupEditor provides { editingId = it },
            ) {
                Column {
                    TabGroupContextActions(
                        state = state,
                        ref = TabRef.Log("a"),
                        onDismiss = {},
                        onEdit = { editingId = it },
                    )
                    TabStrip(state, ChromeOrientation.HORIZONTAL, Modifier.width(1000.dp).height(44.dp))
                    editingId?.let { id -> TabGroupEditorDialog(state, id) { editingId = null } }
                }
            }
        }

        rule.onNodeWithText("Add to new group").performClick()
        rule.waitForIdle()
        val groupId = requireNotNull(editingId)
        assertEquals("Group 1", state.tabGroups.single().name)
        assertEquals(listOf(TabRef.Log("a")), state.tabGroups.single().members)
        assertEquals(TabGroupTextSize.MEDIUM, state.tabGroups.single().textSize)
        assertEquals(TabGroupIcon.NONE, state.tabGroups.single().icon)
        rule.onNodeWithTag("tab-group-name").assertExists()
        val initialHeaderWidth = rule.onNodeWithTag("tab-group-header-$groupId").getBoundsInRoot().width
        rule.onNodeWithTag("tab-group-color-green").performClick()
        rule.onNodeWithTag("tab-group-text-size-large").performClick()
        rule.onNodeWithTag("tab-group-icon-bug").performClick()
        rule.onNodeWithTag("tab-group-save").performClick()
        rule.waitForIdle()
        assertEquals(TabGroupColor.GREEN, state.tabGroups.single().color)
        assertEquals(TabGroupTextSize.LARGE, state.tabGroups.single().textSize)
        assertEquals(TabGroupIcon.BUG, state.tabGroups.single().icon)
        assertEquals(10.sp, TabGroupTextSize.SMALL.fontSize())
        assertEquals(12.sp, TabGroupTextSize.MEDIUM.fontSize())
        assertEquals(14.sp, state.tabGroups.single().textSize.fontSize())
        assertTrue(rule.onNodeWithTag("tab-group-header-$groupId").getBoundsInRoot().width > initialHeaderWidth)
        assertTrue(exists("tab-group-header-icon-$groupId"), "the saved pictogram renders immediately in the group header")
        assertEquals(null, editingId)

        val diagramId = requireNotNull(state.seq3Sessions.beginEmpty("a"))
        state.assignTabToGroup(TabRef.Log("b"), groupId)
        state.assignTabToGroup(TabRef.Diagram(diagramId), groupId)
        state.activateTab("a")
        rule.waitForIdle()

        assertTrue(exists("tab-group-header-$groupId"))
        assertTrue(exists("tab-a"))
        assertTrue(exists("tab-b"))
        assertTrue(exists("tab-$diagramId"), "diagram workspace tabs can be group members")
        assertEquals(3, state.tabGroups.single().members.size)

        rule.onNodeWithTag("tab-group-header-$groupId").performClick()
        rule.waitForIdle()
        assertTrue(state.tabGroups.single().collapsed)
        assertEquals("a", state.activeTabId, "collapsing a group leaves its active content alone")
        assertFalse(exists("tab-a"))
        assertFalse(exists("tab-b"))
        assertFalse(exists("tab-$diagramId"))

        rule.onNodeWithTag("tab-group-header-$groupId").performClick()
        rule.waitForIdle()
        assertFalse(state.tabGroups.single().collapsed)
        assertTrue(exists("tab-a"))
        assertTrue(exists("tab-b"))
        assertTrue(exists("tab-$diagramId"))
    }

    @Test
    fun draggingTabOntoHeaderJoinsItAndDraggingHeaderMovesTheWholeBlock() {
        val a = tab("a")
        val b = tab("b")
        val c = tab("c")
        newState(a, b, c)
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        setStrip()

        dragNodeTo("tab-b", "tab-group-header-$groupId")
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("b"))?.id)
        assertEquals(listOf(TabRef.Log("a"), TabRef.Log("b")), state.tabGroups.single().members)

        dragNodeTo("tab-group-header-$groupId", "tab-c")
        assertEquals(
            listOf(TabRef.Log("a"), TabRef.Log("b")),
            state.reconciledTabOrder().takeLast(2),
            "dragging a group header moves the complete member block",
        )
        dragNodeToOutsideStart("tab-group-header-$groupId")
        assertEquals(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c")), state.reconciledTabOrder())
    }

    @Test
    fun collapsedVerticalOverflowSelectionRevealsAHiddenMemberWithoutUngroupingOrReordering() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        newState(tabs)
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        state.assignTabToGroup(TabRef.Log("b"), groupId)
        state.assignTabToGroup(TabRef.Log("c"), groupId)
        state.toggleTabGroupCollapsed(groupId)
        val before = state.reconciledTabOrder()
        setStrip(width = 200, height = 144, vertical = true)

        assertTrue(exists("tab-group-header-$groupId"))
        rule.onNodeWithText("▾ 3").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("b.log").performClick()
        rule.waitForIdle()

        assertFalse(state.tabGroups.single().collapsed)
        assertEquals("b", state.activeTabId)
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("b"))?.id)
        assertEquals(before, state.reconciledTabOrder(), "revealing a hidden member does not move it out of its group")
        rule.onNodeWithTag("tab-b").assertExists()
        rule.onNodeWithTag("tab-group-header-$groupId").assertExists()
    }

    @Test
    fun fitOverflowSelectionKeepsTheGroupHeaderBesideTheSelectedMember() {
        val tabs = listOf(
            tab("a", "very-long-first.log"),
            tab("b", "very-long-second.log"),
            tab("c", "very-long-third.log"),
        )
        newState(tabs)
        state.updateSettings { it.copy(tabSizeMode = TabSizeMode.FIT, visibleTabLimit = 2) }
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        state.assignTabToGroup(TabRef.Log("b"), groupId)
        state.assignTabToGroup(TabRef.Log("c"), groupId)
        state.toggleTabGroupCollapsed(groupId)
        val before = state.reconciledTabOrder()
        setStrip(width = 260, height = 44)

        rule.onNodeWithText("▾ 3").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("very-long-second.log").performClick()
        rule.waitForIdle()

        assertEquals(before, state.reconciledTabOrder())
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("b"))?.id)
        rule.onNodeWithTag("tab-b").assertExists()
        rule.onNodeWithTag("tab-group-header-$groupId").assertExists()
    }

    @Test
    fun shortVerticalStripKeepsTheGroupHeaderAndOverflowButtonOnSeparateRows() {
        newState(tab("a"), tab("b"), tab("c"))
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        state.assignTabToGroup(TabRef.Log("b"), groupId)
        state.assignTabToGroup(TabRef.Log("c"), groupId)
        state.toggleTabGroupCollapsed(groupId)
        setStrip(width = 200, height = 92, vertical = true)

        rule.onNodeWithText("▾ 3").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("b.log").performClick()
        rule.waitForIdle()

        assertFalse(state.tabGroups.single().collapsed)
        assertFalse(exists("tab-b"), "the selected member remains in overflow when the viewport cannot fit it with its header")
        val header = rule.onNodeWithTag("tab-group-header-$groupId").getBoundsInRoot()
        val overflow = rule.onNodeWithTag("tab-overflow-button").getBoundsInRoot()
        assertTrue(header.bottom <= overflow.top, "the full-height header leaves a row for the overflow button")
    }

    @Test
    fun changingMembershipMidDragResetsTheGestureAndTheNextTabClickStillActivates() {
        newState(tab("a"), tab("b"), tab("c"))
        setStrip()

        rule.onNodeWithTag("tab-a").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
        }
        rule.waitForIdle()
        assertTrue(exists("tab-strip-drag-active"))
        requireNotNull(state.createTabGroup(TabRef.Log("a")))
        rule.waitForIdle()
        assertTrue(exists("tab-strip-drag-idle"), "cancellation clears the drag gate before pointer release")

        rule.onNodeWithTag("tab-b").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("b", state.activeTabId)
        // Finish the still-pressed synthetic touch after checking the canceled gesture's click gate.
        rule.onNodeWithTag("group-test-strip").performTouchInput { up() }
    }

    @Test
    fun longCollapsedGroupOverflowScrollsToAndActivatesItsLastMember() {
        val tabs = (0 until 24).map { index -> tab("g$index") }
        newState(tabs)
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("g0")))
        tabs.drop(1).forEach { state.assignTabToGroup(TabRef.Log(it.id), groupId) }
        state.toggleTabGroupCollapsed(groupId)
        val before = state.reconciledTabOrder()
        setStrip(width = 260, height = 44)

        rule.onNodeWithText("▾ 24").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("g23.log").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("g23", state.activeTabId)
        assertFalse(state.tabGroups.single().collapsed)
        assertEquals(before, state.reconciledTabOrder())
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("g23"))?.id)
    }

    @Test
    fun viewportAroundSelectedGroupMemberKeepsPrecedingSiblingsWhenTheyFit() {
        val refs = listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c"))
        val group = TabGroup("g", "Group 1", TabGroupColor.BLUE, collapsed = false, members = refs)
        val items = projectTabStripItems(refs, listOf(group))
        val (visible, _) = splitTabStripItems(
            items = items,
            widthsPx = List(items.size) { 40f },
            containerPx = 400,
            overflowButtonPx = 40,
            visibleItemLimit = 8,
            minimumItemPx = 40,
            groups = listOf(group),
            anchorRef = refs.last(),
        )

        assertEquals(items, visible)
    }

    @Test
    fun fixedGroupHeadersUseMeasuredWidthAndShareTheRestAcrossTabs() {
        newState(tab("a"), tab("b"), tab("c"))
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        setStrip(width = FIXED_GROUP_TEST_WIDTH_DP, height = FIXED_GROUP_TEST_HEIGHT_DP)

        val header = rule.onNodeWithTag("tab-group-header-$groupId").getBoundsInRoot()
        val tabs = listOf("a", "b", "c").map { rule.onNodeWithTag("tab-$it").getBoundsInRoot() }
        assertTrue(header.width >= 64.dp && header.width <= 180.dp, "the measured header is clamped to a compact natural width")
        assertTrue(tabs.all { it.width == tabs.first().width }, "Fixed mode divides the remaining width equally among real tabs")
        assertTrue(tabs.first().width > header.width, "real tabs share the strip after the compact header takes its measured width")
        assertTrue(
            header.right <= tabs.first().left && tabs.first().left - header.right <= 4.dp,
            "the natural header ends at the first tab's allocated boundary",
        )

        state.toggleTabGroupCollapsed(groupId)
        rule.waitForIdle()
        val collapsedHeader = rule.onNodeWithTag("tab-group-header-$groupId").getBoundsInRoot()
        assertEquals(header.width, collapsedHeader.width, "collapse does not change the header's natural width")
    }

    @Test
    fun groupedFixedWidthsReservePositiveSpaceForTabsWhenHeadersConsumeTheViewport() {
        val group = TabGroup("g", "Group 1", TabGroupColor.BLUE, collapsed = false, members = listOf(TabRef.Log("a")))
        val items = listOf(TabStripItem.GroupHeader(group), TabStripItem.Tab(TabRef.Log("a")))

        val oversized = groupedFixedVisibleWidthsPx(
            items,
            mapOf(group.id to GROUP_HEADER_WIDTH_PX),
            NARROW_AVAILABLE_PX,
            MIN_TAB_WIDTH_PX,
        )
        assertTrue(oversized.all { it > 0f }, "the selected tab remains hittable when a natural header is too wide")
        assertTrue(oversized.sum() <= NARROW_AVAILABLE_PX)

        val exactFit = groupedFixedVisibleWidthsPx(
            items,
            mapOf(group.id to NARROW_AVAILABLE_PX),
            NARROW_AVAILABLE_PX,
            MIN_TAB_WIDTH_PX,
        )
        assertTrue(exactFit.all { it > 0f }, "equal header and viewport widths still reserve tab space")
        assertTrue(exactFit.sum() <= NARROW_AVAILABLE_PX)

        val roomy = groupedFixedVisibleWidthsPx(
            items,
            mapOf(group.id to GROUP_HEADER_WIDTH_PX),
            ROOMY_AVAILABLE_PX,
            MIN_TAB_WIDTH_PX,
        )
        assertEquals(GROUP_HEADER_WIDTH_PX, roomy.first())
        assertEquals(ROOMY_TAB_WIDTH_PX, roomy.last())
    }

    @Test
    fun groupHeaderPopupUsesClickedNonfirstHeaderWindowBoundsAndClampsAtEveryEdge() {
        newState(tab("a"), tab("b"), tab("c"), tab("d"), tab("e"))
        val firstGroup = requireNotNull(state.createTabGroup(TabRef.Log("b")))
        val secondGroup = requireNotNull(state.createTabGroup(TabRef.Log("d")))
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(
                    Modifier.width(POPUP_TEST_WIDTH_DP.dp).height(POPUP_TEST_HEIGHT_DP.dp)
                        .padding(start = POPUP_TEST_LEFT_PADDING_DP.dp, top = POPUP_TEST_TOP_PADDING_DP.dp),
                ) {
                    TabStrip(state, ChromeOrientation.HORIZONTAL, Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        val secondHeader = rule.onNodeWithTag("tab-group-header-$secondGroup").getBoundsInRoot()
        assertTrue(secondHeader.left > rule.onNodeWithTag("tab-group-header-$firstGroup").getBoundsInRoot().left)
        rule.onNodeWithTag("tab-group-header-$secondGroup").performMouseInput { rightClick() }
        rule.waitForIdle()
        val menu = rule.onNodeWithTag("tab-group-context-menu").getBoundsInRoot()
        assertEquals(secondHeader.left, menu.left, "the group menu follows the clicked nonfirst header")
        assertEquals(secondHeader.bottom, menu.top, "the top strip menu opens just below that header")
        rule.onNodeWithText("Edit group…").assertExists()

        val popup = IntSize(POPUP_WIDTH_PX, POPUP_HEIGHT_PX)
        val window = IntSize(POPUP_WINDOW_WIDTH_PX, POPUP_WINDOW_HEIGHT_PX)
        val header = IntRect(HEADER_LEFT_PX, HEADER_TOP_PX, HEADER_RIGHT_PX, HEADER_TOP_PX + HEADER_HEIGHT_PX)
        val expected = mapOf(
            com.indagium.model.TabStripPosition.TOP to (HEADER_LEFT_PX to (HEADER_TOP_PX + HEADER_HEIGHT_PX)),
            com.indagium.model.TabStripPosition.BOTTOM to (HEADER_LEFT_PX to (HEADER_TOP_PX - POPUP_HEIGHT_PX)),
            com.indagium.model.TabStripPosition.LEFT to (HEADER_RIGHT_PX to HEADER_TOP_PX),
            com.indagium.model.TabStripPosition.RIGHT to ((HEADER_LEFT_PX - POPUP_WIDTH_PX) to HEADER_TOP_PX),
        )
        expected.forEach { (position, origin) ->
            val actual = groupHeaderPopupOrigin(position, header, popup, window, HEADER_HEIGHT_PX)
            assertEquals(origin.first, actual.x, "$position popup horizontal edge")
            assertEquals(origin.second, actual.y, "$position popup vertical edge")
        }
        val clamped = groupHeaderPopupOrigin(
            com.indagium.model.TabStripPosition.TOP,
            IntRect(CLAMPED_HEADER_LEFT_PX, CLAMPED_HEADER_TOP_PX, CLAMPED_HEADER_RIGHT_PX, CLAMPED_HEADER_BOTTOM_PX),
            popup,
            window,
            HEADER_HEIGHT_PX,
        )
        assertEquals(POPUP_WINDOW_WIDTH_PX - POPUP_WIDTH_PX, clamped.x)
        assertEquals(POPUP_WINDOW_HEIGHT_PX - POPUP_HEIGHT_PX, clamped.y)
    }

    @Test
    fun overflowSeparatesCollapsedMembersFromOtherHiddenTabsWithoutDuplicates() {
        val tabs = (0 until 6).map { index -> tab("o$index") }
        newState(tabs)
        val groupId = requireNotNull(state.createTabGroup(TabRef.Log("o0")))
        state.assignTabToGroup(TabRef.Log("o1"), groupId)
        state.toggleTabGroupCollapsed(groupId)
        state.updateSettings { it.copy(visibleTabLimit = 2) }
        setStrip(width = 260, height = 44)

        rule.onNodeWithTag("tab-overflow-button").performClick()
        rule.waitForIdle()
        assertTrue(exists("tab-overflow-collapsed-groups"))
        assertTrue(exists("tab-overflow-more-tabs"))
        listOf("o0", "o1", "o2", "o3").forEach { id ->
            val matches = rule.onAllNodesWithTag("tab-overflow-ref-$id", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, matches.size, "$id appears in exactly one overflow section")
        }
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("o0"))?.id)
    }

    private fun dragNodeTo(fromTag: String, toTag: String) {
        rule.waitForIdle()
        val from = rule.onNodeWithTag(fromTag).getBoundsInRoot()
        val to = rule.onNodeWithTag(toTag).getBoundsInRoot()
        val localTarget = Offset(
            x = ((to.left + to.right) / 2f - from.left).value * rule.density.density,
            y = ((to.top + to.bottom) / 2f - from.top).value * rule.density.density,
        )
        rule.onNodeWithTag(fromTag).performTouchInput {
            down(center)
            moveTo(localTarget, delayMillis = 100)
            moveTo(localTarget, delayMillis = 100)
            up()
        }
        rule.waitForIdle()
    }

    private fun dragNodeToOutsideStart(fromTag: String) {
        rule.waitForIdle()
        val from = rule.onNodeWithTag(fromTag).getBoundsInRoot()
        val density = rule.density.density
        val targetX = (-from.left.value - 40f) * density
        rule.onNodeWithTag(fromTag).performTouchInput {
            down(center)
            moveTo(Offset(targetX, center.y), delayMillis = 100)
            moveTo(Offset(targetX, center.y), delayMillis = 100)
            up()
        }
        rule.waitForIdle()
    }
}
