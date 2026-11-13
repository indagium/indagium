package com.indagium.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class PopupTile { VIDEO, NOTES, OTHER }

private const val TILE_VIEWPORT_HEIGHT_DP = 600
private const val VIDEO_HEIGHT_DP = 420f
private const val NOTES_HEIGHT_DP = 240f
private const val OTHER_HEIGHT_DP = 240f
private const val INNER_SCROLL_START_DP = 70
private const val POSITION_TOLERANCE_DP = 0.6f

private data class NotesViewportSnapshot(val videoTop: Float, val notes: DpRect, val innerScroll: Int)

/** Exercises the Notes menus inside an actual scrolling tile column, including the narrow body toolbar variant. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTestApi::class)
class NotesHeaderPopupScrollUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var innerNotesScroll: ScrollState? = null
    private var openedNotes = mutableListOf<File>()

    @Test
    fun headerMenusKeepPartialNotesViewportAndNestedScrollStable() = runMenuScenario(widthDp = 1_000)

    @Test
    fun narrowBodyMenusKeepPartialNotesViewportAndNestedScrollStable() = runMenuScenario(widthDp = 500)

    private fun runMenuScenario(widthDp: Int) {
        val noteFile = File.createTempFile("notes-menu", ".md").apply {
            writeText("recent note")
            deleteOnExit()
        }
        openedNotes = mutableListOf()
        val tree = popupTileTree()
        val headerActions = notesHeaderLayout(widthDp.toFloat(), hasLocate = true, hasRecent = true, headerIcons = 2).actionsInHeader
        rule.setContent {
            var recentMenuOpen by remember { mutableStateOf(false) }
            val notesFocus = remember { FocusRequester() }
            val feedback = remember { CopyFeedbackState { _, complete -> complete(true) } }
            val actions = NotesActionModel(
                tab = mkTab("notes-popup", "notes.log", emptyList()),
                defaultFormat = AnnotationCopyFormat.MARKDOWN,
                feedback = feedback,
                locked = false,
                recentNotes = listOf(noteFile.absolutePath),
                recentMenuOpen = recentMenuOpen,
                activeNotePath = null,
                onToggleMd = {},
                onSave = {},
                onNewAnalysis = {},
                onToggleRecentNotes = { recentMenuOpen = !recentMenuOpen },
                onOpenNote = { openedNotes += it },
                onLocateLog = {},
                onReclaimFocus = { notesFocus.requestFocus() },
            )
            val spec = popupTileSpec
            Box(Modifier.size(widthDp.dp, TILE_VIEWPORT_HEIGHT_DP.dp)) {
                TilingSurface(
                    fullLayout = tree,
                    isVisible = { true },
                    collapsed = emptySet(),
                    parked = emptySet(),
                    maximized = null,
                    locked = false,
                    widthDp = widthDp.toFloat(),
                    heightDp = TILE_VIEWPORT_HEIGHT_DP.toFloat(),
                    spec = spec,
                    callbacks = popupCallbacks,
                ) {
                    Tile(
                        PopupTile.VIDEO,
                        TileSlot(Icons.Outlined.Description, "Video", body = {
                            Box(Modifier.fillMaxSize().testTag("popup-video-body"))
                        }),
                    )
                    Tile(
                        PopupTile.NOTES,
                        TileSlot(
                            icon = Icons.Outlined.Description,
                            title = "Notes",
                            headerAction = {
                                if (headerActions) NotesActionButtons(actions)
                            },
                            headerParts = ::notesHeaderParts,
                            actionsCentered = true,
                            maximizable = true,
                            preserveBodyRootFocus = true,
                            body = { info ->
                                val inner = rememberScrollState()
                                innerNotesScroll = inner
                                Column(Modifier.fillMaxSize().focusRequester(notesFocus).focusable()) {
                                    val bodyLayout = notesHeaderLayout(info.widthDp, true, true, 2)
                                    if (!headerActions) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) { NotesActionButtons(actions, bodyLayout.look) }
                                    }
                                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(inner)) {
                                        repeat(40) { Text("notes row $it", Modifier.height(24.dp)) }
                                    }
                                }
                            },
                        ),
                    )
                    Tile(
                        PopupTile.OTHER,
                        TileSlot(Icons.Outlined.Description, "Other", body = {
                            Box(Modifier.fillMaxSize().testTag("popup-other-body"))
                        }),
                    )
                }
            }
        }
        rule.waitForIdle()
        val inner = checkNotNull(innerNotesScroll)
        rule.runOnIdle { runBlocking { inner.scrollTo(INNER_SCROLL_START_DP) } }
        rule.onNodeWithTag("popup-video-body").performMouseInput { scroll(4f) }
        rule.waitForIdle()

        val videoTag = "${popupTileSpec.tagPrefix}-card-video"
        val notesTag = "${popupTileSpec.tagPrefix}-card-notes"
        val initialVideoTop = bounds(videoTag).top.value
        val initialNotes = bounds(notesTag)
        val copyTop = bounds(notesButtonTag(NotesAction.COPY)).top.value
        assertTrue(initialVideoTop < -1f, "the tiled column has a nonzero outer scroll offset: $initialVideoTop")
        assertTrue(initialNotes.top.value < TILE_VIEWPORT_HEIGHT_DP && initialNotes.bottom.value > TILE_VIEWPORT_HEIGHT_DP,
            "the Notes tile is partly visible below Video: $initialNotes")
        val headerBottom = initialNotes.top.value + popupTileSpec.headerDp
        if (headerActions) assertTrue(copyTop < headerBottom, "Copy is in the Notes header: $copyTop")
        else assertTrue(copyTop >= headerBottom, "Copy is in the narrow Notes body toolbar: $copyTop")
        assertTrue(inner.value > 0, "the Notes content has a nonzero inner scroll offset")
        val snapshot = NotesViewportSnapshot(initialVideoTop, initialNotes, inner.value)
        runNotesMenus(noteFile, videoTag, notesTag, inner, snapshot)
    }

    private fun runNotesMenus(noteFile: File, videoTag: String, notesTag: String, inner: ScrollState, snapshot: NotesViewportSnapshot) {
        rule.onNodeWithTag(NOTES_COPY_FORMATS_TAG).performClick()
        rule.waitForIdle()
        val copyPopup = rule.onNodeWithTag(NOTES_COPY_POPUP_TAG)
        copyPopup.assertIsDisplayed()
        assertViewportStable("copy menu open", videoTag, notesTag, inner, snapshot)
        copyPopup.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        rule.waitForIdle()
        assertViewportStable("copy menu dismiss", videoTag, notesTag, inner, snapshot)

        rule.onNodeWithTag(NOTES_COPY_FORMATS_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(NOTES_COPY_POPUP_TAG).assertIsDisplayed()
        rule.onNodeWithText("HTML").performClick()
        rule.waitForIdle()
        assertViewportStable("copy format selected", videoTag, notesTag, inner, snapshot)

        rule.onNodeWithTag(NOTES_RECENT_NOTES_TAG).performClick()
        rule.waitForIdle()
        val recentPopup = rule.onNodeWithTag(NOTES_RECENT_POPUP_TAG)
        recentPopup.assertIsDisplayed()
        assertViewportStable("recent menu open", videoTag, notesTag, inner, snapshot)
        recentPopup.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        assertViewportStable("recent menu navigation", videoTag, notesTag, inner, snapshot)
        recentPopup.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        rule.waitForIdle()
        assertViewportStable("recent menu dismiss", videoTag, notesTag, inner, snapshot)

        rule.onNodeWithTag(NOTES_RECENT_NOTES_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(NOTES_RECENT_POPUP_TAG).assertIsDisplayed()
        rule.onNodeWithText(noteFile.name).performClick()
        rule.waitForIdle()
        assertEquals(listOf(noteFile), openedNotes)
        assertViewportStable("recent note selected", videoTag, notesTag, inner, snapshot)
    }

    private fun assertViewportStable(stage: String, videoTag: String, notesTag: String, inner: ScrollState, snapshot: NotesViewportSnapshot) {
        assertEquals(snapshot.videoTop, bounds(videoTag).top.value, POSITION_TOLERANCE_DP, "$stage: tile column offset")
        assertEquals(snapshot.notes.top.value, bounds(notesTag).top.value, POSITION_TOLERANCE_DP, "$stage: Notes position")
        assertEquals(snapshot.notes.bottom.value, bounds(notesTag).bottom.value, POSITION_TOLERANCE_DP, "$stage: Notes bottom")
        assertEquals(snapshot.innerScroll, inner.value, "$stage: Notes inner scroll")
    }

    @Test
    fun aGenuineOffscreenFieldStillScrollsOnlyFarEnoughToRevealIt() {
        val fieldRequester = FocusRequester()
        rule.setContent {
            Box(Modifier.size(800.dp, TILE_VIEWPORT_HEIGHT_DP.dp)) {
                TilingSurface(
                    fullLayout = popupTileTree(),
                    isVisible = { true },
                    collapsed = emptySet(),
                    parked = emptySet(),
                    maximized = null,
                    locked = false,
                    widthDp = 800f,
                    heightDp = TILE_VIEWPORT_HEIGHT_DP.toFloat(),
                    spec = popupTileSpec,
                    callbacks = popupCallbacks,
                ) {
                    Tile(PopupTile.VIDEO, simpleSlot("Video"))
                    Tile(PopupTile.NOTES, simpleSlot("Notes"))
                    Tile(
                        PopupTile.OTHER,
                        TileSlot(Icons.Outlined.Description, "Other") {
                            androidx.compose.foundation.text.BasicTextField(
                                value = "",
                                onValueChange = {},
                                modifier = Modifier.focusRequester(fieldRequester).testTag("notes-test-offscreen-field"),
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { fieldRequester.requestFocus() }
        rule.waitForIdle()
        val field = bounds("notes-test-offscreen-field")
        val tile = bounds("${popupTileSpec.tagPrefix}-card-other")
        assertTrue(field.bottom.value <= TILE_VIEWPORT_HEIGHT_DP + 0.5f, "the actual text field is visible after focus: $field")
        assertTrue(tile.top.value in (TILE_VIEWPORT_HEIGHT_DP - 100f)..(TILE_VIEWPORT_HEIGHT_DP - 1f),
            "only the minimum amount was scrolled to reveal the field: $tile")
    }

    private fun simpleSlot(title: String) = TileSlot(Icons.Outlined.Description, title) {
        Box(Modifier.fillMaxSize())
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
}

private fun popupTileTree(): TileNode<PopupTile> = TileSplit(
    TileAxis.COLUMN,
    listOf(
        TileLeaf(PopupTile.VIDEO, sizeDp = VIDEO_HEIGHT_DP),
        TileLeaf(PopupTile.NOTES, sizeDp = NOTES_HEIGHT_DP),
        TileLeaf(PopupTile.OTHER, sizeDp = OTHER_HEIGHT_DP),
    ),
)

private val popupTileSpec = TileChromeSpec<PopupTile>(
    headerDp = 32f,
    titleSp = 12f,
    gapRowDp = 8f,
    gapColumnDp = 8f,
    cornerDp = 6,
    minTileDp = 60f,
    minPairShare = 0.1f,
    tagPrefix = "notes-menu-test",
    keyOf = { it.name.lowercase() },
    scrollMinDp = 60f,
    scrollMaxDp = 2000f,
    scrolls = { true },
)

private val popupCallbacks = TilingCallbacks<PopupTile>(
    onCommitLayout = {},
    onHide = {},
    onToggleCollapse = {},
    onMaximize = {},
    onReclaimFocus = {},
    dropLabel = { "drop" },
)
