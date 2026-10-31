package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A group chip is as wide as [tabGroupHeaderWidthPx] says (that is what the strip allots it), so everything the chip draws (the dot,
 * the optional icon, "▾/▸ ", the name, " · N", the gaps and the style's padding) must fit in that width: no ellipsis, the count visible.
 */
@OptIn(ExperimentalTestApi::class)
class TabGroupChipLabelUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private val color = 0xFFEC4899.toInt()

    private fun group(id: String, name: String, icon: TabGroupIcon, collapsed: Boolean = true, count: Int = 3) = TabGroup(
        id = id,
        name = name,
        colorArgb = color,
        collapsed = collapsed,
        members = (1..count).map { TabRef.Log("m$it") },
        icon = icon,
    )

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    @Composable
    private fun Chip(group: TabGroup, style: TabStripStyle, position: TabStripPosition, vertical: Boolean) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val width = with(density) {
            tabGroupHeaderWidthPx(group, measurer, LocalUiFontFamily.current, density, style, position).toDp()
        }
        // A vertical strip gives the chip the strip's width, so the helper's width stands in for the bar here.
        Row(Modifier.background(tabStripBackground(style, LIGHT_THEME)).width(width)) {
            TabGroupHeader(
                group = group,
                vertical = vertical,
                width = width,
                height = tabStripHeightDp(style).dp,
                appearance = TabAppearance(style = style, position = position),
                onClick = {},
                onContextMenu = {},
            )
        }
    }

    private fun assertEveryStyleShowsTheWholeChip(
        names: List<String>,
        positions: List<TabStripPosition>,
        vertical: Boolean,
        density: Float,
    ) {
        positions.forEach { position ->
            TabStripStyle.entries.forEach { style ->
                val groups = names.flatMap { name ->
                    listOf(TabGroupIcon.NONE, TabGroupIcon.BOOKMARK).map { icon ->
                        group("${style.name}-${name.length}-${icon.name}", name, icon)
                    }
                }
                rule.setContent {
                    CompositionLocalProvider(LocalTheme provides LIGHT_THEME, LocalDensity provides Density(density)) {
                        Column(Modifier.width(600.dp)) { groups.forEach { Chip(it, style, position, vertical) } }
                    }
                }
                rule.waitForIdle()
                groups.forEach { group -> assertWholeLabel(group, "$style on $position (vertical=$vertical, density=$density) '${group.name}' ${group.icon}") }
            }
        }
    }

    /** The drawn label is at least as wide as the same text measured unconstrained in the same style: nothing is cut off. */
    private fun assertWholeLabel(group: TabGroup, what: String) {
        val label = tabGroupChipLabel(group)
        val node = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("tab-group-header-${group.id}")), useUnmergedTree = true)
        val drawn = layoutOf(node)
        val input = drawn.layoutInput
        val natural = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
            .measure(input.text, input.style).size.width
        assertTrue(drawn.size.width >= natural, "$what: the label is cut off (${drawn.size.width}px drawn, $natural px needed)")
        assertTrue(input.text.text.endsWith(" · ${group.members.size}"), "$what: the count is part of the label")
        assertEquals(1, drawn.lineCount, "$what: the label stays on one line")
    }

    @Test
    fun aCollapsedGroupWithAnIconShowsItsWholeLabelAndCountInEveryStyleOnTheHorizontalEdges() {
        assertEveryStyleShowsTheWholeChip(
            names = listOf("tickets order 1", "Checkout flow crash"),
            positions = listOf(TabStripPosition.TOP, TabStripPosition.BOTTOM),
            vertical = false,
            density = 1f,
        )
    }

    @Test
    fun theChipStillFitsAtAHiDpiDensity() {
        assertEveryStyleShowsTheWholeChip(
            names = listOf("tickets order 1"),
            positions = listOf(TabStripPosition.TOP),
            vertical = false,
            density = 1.5f,
        )
    }

    @Test
    fun aVerticalStripsChipIsAsWideAsTheHelperSaysAndShowsItsWholeLabel() {
        assertEveryStyleShowsTheWholeChip(
            names = listOf("tickets order 1"),
            positions = listOf(TabStripPosition.LEFT, TabStripPosition.RIGHT),
            vertical = true,
            density = 1f,
        )
    }

    @Test
    fun theHelperGrowsWithTheIconAndTheCountDigits() {
        val density = Density(1f)
        var plain = 0f
        var withIcon = 0f
        var bigCount = 0f
        rule.setContent {
            val measurer = rememberTextMeasurer()
            val font = LocalUiFontFamily.current
            val base = group("g", "tickets", TabGroupIcon.NONE)
            plain = tabGroupHeaderWidthPx(base, measurer, font, density, TabStripStyle.CURRENT)
            withIcon = tabGroupHeaderWidthPx(base.copy(icon = TabGroupIcon.BOOKMARK), measurer, font, density, TabStripStyle.CURRENT)
            bigCount = tabGroupHeaderWidthPx(group("g", "tickets", TabGroupIcon.NONE, count = 120), measurer, font, density, TabStripStyle.CURRENT)
        }
        rule.waitForIdle()
        assertTrue(withIcon > plain, "an icon widens the chip")
        assertTrue(bigCount > plain, "more digits widen the chip")
    }

    private fun stripWithTheUsersGroup(sizeMode: TabSizeMode, style: TabStripStyle, vertical: Boolean) {
        directory = createTempDirectory("tab-group-chip-label").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = listOf(mkTab("a", "a.log", emptyList()), mkTab("b", "b.log", emptyList()), mkTab("c", "c.log", emptyList()))
        state.activateTab("a")
        state.updateSettings { it.copy(tabSizeMode = sizeMode, tabStripStyle = style) }
        val id = requireNotNull(state.createTabGroup(TabRef.Log("a")))
        state.renameTabGroup(id, "tickets order 1")
        state.setTabGroupIcon(id, TabGroupIcon.BOOKMARK)
        state.toggleTabGroupCollapsed(id)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.width(if (vertical) 300.dp else 1000.dp).height(if (vertical) 400.dp else 44.dp)) {
                    TabStrip(state, if (vertical) ChromeOrientation.VERTICAL else ChromeOrientation.HORIZONTAL, Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theRealStripShowsTheWholeChipLabelInFixedAndFitSizingHorizontalAndVertical() {
        listOf(TabSizeMode.FIXED, TabSizeMode.FIT).forEach { mode ->
            listOf(false, true).forEach { vertical ->
                TabStripStyle.entries.forEach { style ->
                    stripWithTheUsersGroup(mode, style, vertical)
                    assertWholeLabel(state.tabGroups.single(), "$mode $style (vertical=$vertical)")
                    state.close()
                    directory.deleteRecursively()
                }
            }
        }
    }
}
