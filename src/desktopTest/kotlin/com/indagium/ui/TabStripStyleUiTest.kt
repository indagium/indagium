package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import com.indagium.model.LogTab
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The real [TabBar] in every strip style: tabs, close buttons, tailing dot, strip height, FIT widths and drag reorder, and a pixel
 * check that the default shell draws what it always did.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripStyleUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState(): AppState {
        directory = createTempDirectory("tab-strip-style-ui").toFile()
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
    }

    private fun tab(id: String, name: String, path: String? = "/logs/$id/$name"): LogTab = mkTab(id, name, emptyList()).copy(sourcePath = path)

    private val defaultTabs = listOf(
        tab("short", "a.log"),
        tab("long", "a-much-longer-file-name-for-fit.log"),
        tab("mid", "middle.log").copy(tailing = true),
    )

    private fun setup(width: Int = 1200, tabs: List<LogTab> = defaultTabs, configure: (AppState) -> Unit = {}) {
        state = newState()
        state.tabs = tabs
        state.activeTabId = tabs.first().id
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.width(width.dp)) { TabBar(state) }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun style(style: TabStripStyle) {
        state.updateSettings { it.copy(tabStripStyle = style) }
        rule.waitForIdle()
    }

    @Test
    fun everyStyleRendersTheTabsTheirCloseButtonsAndTheTailingDot() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            defaultTabs.forEach { t ->
                assertTrue(exists("tab-${t.id}"), "$s: tab ${t.id}")
                assertTrue(exists("tab-close-${t.id}"), "$s: close button of ${t.id}")
            }
            assertTrue(exists("tab-tailing-mid"), "$s: tailing dot")
            assertFalse(exists("tab-tailing-short"), "$s: only the tailing tab has the dot")
        }
    }

    @Test
    fun theStripHeightFollowsTheStyle() {
        setup()
        assertEquals(36.dp, bounds("tab-strip").height)
        style(TabStripStyle.COMPACT)
        assertEquals(28.dp, bounds("tab-strip").height)
        style(TabStripStyle.TWO_LINE)
        assertEquals(44.dp, bounds("tab-strip").height)
        style(TabStripStyle.RAISED)
        assertEquals(36.dp, bounds("tab-strip").height)
    }

    @Test
    fun clickingATabInAnyStyleActivatesItAndTheCloseButtonClosesIt() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            rule.onNodeWithTag("tab-long").performClick()
            rule.waitForIdle()
            assertEquals("long", state.activeTabId, "$s: click activates")
            state.activateTab("short")
            rule.waitForIdle()
        }
        style(TabStripStyle.SEGMENTED)
        rule.onNodeWithTag("tab-close-long").performClick()
        rule.waitForIdle()
        assertEquals(listOf("short", "mid"), state.tabs.map { it.id })
    }

    @Test
    fun fixedTabsShareTheStripEquallyAndFitTabsFollowTheirLabel() {
        setup()
        val fixed = defaultTabs.map { bounds("tab-${it.id}").width }
        assertEquals(fixed[0], fixed[1])
        assertEquals(fixed[1], fixed[2])
        state.updateSettings { it.copy(tabSizeMode = TabSizeMode.FIT) }
        rule.waitForIdle()
        val fit = defaultTabs.associate { it.id to bounds("tab-${it.id}").width }
        assertTrue(fit.getValue("long") > fit.getValue("mid"), "the long name is wider than the mid one: $fit")
        assertTrue(fit.getValue("mid") > fit.getValue("short"), "the mid name (with the dot) is wider than the short one: $fit")
        // Laid out one after another from the strip's start.
        assertEquals(bounds("tab-short").right, bounds("tab-long").left)
        assertEquals(bounds("tab-long").right, bounds("tab-mid").left)
        assertEquals(0.dp, bounds("tab-short").left)
    }

    // Press, drag by [dx] px, let the strip recompose (a real drag has frames between moves), then release.
    private fun dragShort(dx: Float) {
        rule.onNodeWithTag("tab-short").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            moveBy(Offset(dx - 20f, 0f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab-short").performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun aFitDragReorderCommitsTheRightOrder() {
        setup(configure = { it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) } })
        val d = rule.density.density
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // Dragging the narrow first tab only a little leaves the order alone.
        dragShort(40f * d)
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // Far enough to pass the wide tab's centre but not the third's: it lands second.
        val widthShort = bounds("tab-short").width.value
        val widthLong = bounds("tab-long").width.value
        val pastLongCentre = (widthShort / 2f + widthShort * 0.25f) // start centre + bias
        val dx = (widthShort + widthLong / 2f - pastLongCentre + 10f) * d
        dragShort(dx)
        assertEquals(listOf("long", "short", "mid"), state.tabs.map { it.id })
        // The dragged tab now sits second, its neighbours in the new order.
        assertTrue(bounds("tab-long").right <= bounds("tab-short").left + 1.dp)
        assertTrue(bounds("tab-short").right <= bounds("tab-mid").left + 1.dp)
    }

    @Test
    fun theDetailCheckboxesAddABadgeAndAStripeToAnyStyleAndTheNamedStylesImplyThem() {
        setup()
        assertFalse(exists("tab-badge-short"))
        assertFalse(exists("tab-stripe-short"))
        state.updateSettings { it.copy(tabShowTypeBadge = true, tabShowSourceStripe = true) }
        rule.waitForIdle()
        defaultTabs.forEach {
            assertTrue(exists("tab-badge-${it.id}"))
            assertTrue(exists("tab-stripe-${it.id}"))
        }
        state.updateSettings { it.copy(tabShowTypeBadge = false, tabShowSourceStripe = false, tabStripStyle = TabStripStyle.TYPED) }
        rule.waitForIdle()
        assertTrue(exists("tab-badge-short"))
        assertFalse(exists("tab-stripe-short"))
        style(TabStripStyle.SOURCE_STRIPE)
        assertFalse(exists("tab-badge-short"))
        assertTrue(exists("tab-stripe-short"))
    }

    @Test
    fun closeOnEveryTabOffKeepsTheButtonOnTheActiveTabOnly() {
        setup()
        state.updateSettings { it.copy(tabCloseOnEvery = false) }
        rule.waitForIdle()
        assertTrue(exists("tab-close-short"), "the active tab keeps its close button")
        assertFalse(exists("tab-close-long"))
        assertFalse(exists("tab-close-mid"))
    }

    private fun hasLabel(text: String): Boolean =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun labelModesChangeTheTabText() {
        setup(tabs = listOf(tab("one", "same.log", "/logs/first/same.log"), tab("two", "same.log", "/logs/second/same.log")))
        assertTrue(hasLabel("same.log — first") && hasLabel("same.log — second"))
        state.updateSettings { it.copy(tabLabelMode = TabLabelMode.SESSION_ONLY) }
        rule.waitForIdle()
        assertTrue(hasLabel("first") && hasLabel("second"))
        assertFalse(hasLabel("same.log — first"))
        state.updateSettings { it.copy(tabLabelMode = TabLabelMode.FILE_ONLY) }
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("same.log", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDefaultStyleKeepsTodaysGeometry() {
        setup()
        val area = defaultTabs.map { bounds("tab-${it.id}") }
        // Three equal tabs filling the area left of the toolbar, each as tall as the strip's content (36 - 2 dp padding).
        assertEquals(area[0].width, area[1].width)
        assertEquals(area[1].width, area[2].width)
        assertEquals(34.dp, area[0].height)
        assertEquals(area[0].right, area[1].left)
        assertEquals(area[1].right, area[2].left)
        assertEquals(36.dp, bounds("tab-strip").height)
    }

    // The shell as it was before styles existed, copied verbatim (minus test tags), to compare pixels with.
    @Composable
    private fun LegacyTabShell(label: String, isActive: Boolean, leading: (@Composable () -> Unit)?) {
        val tc = tc()
        val accent = tc.ac
        Row(
            Modifier.fillMaxWidth().height(36.dp)
                .background(if (isActive) tc.bg else tc.p2)
                .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
                .drawBehind {
                    if (isActive) {
                        val stroke = 2.dp.toPx()
                        drawRect(
                            color = accent,
                            topLeft = Offset(0f, size.height - stroke),
                            size = androidx.compose.ui.geometry.Size(size.width, stroke),
                        )
                    }
                }
                .clickable {}.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f)) {
                AppText(label, color = if (isActive) tc.tx else tc.ts, fontSize = 12.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis, maxLines = 1)
            }
            CloseButton(onClick = {})
        }
    }

    @Test
    fun theDefaultShellPaintsTheSamePixelsAsTheShellBeforeStyles() {
        directory = createTempDirectory("tab-strip-style-pixels").toFile()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(300.dp).padding(8.dp)) {
                    listOf(true, false).forEach { active ->
                        Box(Modifier.width(240.dp).testTag("new-$active")) {
                            TabShell(
                                pointerKey = "k$active", label = "pixel-check.log", tooltip = "t", isActive = active,
                                showClose = true, onClick = {}, onClose = {},
                            )
                        }
                        Box(Modifier.width(240.dp).testTag("old-$active")) { LegacyTabShell("pixel-check.log", active, null) }
                    }
                }
            }
        }
        rule.waitForIdle()
        listOf(true, false).forEach { active ->
            val new = rule.onNodeWithTag("new-$active").captureToImage().toPixelMap()
            val old = rule.onNodeWithTag("old-$active").captureToImage().toPixelMap()
            assertEquals(old.width, new.width)
            assertEquals(old.height, new.height)
            var differing = 0
            for (x in 0 until old.width) for (y in 0 until old.height) if (old[x, y] != new[x, y]) differing++
            assertEquals(0, differing, "active=$active: the default shell differs from the old one in $differing pixels")
        }
    }

    @Test
    fun aNonDefaultStyleDoesPaintDifferently() {
        directory = createTempDirectory("tab-strip-style-pixels2").toFile()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(300.dp)) {
                    Box(Modifier.width(240.dp).testTag("a")) {
                        TabShell(pointerKey = "a", label = "x.log", tooltip = "t", isActive = true, showClose = true, onClick = {}, onClose = {})
                    }
                    Box(Modifier.width(240.dp).testTag("b")) {
                        TabShell(
                            pointerKey = "b", label = "x.log", tooltip = "t", isActive = true, showClose = true, onClick = {}, onClose = {},
                            appearance = TabAppearance(style = TabStripStyle.RAISED),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val a = rule.onNodeWithTag("a").captureToImage().toPixelMap()
        val b = rule.onNodeWithTag("b").captureToImage().toPixelMap()
        var differing = 0
        for (x in 0 until minOf(a.width, b.width)) for (y in 0 until minOf(a.height, b.height)) if (a[x, y] != b[x, y]) differing++
        assertNotEquals(0, differing)
    }

    @Test
    fun theSettingsSubsectionPicksTheStyleAndLocksTheDetailTheStyleImplies() {
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(700.dp)) { TabsAppearanceSettings(state) }
            }
        }
        rule.waitForIdle()
        TabStripStyle.entries.forEach { assertTrue(exists("tab-style-${it.name}"), "thumbnail of $it") }
        rule.onNodeWithTag("tab-style-RAISED").performClick()
        rule.waitForIdle()
        assertEquals(TabStripStyle.RAISED, state.settings.tabStripStyle)
        rule.onNodeWithTag("tab-detail-file-type-badge", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.tabShowTypeBadge)
        rule.onNodeWithTag("tab-style-TYPED").performClick()
        rule.waitForIdle()
        // TYPED always shows the badge: the checkbox is locked, so a click leaves the stored value alone.
        rule.onNodeWithTag("tab-detail-file-type-badge", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.tabShowTypeBadge)
        rule.onNodeWithText("Fit label").performClick()
        rule.waitForIdle()
        assertEquals(TabSizeMode.FIT, state.settings.tabSizeMode)
        rule.onNodeWithText("Session only").performClick()
        rule.waitForIdle()
        assertEquals(TabLabelMode.SESSION_ONLY, state.settings.tabLabelMode)
        rule.onNodeWithTag("tab-detail-close-button-on-every-tab", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(state.settings.tabCloseOnEvery)
    }
}
