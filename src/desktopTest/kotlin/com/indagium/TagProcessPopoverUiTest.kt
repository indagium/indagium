@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.ui.TagProcessPopover
import com.indagium.utils.TagProcessInfo
import com.indagium.utils.canFollowTagByToken
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class TagProcessPopoverUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val infos = listOf(
        TagProcessInfo(100, "com.example.app", tagLines = 5, totalLines = 20, distinctTags = 4, firstTs = "10:00:00.000", lastTs = "10:05:00.000"),
        TagProcessInfo(200, null, tagLines = 2, totalLines = 3, distinctTags = 1, firstTs = "10:06:00.000", lastTs = "10:06:30.000"),
    )

    // Hoists the same state FilterPanel owns, so the toggles in the test behave like the real wiring.
    private fun setPopover(
        tag: String = "CarService",
        initiallySelected: Set<Int> = setOf(100, 200),
        loaded: List<TagProcessInfo>? = infos,
        onAdd: (Set<Int>, Boolean) -> Unit = { _, _ -> },
        cursor: Int = -1,
    ) {
        rule.setContent {
            var selected by remember { mutableStateOf(initiallySelected) }
            var keep by remember { mutableStateOf(true) }
            TagProcessPopover(
                tag = tag,
                infos = loaded,
                selected = selected,
                keepFollowing = keep,
                onToggle = { pid -> selected = if (pid in selected) selected - pid else selected + pid },
                onKeepFollowingChange = { keep = it },
                onAdd = { onAdd(selected, keep) },
                onCancel = {},
                cursor = cursor,
            )
        }
    }

    @Test
    fun listsEachProcessWithItsNameOrAnUnknownMarker() {
        setPopover()

        rule.onNodeWithText("com.example.app").assertExists()
        rule.onNodeWithText("unknown process").assertExists()
        rule.onNodeWithText("20 lines").assertExists()
    }

    @Test
    fun keepFollowingStartsEnabledWhenEveryPidIsChecked() {
        setPopover()

        rule.onNodeWithTag("tag-process-keep-following").assertIsEnabled()
    }

    @Test
    fun keepFollowingIsDisabledOnceAPidIsUnchecked() {
        setPopover()

        rule.onNodeWithTag("tag-process-row-200").performClick()

        rule.onNodeWithTag("tag-process-keep-following").assertIsNotEnabled()
        rule.onNodeWithText("Following restarts needs all pids checked").assertExists()
    }

    @Test
    fun keepFollowingIsDisabledForATagNameThatCannotBeATokenEvenWithEveryPidChecked() {
        setPopover(tag = "My Tag")

        rule.onNodeWithTag("tag-process-keep-following").assertIsNotEnabled()
        rule.onNodeWithText("Can't follow restarts for this tag name").assertExists()
        assertEquals(false, canFollowTagByToken("My Tag"))
    }

    @Test
    fun addIsDisabledWithNothingChecked() {
        val added = AtomicInteger()
        setPopover(initiallySelected = emptySet(), onAdd = { _, _ -> added.incrementAndGet() })

        rule.onNodeWithTag("tag-process-add").assertIsNotEnabled()
        rule.onNodeWithTag("tag-process-add").performClick()
        rule.runOnIdle { assertEquals(0, added.get()) }
    }

    @Test
    fun addReportsTheCheckedPidsAndKeepFollowingChoice() {
        var added: Pair<Set<Int>, Boolean>? = null
        setPopover(onAdd = { selected, keep -> added = selected to keep })

        rule.onNodeWithTag("tag-process-add").assertIsEnabled().performClick()

        rule.runOnIdle { assertEquals(setOf(100, 200) to true, added) }
    }

    @Test
    fun addStaysDisabledWhileTheScanIsRunning() {
        setPopover(loaded = null)

        rule.onNodeWithText("Scanning…").assertExists()
        rule.onNodeWithTag("tag-process-add").assertIsNotEnabled()
    }

    @Test
    fun theCursorRowIsMarkedSelectedAndOthersAreNot() {
        setPopover(cursor = 1)

        rule.onNodeWithTag("tag-process-item-200").assertIsSelected()
        rule.onNodeWithTag("tag-process-item-100").assertIsNotSelected()
        rule.onNodeWithTag("tag-process-keep-row").assertIsNotSelected()
    }

    @Test
    fun theCursorCanSitOnTheKeepFollowingRow() {
        setPopover(cursor = infos.size)

        rule.onNodeWithTag("tag-process-keep-row").assertIsSelected()
        rule.onNodeWithTag("tag-process-item-100").assertIsNotSelected()
    }

    @Test
    fun noCursorMarksNothingAndTheKeyHintShows() {
        setPopover()

        rule.onNodeWithTag("tag-process-item-100").assertIsNotSelected()
        rule.onNodeWithTag("tag-process-keep-row").assertIsNotSelected()
        rule.onNodeWithText("↑↓ move · Space toggle · Enter add · Esc close").assertExists()
    }
}
