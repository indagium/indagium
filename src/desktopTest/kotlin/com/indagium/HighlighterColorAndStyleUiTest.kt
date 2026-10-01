package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.Filter
import com.indagium.model.Highlighter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.AppearanceSystemFontSelectors
import com.indagium.ui.CtxTagActions
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FilterPanelUiState
import com.indagium.ui.FontCatalog
import com.indagium.ui.HL_COLORS
import com.indagium.ui.HighlighterActions
import com.indagium.ui.HighlighterColorPicker
import com.indagium.ui.HighlighterSection
import com.indagium.ui.HighlighterSectionState
import com.indagium.ui.LocalTheme
import com.indagium.ui.MAX_CUSTOM_HIGHLIGHT_COLORS
import com.indagium.ui.ThemeColors
import com.indagium.ui.WARM_PAPER
import com.indagium.ui.resolvedInterfaceFontFamily
import com.indagium.ui.resolvedLogFontFamily
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Compose interactions for the shared picker, rule add/edit controls, and font settings. */
@OptIn(ExperimentalTestApi::class)
class HighlighterColorAndStyleUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun recordScreenshot(name: String) {
        recordScreenshot("highlighter-section-qa", name)
    }

    private fun recordScreenshot(testTag: String, name: String) {
        val image = rule.onNodeWithTag(testTag).captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/highlighter-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    private fun assertRenderedThemeAccent(testTag: String, accent: Color) {
        val pixels = rule.onNodeWithTag(testTag).captureToImage().toPixelMap()
        val containsAccent = (0 until pixels.width).any { x ->
            (0 until pixels.height).any { y -> pixels[x, y].toArgb() == accent.toArgb() }
        }
        assertTrue(containsAccent, "the enabled style chip should use the selected theme accent")
    }

    private fun collapseAndReopenHighlightersSection() {
        rule.onNodeWithText("Highlighters").performClick()
        rule.onNodeWithTag("highlighter-add-pattern").assertDoesNotExist()
        rule.onNodeWithText("Highlighters").performClick()
    }

    private fun tapChipAt(testTag: String, xFraction: Float, yFraction: Float) {
        val size = rule.onNodeWithTag(testTag).fetchSemanticsNode().size
        val position = Offset(size.width * xFraction, size.height * yFraction)
        rule.onNodeWithTag(testTag).performTouchInput { click(position) }
    }

    private fun verifyBackgroundChipHitAreas(sectionState: HighlighterSectionState) {
        val chip = "highlighter-add-background-group"
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_TOP_PADDING)
        rule.runOnIdle { assertFalse(sectionState.addBackgroundEnabled, "top-right padding should toggle background off") }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_BOTTOM_PADDING)
        rule.runOnIdle { assertTrue(sectionState.addBackgroundEnabled, "bottom-right padding should toggle background on") }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_VERTICAL_CENTER)
        rule.runOnIdle { assertFalse(sectionState.addBackgroundEnabled, "right inset should be part of the chip action") }
        rule.onNodeWithTag("highlighter-add-background-enable-swatch").performClick()
        rule.runOnIdle {
            assertTrue(sectionState.addBackgroundEnabled, "swatch should enable background")
            assertTrue(sectionState.addColorPickerOpen, "swatch should open its color picker without toggling back off")
        }
        rule.onNodeWithTag("highlighter-add-background-picker").performKeyInput { pressKey(Key.Escape) }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_VERTICAL_CENTER)
        rule.runOnIdle { assertFalse(sectionState.addBackgroundEnabled) }
    }

    private fun verifyForegroundChipHitAreas(sectionState: HighlighterSectionState) {
        val chip = "highlighter-add-foreground-group"
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_TOP_PADDING)
        rule.runOnIdle { assertNotNull(sectionState.addTextColor, "top-right padding should turn text color on") }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_BOTTOM_PADDING)
        rule.runOnIdle { assertNull(sectionState.addTextColor, "bottom-right padding should turn text color off") }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_VERTICAL_CENTER)
        rule.runOnIdle { assertNotNull(sectionState.addTextColor, "right inset should be part of the chip action") }
        recordScreenshot("highlighter-add-foreground-group", "highlighter-foreground-control")
        assertRenderedThemeAccent("highlighter-add-foreground-group", WARM_PAPER.ac)
        rule.onNodeWithTag("highlighter-add-foreground-trigger", useUnmergedTree = true).performClick()
        rule.runOnIdle {
            assertNotNull(sectionState.addTextColor, "swatch should open its picker without disabling text color")
            assertTrue(sectionState.addForegroundPickerOpen)
        }
        rule.onNodeWithTag("highlighter-add-foreground-picker").performKeyInput { pressKey(Key.Escape) }
        tapChipAt(chip, CHIP_RIGHT_PADDING, CHIP_VERTICAL_CENTER)
        rule.runOnIdle { assertNull(sectionState.addTextColor, "chip padding should restore inherited log text color") }
        rule.onNodeWithTag("highlighter-add-foreground-enable-swatch").performClick()
        rule.runOnIdle { assertNotNull(sectionState.addTextColor, "foreground swatch should enable a text color") }
        rule.onNodeWithTag("highlighter-add-foreground-picker").assertExists()
        rule.runOnIdle { assertTrue(sectionState.addForegroundPickerOpen) }
        rule.onNodeWithTag("highlighter-add-foreground-picker").performKeyInput { pressKey(Key.Escape) }
    }

    private fun verifyRuleListCollapseIsIndependentFromSectionCollapse() {
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighters-list-toggle"))
        rule.onNodeWithTag("highlighters-list-toggle").performClick()
        rule.onNodeWithTag("highlighter-add-pattern").assertExists()
        rule.onNodeWithText("needle").assertDoesNotExist()
        rule.onNodeWithTag("highlighters-list-toggle").performClick()
        rule.onNodeWithText("needle").assertExists()
        rule.onNodeWithTag("highlighter-editor-pattern").assertDoesNotExist()
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasText("Highlighters"))
        collapseAndReopenHighlightersSection()
        rule.onNodeWithTag("highlighter-add-pattern").assertExists()
        rule.onNodeWithText("needle").assertExists()

        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasText("Highlighters"))
        rule.onNodeWithText("Highlighters").performClick()
        rule.onNodeWithTag("highlighter-add-pattern").assertDoesNotExist()
        rule.onNodeWithText("needle").assertDoesNotExist()
        rule.onNodeWithTag("highlighters-list-toggle").performClick()
        rule.onNodeWithTag("highlighter-add-pattern").assertExists()
        rule.onNodeWithText("needle").assertExists()
    }

    private fun reopenRuleEditorAndClearStyleOverrides() {
        rule.onNodeWithText("needle").performClick()
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-editor-pattern"))
        rule.onNodeWithTag("highlighter-editor-pattern").assertIsDisplayed()
        recordScreenshot("highlighter-editor-form")
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-editor-background-toggle"))
        rule.onNodeWithTag("highlighter-editor-background-toggle").performClick()
        rule.onNodeWithTag("highlighter-editor-foreground-toggle").performClick()
        repeat(2) { rule.onNodeWithTag("highlighter-editor-bold-cycle").performClick() }
        rule.onNodeWithTag("highlighter-editor-done").performClick()
    }

    @Test
    fun pickerPagesRectangleAndPersistsTransparentHexCustomColors() {
        val savedAtStart = Color(0x80445566)
        var selected by mutableStateOf(savedAtStart)
        var custom by mutableStateOf(listOf(savedAtStart))
        var columns by mutableIntStateOf(5)
        var open by mutableStateOf(true)
        var customEditorExpanded by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Box(Modifier.fillMaxSize()) {
                    HighlighterColorPicker(
                        color = selected,
                        onColorChange = { selected = it },
                        customColors = custom,
                        onSaveCustomColor = { custom = (custom + it).distinct() },
                        onDeleteCustomColor = { color -> custom = custom.filterNot { it == color } },
                        paletteColumns = columns,
                        onPaletteColumnsChange = { columns = it },
                        pickerOpen = open,
                        onPickerOpenChange = { open = it },
                        testTagPrefix = "functional-picker",
                        customColorEditorExpanded = customEditorExpanded,
                        onCustomColorEditorExpandedChange = { customEditorExpanded = it },
                    )
                }
            }
        }

        rule.onNodeWithTag("functional-picker-hex").assertExists()
        rule.onNodeWithTag("functional-picker-mode-pages").assertDoesNotExist()
        recordScreenshot("functional-picker-picker", "palette-custom-expanded-warm-paper")
        rule.onNodeWithText("Page 2 of 2").assertExists()
        rule.onNodeWithTag("functional-picker-prev-page").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.onNodeWithTag("functional-picker-swatch-25").performClick()
        rule.runOnIdle { assertEquals(HL_COLORS[25], selected) }
        rule.onNodeWithText("Page 1 of 2").assertExists()

        val oldColor = selected
        rule.onNodeWithTag("functional-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("functional-picker-trigger").performClick()
        rule.onNodeWithTag("functional-picker-hex").assertExists()
        rule.onNodeWithTag("functional-picker-hex").performTextClearance()
        rule.onNodeWithTag("functional-picker-hex").performTextInput("#BADG")
        rule.onNodeWithTag("functional-picker-hex-error").assertExists()
        rule.onNodeWithTag("functional-picker-save").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(oldColor, selected) }

        rule.onNodeWithTag("functional-picker-hex").performTextClearance()
        rule.onNodeWithTag("functional-picker-hex").performTextInput("#80445566")
        rule.onNodeWithTag("functional-picker-save").assertIsNotEnabled()
        rule.onNodeWithTag("functional-picker-hex").performTextClearance()
        rule.onNodeWithTag("functional-picker-hex").performTextInput("#80667788")
        rule.onNodeWithTag("functional-picker-save").performClick()
        rule.runOnIdle { assertEquals(listOf(savedAtStart, Color(0x80667788)), custom) }
        rule.onNodeWithTag("functional-picker-hex").performTextClearance()
        rule.onNodeWithTag("functional-picker-hex").performTextInput("#8044AABB")
        rule.onNodeWithTag("functional-picker-apply").performClick()
        rule.runOnIdle { assertEquals(Color(0x8044AABB), selected) }
        rule.onNodeWithTag("functional-picker-next-page").performClick()
        rule.onNodeWithTag("functional-picker-swatch-100").performMouseInput { rightClick() }
        rule.onNodeWithTag("functional-picker-delete-menu").assertExists()
        rule.onNodeWithTag("functional-picker-delete-menu").performClick()
        rule.runOnIdle {
            assertEquals(Color(0x8044AABB), selected, "deleting a saved color must not change the current selection")
            assertEquals(listOf(Color(0x80667788)), custom)
        }
        rule.onNodeWithTag("functional-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("functional-picker-picker").assertDoesNotExist()
    }

    @Test
    fun pickerUsesFixedPagesAndReopensOnTheSelectedColorWithoutJumpingDuringNavigation() {
        val custom = listOf(Color(0x80112233), Color(0x80445566))
        var selected by mutableStateOf(Color(0xFF010203))
        var open by mutableStateOf(true)
        rule.setContent {
            HighlighterColorPicker(
                color = selected,
                onColorChange = { selected = it },
                customColors = custom,
                onSaveCustomColor = {},
                onDeleteCustomColor = {},
                paletteColumns = 5,
                onPaletteColumnsChange = {},
                pickerOpen = open,
                onPickerOpenChange = { open = it },
                testTagPrefix = "fixed-picker",
                customColorEditorExpanded = false,
            )
        }

        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.runOnIdle { selected = custom.last() }
        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.onNodeWithTag("fixed-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("fixed-picker-trigger").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
        rule.onAllNodesWithTag("fixed-picker-empty-slot").assertCountEquals(98)
        rule.onNodeWithTag("fixed-picker-prev-page").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.onNodeWithTag("fixed-picker-swatch-25").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.onNodeWithTag("fixed-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("fixed-picker-trigger").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        rule.onNodeWithTag("fixed-picker-next-page").performClick()
        rule.onNodeWithTag("fixed-picker-swatch-101").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
        rule.onNodeWithTag("fixed-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("fixed-picker-trigger").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
    }

    @Test
    fun pickerShowsCustomColorLimitInsteadOfSilentlyIgnoringSave() {
        val colors = (0 until MAX_CUSTOM_HIGHLIGHT_COLORS).map { index ->
            Color((0x80 shl 24) or (index shl 16) or (index shl 8) or index)
        }
        rule.setContent {
            HighlighterColorPicker(
                color = colors.last(),
                onColorChange = {},
                customColors = colors,
                onSaveCustomColor = {},
                onDeleteCustomColor = {},
                paletteColumns = 10,
                onPaletteColumnsChange = {},
                pickerOpen = true,
                onPickerOpenChange = {},
                testTagPrefix = "cap-picker",
            )
        }
        rule.onNodeWithText("Page 4 of 4").assertExists()
        rule.onAllNodesWithTag("cap-picker-empty-slot").assertCountEquals(44)
        rule.onNodeWithTag("cap-picker-custom-color-limit").assertIsDisplayed()
        rule.onNodeWithTag("cap-picker-hex").performTextClearance()
        rule.onNodeWithTag("cap-picker-hex").performTextInput("#FE123456")
        rule.onNodeWithTag("cap-picker-save").assertIsNotEnabled()
    }

    @Test
    fun pickerEscapeDismissesPopupFromTheHexField() {
        var open by mutableStateOf(true)
        rule.setContent {
            HighlighterColorPicker(
                color = Color.Yellow,
                onColorChange = {},
                customColors = emptyList(),
                onSaveCustomColor = {},
                onDeleteCustomColor = {},
                paletteColumns = 5,
                onPaletteColumnsChange = {},
                pickerOpen = open,
                onPickerOpenChange = { open = it },
                testTagPrefix = "escape-picker",
            )
        }
        rule.onNodeWithTag("escape-picker-hex").assertExists()
        rule.onNodeWithTag("escape-picker-custom-toggle").performClick()
        rule.onNodeWithTag("escape-picker-hex").assertDoesNotExist()
        rule.onNodeWithTag("escape-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("escape-picker-picker").assertDoesNotExist()
        rule.onNodeWithTag("escape-picker-trigger").performClick()
        rule.onNodeWithTag("escape-picker-hex").assertDoesNotExist()
        rule.onNodeWithTag("escape-picker-swatch-0").performClick()
        rule.onNodeWithTag("escape-picker-custom-toggle").performClick()
        rule.onNodeWithTag("escape-picker-picker").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("escape-picker-picker").assertDoesNotExist()
    }

    @Test
    fun contextPaletteUsesTenColumnPagesAndRetainsPlainBackgroundAction() {
        var columns by mutableIntStateOf(5)
        var colorPicked: Color? = null
        var ordinaryHighlightClicks = 0
        var custom by mutableStateOf(listOf(Color(0x80112233), Color(0x80445566)))
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Box(Modifier.width(640.dp).background(WARM_PAPER.p)) {
                    CtxTagActions(
                        onInclude = {},
                        onExclude = {},
                        onHighlight = { ordinaryHighlightClicks++ },
                        onHighlightColor = { colorPicked = it },
                        highlightAutoColor = custom.last(),
                        preferPickerLeft = false,
                        customColors = custom,
                        paletteColumns = columns,
                        onPaletteColumnsChange = { columns = it },
                    )
                }
            }
        }

        rule.onNodeWithTag("context-highlight-trigger").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
        rule.onNodeWithTag("context-highlight-mode-pages").assertDoesNotExist()
        rule.onNodeWithTag("context-highlight-mode-rectangle").assertDoesNotExist()
        rule.onAllNodesWithTag("context-highlight-empty-slot").assertCountEquals(98)
        val lastPopupBounds = rule.onNodeWithTag("context-highlight-popup").fetchSemanticsNode().boundsInRoot
        val lastPagePrevBounds = rule.onNodeWithTag("context-highlight-prev-page").fetchSemanticsNode().boundsInRoot
        recordScreenshot("context-highlight-popup", "context-palette-page-2-warm-paper")
        rule.onNodeWithTag("context-highlight-prev-page").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        val fullPopupBounds = rule.onNodeWithTag("context-highlight-popup").fetchSemanticsNode().boundsInRoot
        val fullPagePrevBounds = rule.onNodeWithTag("context-highlight-prev-page").fetchSemanticsNode().boundsInRoot
        assertEquals(lastPopupBounds.size, fullPopupBounds.size)
        assertEquals(lastPopupBounds.top, fullPopupBounds.top)
        assertEquals(lastPopupBounds.left, fullPopupBounds.left)
        assertTrue(abs(lastPagePrevBounds.top - fullPagePrevBounds.top) <= 1f)
        assertTrue(abs(lastPagePrevBounds.left - fullPagePrevBounds.left) <= 1f)
        rule.onNodeWithTag("context-highlight-next-page").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
        recordScreenshot("context-highlight-popup", "context-palette-page-2-warm-paper-repeat")
        rule.onNodeWithTag("context-highlight-prev-page").performClick()
        rule.onNodeWithText("Page 1 of 2").assertExists()
        recordScreenshot("context-highlight-popup", "context-palette-ten-columns-warm-paper")
        rule.onNodeWithTag("context-highlight-swatch-99").performClick()
        rule.runOnIdle {
            assertEquals(HL_COLORS[99], colorPicked)
            assertEquals(5, columns, "the context flyout must not change the saved palette preference")
        }
        rule.onNodeWithText("Highlight").performClick()
        rule.runOnIdle {
            assertEquals(1, ordinaryHighlightClicks)
            assertEquals(HL_COLORS[99], colorPicked, "the ordinary action remains separate from color selection")
        }
    }

    @Test
    fun contextSecondaryClickDeletesOnlySavedColorsWithoutHighlighting() {
        val first = Color(0x80112233)
        val second = Color(0x80445566)
        var custom by mutableStateOf(listOf(first, second))
        var highlightCalls = 0
        var colorCalls = 0
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Box(Modifier.width(640.dp).background(WARM_PAPER.p)) {
                    CtxTagActions(
                        onInclude = {},
                        onExclude = {},
                        onHighlight = { highlightCalls++ },
                        onHighlightColor = { colorCalls++ },
                        highlightAutoColor = second,
                        preferPickerLeft = false,
                        customColors = custom,
                        onDeleteCustomColor = { deleted -> custom = custom.filterNot { it == deleted } },
                        paletteColumns = 10,
                        onPaletteColumnsChange = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("context-highlight-trigger").performClick()
        rule.onNodeWithText("Page 2 of 2").assertExists()
        rule.onNodeWithTag("context-highlight-swatch-101").performMouseInput { rightClick() }
        rule.onNodeWithTag("context-highlight-delete-menu").assertExists()
        rule.runOnIdle {
            assertEquals(0, highlightCalls)
            assertEquals(0, colorCalls)
            assertEquals(listOf(first, second), custom)
        }
        rule.onNodeWithTag("context-highlight-delete-menu").performClick()
        rule.runOnIdle {
            assertEquals(listOf(first), custom)
            assertEquals(0, highlightCalls)
            assertEquals(0, colorCalls)
        }
        // Presets are never deletable, and secondary-click never activates the normal highlight.
        rule.onNodeWithTag("context-highlight-trigger").performClick()
        rule.onNodeWithTag("context-highlight-swatch-0").performMouseInput { rightClick() }
        rule.onNodeWithTag("context-highlight-delete-menu").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, highlightCalls)
            assertEquals(0, colorCalls)
            assertEquals(listOf(first), custom)
        }
    }

    @Test
    fun addFormCreatesForegroundOnlyTypographyRuleAndEditorSavesChanges() {
        var newPattern by mutableStateOf("")
        var newColor by mutableStateOf(Color(0xFFfacc15))
        var filter by mutableStateOf(Filter())
        var added: Highlighter? = null
        val pickedFont = FontCatalog.families.firstOrNull()
        val actions = HighlighterActions(
            onAdd = { pattern, regex, color, whole, target, tag, background, foreground, family, bold, italic ->
                val created = Highlighter(
                    id = "created-ui-rule", pattern = pattern, regex = regex, color = color, on = true,
                    wholeLine = whole, target = target, tag = tag, backgroundEnabled = background,
                    textColor = foreground, fontFamily = family, bold = bold, italic = italic,
                )
                added = created
                filter = filter.copy(highlighters = filter.highlighters + created)
            },
            onRemove = { id -> filter = filter.copy(highlighters = filter.highlighters.filterNot { it.id == id }) },
            onToggle = {},
            onSetColor = { id, color -> filter = filter.copy(highlighters = filter.highlighters.map { if (it.id == id) it.copy(color = color) else it }) },
            onUpdate = { id, transform -> filter = filter.copy(highlighters = filter.highlighters.map { if (it.id == id) transform(it) else it }) },
            onSetNewPattern = { newPattern = it },
            onSetNewRegex = {},
            onSetNewColor = { newColor = it },
            onSetKwHighlightEnabled = {},
            onSetKwHighlightColor = {},
            onRequestMessageComposition = {},
            customColors = emptyList(),
            paletteColumns = 5,
            onSaveCustomColor = {},
            onDeleteCustomColor = {},
            onPaletteColumnsChange = {},
        )
        val tabBase = LogTab(
            id = "highlighter-style-ui",
            filename = "highlighter-style-ui.log",
            logData = emptyList(),
            rmap = emptyMap(),
            analysis = LogAnalysis(pending = false),
        )
        val filterPanelState = FilterPanelUiState()
        val sectionState = HighlighterSectionState()
        val focusRequester = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Column(
                    Modifier.width(220.dp).height(720.dp).background(WARM_PAPER.p)
                        .verticalScroll(rememberScrollState()).testTag("highlighter-section-qa"),
                ) {
                    HighlighterSection(
                        tab = tabBase.copy(filter = filter),
                        fpState = filterPanelState,
                        sectionState = sectionState,
                        actions = actions,
                        sortedTags = emptyList(),
                        tagUsage = emptyMap(),
                        mostUsedTagLimit = 5,
                        filterListRows = 5,
                        newHlPat = newPattern,
                        newHlRx = false,
                        newHlColor = newColor,
                        inputFocusRequester = focusRequester,
                        onInputFocusedChange = {},
                        onTabOut = {},
                        onReclaimFocus = {},
                        onUiStateChanged = {},
                    )
                }
            }
        }

        rule.onNodeWithTag("highlighter-add-pattern").assertExists()
        recordScreenshot("highlighter-add-form")
        verifyBackgroundChipHitAreas(sectionState)
        verifyForegroundChipHitAreas(sectionState)
        rule.onNodeWithTag("highlighter-add-bold-cycle").performClick()
        repeat(2) { rule.onNodeWithTag("highlighter-add-italic-cycle").performClick() }
        rule.onNodeWithText("Bold on · Italic off").assertExists()
        rule.onNodeWithTag("highlighter-add-pattern").performTextInput("needle")
        // Collapsing hides the form, releases panel focus, and retains the user's style draft.
        collapseAndReopenHighlightersSection()
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-add-background-toggle"))
        rule.onNodeWithTag("highlighter-add-background-toggle").assertExists()
        if (pickedFont != null) {
            rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-add-font-trigger"))
            rule.onNodeWithTag("highlighter-add-font-trigger").performClick()
            rule.onNodeWithTag("highlighter-add-font-search").performTextInput(pickedFont)
            rule.onNodeWithTag("highlighter-add-font-option-0").performClick()
        }
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-add-submit"))
        rule.onNodeWithTag("highlighter-add-submit").performClick()
        rule.runOnIdle {
            val created = assertNotNull(added)
            assertEquals("needle", created.pattern)
            assertFalse(created.backgroundEnabled)
            assertNotNull(created.textColor)
            assertEquals(true, created.bold)
            assertEquals(false, created.italic)
            if (pickedFont != null) assertEquals(pickedFont, created.fontFamily)
        }

        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasText("needle"))
        rule.onNodeWithText("needle").performClick()
        rule.onNodeWithTag("highlighter-section-qa").performScrollToNode(hasTestTag("highlighter-editor-pattern"))
        rule.onNodeWithTag("highlighter-editor-pattern").assertIsDisplayed()
        verifyRuleListCollapseIsIndependentFromSectionCollapse()
        reopenRuleEditorAndClearStyleOverrides()
        rule.runOnIdle {
            val edited = filter.highlighters.single()
            assertTrue(edited.backgroundEnabled)
            assertNull(edited.textColor)
            assertNull(edited.bold)
            assertEquals(false, edited.italic)
        }
    }

    @Test
    fun compactAddControlsFitNarrowWarmPaperAndDarkWindows() {
        captureCompactAddForm(320, WARM_PAPER, "compact-add-320-warm-paper")
        captureCompactAddForm(360, DARK_GITHUB, "compact-add-360-dark-github")
    }

    @Test
    fun colorChipTooltipsExplainTogglingAndChoosingColors() {
        captureCompactAddForm(320, WARM_PAPER, "color-chip-layout")
        rule.onNodeWithTag("highlighter-add-background-toggle").performMouseInput { moveTo(center) }
        rule.waitUntil(2_000) {
            rule.onAllNodesWithText("Background is on.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("click swatch to choose color", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("highlighter-add-foreground-toggle").performMouseInput { moveTo(center) }
        rule.waitUntil(2_000) {
            rule.onAllNodesWithText("Text color is inherited.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("swatch chooses text color", substring = true).assertIsDisplayed()
    }

    private fun captureCompactAddForm(widthDp: Int, theme: ThemeColors, screenshot: String) {
        val tab = LogTab(
            id = "compact-add-$widthDp",
            filename = "compact-add.log",
            logData = emptyList(),
            rmap = emptyMap(),
            analysis = LogAnalysis(pending = false),
        )
        val focusRequester = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides theme) {
                Column(
                    Modifier.width(widthDp.dp).height(440.dp).background(theme.p)
                        .verticalScroll(rememberScrollState()).testTag("highlighter-section-qa"),
                ) {
                    HighlighterSection(
                        tab = tab,
                        fpState = FilterPanelUiState(),
                        sectionState = HighlighterSectionState(),
                        actions = noOpHighlighterActions(),
                        sortedTags = emptyList(),
                        tagUsage = emptyMap(),
                        mostUsedTagLimit = 5,
                        filterListRows = 5,
                        newHlPat = "ANR",
                        newHlRx = false,
                        newHlColor = HL_COLORS.first(),
                        inputFocusRequester = focusRequester,
                        onInputFocusedChange = {},
                        onTabOut = {},
                        onReclaimFocus = {},
                        onUiStateChanged = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("highlighter-add-pattern").assertIsDisplayed()
        rule.onNodeWithTag("highlighter-add-background-group").assertExists()
        rule.onNodeWithTag("highlighter-add-foreground-group").assertExists()
        rule.onNodeWithTag("highlighter-add-bold-cycle").assertExists()
        rule.onNodeWithTag("highlighter-add-italic-cycle").assertExists()
        val sectionBounds = rule.onNodeWithTag("highlighter-section-qa").fetchSemanticsNode().boundsInRoot
        val fontBounds = rule.onNodeWithTag("highlighter-add-font-trigger").fetchSemanticsNode().boundsInRoot
        assertTrue(fontBounds.right <= sectionBounds.right + 1f, "font selector should stay within the $widthDp dp panel")
        recordScreenshot("highlighter-section-qa", screenshot)
    }

    private fun noOpHighlighterActions() = HighlighterActions(
        onAdd = { _, _, _, _, _, _, _, _, _, _, _ -> },
        onRemove = {},
        onToggle = {},
        onSetColor = { _, _ -> },
        onUpdate = { _, _ -> },
        onSetNewPattern = {},
        onSetNewRegex = {},
        onSetNewColor = {},
        onSetKwHighlightEnabled = {},
        onSetKwHighlightColor = {},
        onRequestMessageComposition = {},
        customColors = emptyList(),
        paletteColumns = 10,
        onSaveCustomColor = {},
        onDeleteCustomColor = {},
        onPaletteColumnsChange = {},
    )

    @Test
    fun appearanceFontSelectorsUpdateIndependentSettingsAndMissingNamesFallBack() {
        val families = FontCatalog.families
        assertTrue(families.isNotEmpty())
        val first = families.first()
        val second = families.last()
        val state = AppState()
        rule.setContent { AppearanceSystemFontSelectors(state) }

        rule.onNodeWithTag("settings-interface-font-trigger").performClick()
        rule.onNodeWithTag("settings-interface-font-option-default").assertIsDisplayed()
        rule.onNodeWithTag("settings-interface-font-scrollbar").assertExists()
        recordScreenshot("settings-interface-font-popup", "shared-font-dropdown")
        rule.onNodeWithTag("settings-interface-font-search").performTextInput(first)
        rule.onNodeWithTag("settings-interface-font-option-0").performClick()
        // Reopening starts with a blank search, so an earlier selection does not hide alternatives.
        rule.onNodeWithTag("settings-interface-font-trigger").performClick()
        rule.onNodeWithTag("settings-interface-font-option-0").assertIsDisplayed()
        rule.onNodeWithTag("settings-interface-font-search").performTextInput(second)
        rule.onNodeWithTag("settings-interface-font-option-0").performClick()
        rule.onNodeWithTag("settings-log-font-trigger").performClick()
        rule.onNodeWithTag("settings-log-font-search").performTextInput(second)
        rule.onNodeWithTag("settings-log-font-option-0").performClick()
        rule.runOnIdle {
            assertEquals(second, state.settings.interfaceFontFamily)
            assertEquals(second, state.settings.logFontFamily)
        }
        assertNull(FontCatalog.resolveOrNull("No Such System Font Family 8E79"))
        val configured = AppSettings(interfaceFontFamily = first, fontMono = false)
        assertEquals(FontCatalog.resolve(first, androidx.compose.ui.text.font.FontFamily.Default), configured.resolvedInterfaceFontFamily())
        assertEquals(androidx.compose.ui.text.font.FontFamily.Default, configured.resolvedLogFontFamily())
        assertEquals(
            androidx.compose.ui.text.font.FontFamily.Monospace,
            FontCatalog.resolve("No Such System Font Family 8E79", androidx.compose.ui.text.font.FontFamily.Monospace),
        )
        state.close()
    }

    @Test
    fun newPaletteAndFontPreferencesRoundTripInKeyedSettingsJson() {
        val configured = AppSettings(
            interfaceFontFamily = "A UI Family",
            logFontFamily = "A Log Family",
            highlighterCustomColors = listOf("#80112233", "#FFABCDEF"),
            highlighterPaletteColumns = 10,
            highlighterCustomColorEditorExpanded = false,
        )
        val restored = settingsFromJson(configured.settingsJson())!!
        assertEquals(configured.interfaceFontFamily, restored.interfaceFontFamily)
        assertEquals(configured.logFontFamily, restored.logFontFamily)
        assertEquals(configured.highlighterCustomColors, restored.highlighterCustomColors)
        assertEquals(10, restored.highlighterPaletteColumns)
        assertFalse(restored.highlighterCustomColorEditorExpanded)
        val defaults = settingsFromJson("{}")!!
        assertNull(defaults.interfaceFontFamily)
        assertTrue(defaults.highlighterCustomColors.isEmpty())
        assertEquals(5, defaults.highlighterPaletteColumns)
        assertTrue(defaults.highlighterCustomColorEditorExpanded)
    }

    private companion object {
        const val CHIP_RIGHT_PADDING = .97f
        const val CHIP_TOP_PADDING = .04f
        const val CHIP_BOTTOM_PADDING = .96f
        const val CHIP_VERTICAL_CENTER = .5f
    }
}
