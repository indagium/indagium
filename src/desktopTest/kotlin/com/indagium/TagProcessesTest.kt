@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.TAG_PID_TOKEN_PREFIX
import com.indagium.utils.TagProcessInfo
import com.indagium.utils.canFollowTagByToken
import com.indagium.utils.computeTagPids
import com.indagium.utils.defaultTagProcessSelection
import com.indagium.utils.mergeTagPids
import com.indagium.utils.tagProcessInfo
import com.indagium.utils.tagProcessRulePattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TagProcessesTest {
    private var nextId = 1

    private fun e(tag: String, pid: Int, ts: String = "10:00:00.000") =
        LogEntry(nextId++, ts, LogLevel.I, tag, "msg", pid = pid, tid = pid)

    private fun info(pid: Int, name: String?, tagLines: Int = 1) =
        TagProcessInfo(pid, name, tagLines, tagLines, 1, "10:00:00.000", "10:00:01.000")

    @Test
    fun computeTagPidsSkipsEntriesWithoutARealPid() {
        val data = listOf(e("X", 100), e("X", 200), e("X", 0), e("Y", 0), e("Y", 100), e("X", 100))

        val result = computeTagPids(data)

        assertEquals(mapOf("X" to setOf(100, 200), "Y" to setOf(100)), result)
    }

    @Test
    fun mergeTagPidsUnionsPerTagWithoutMutatingTheBase() {
        val base: Map<String, Set<Int>> = mapOf("X" to setOf(100), "Y" to setOf(100))

        val merged = mergeTagPids(base, listOf(e("X", 200), e("Z", 300), e("Y", 100), e("X", 0)))

        assertEquals(mapOf("X" to setOf(100, 200), "Y" to setOf(100), "Z" to setOf(300)), merged)
        assertEquals(mapOf("X" to setOf(100), "Y" to setOf(100)), base, "the published base map must stay untouched")
    }

    @Test
    fun mergeTagPidsReturnsTheBaseWhenNothingIsNew() {
        val base: Map<String, Set<Int>> = mapOf("X" to setOf(100))

        assertSame(base, mergeTagPids(base, listOf(e("X", 100), e("Y", 0))))
    }

    @Test
    fun tagProcessInfoCountsLinesTagsAndTimestampsPerPidAndSortsByTagLines() {
        val data = listOf(
            e("X", 100, "10:00:00.000"),
            e("A", 100, "10:00:01.000"),
            e("B", 100, "10:00:02.000"),
            e("X", 200, "10:00:03.000"),
            e("X", 200, "10:00:04.000"),
            e("X", 200, "10:00:05.000"),
            e("A", 300, "10:00:06.000"),
            e("X", 0, "10:00:07.000"),
            e("X", 100, "10:00:08.000"),
        )

        val infos = tagProcessInfo(data, "X", mapOf(100 to "com.example.app"), cancellationCheck = {})

        assertEquals(
            listOf(
                TagProcessInfo(200, null, tagLines = 3, totalLines = 3, distinctTags = 1, firstTs = "10:00:03.000", lastTs = "10:00:05.000"),
                TagProcessInfo(100, "com.example.app", tagLines = 2, totalLines = 4, distinctTags = 3, firstTs = "10:00:00.000", lastTs = "10:00:08.000"),
            ),
            infos,
            "pid 300 never logged X and pid 0 is skipped; most lines of the tag first",
        )
    }

    @Test
    fun tagProcessInfoBreaksTagLineTiesByPid() {
        val infos = tagProcessInfo(listOf(e("X", 300), e("X", 100)), "X", emptyMap(), cancellationCheck = {})

        assertEquals(listOf(100, 300), infos.map { it.pid })
    }

    @Test
    fun tagProcessInfoPollsTheCancellationCheck() {
        val data = (1..10_000).map { e("X", 100) }

        assertFailsWith<IllegalStateException> {
            tagProcessInfo(data, "X", emptyMap()) { error("cancelled") }
        }
    }

    @Test
    fun defaultSelectionPicksTheTopPidAndEveryPidWithTheSameName() {
        val infos = listOf(info(100, "app", 5), info(200, "other", 4), info(300, "app", 1))

        assertEquals(setOf(100, 300), defaultTagProcessSelection(infos))
    }

    @Test
    fun defaultSelectionWithDifferentNamesPicksOnlyTheTopPid() {
        val infos = listOf(info(100, "a", 5), info(200, "b", 4))

        assertEquals(setOf(100), defaultTagProcessSelection(infos))
    }

    @Test
    fun defaultSelectionTreatsUnknownNamesAsEqual() {
        val infos = listOf(info(100, null, 5), info(200, null, 4), info(300, "known", 3))

        assertEquals(setOf(100, 200), defaultTagProcessSelection(infos))
    }

    @Test
    fun defaultSelectionOfNothingIsEmpty() {
        assertTrue(defaultTagProcessSelection(emptyList()).isEmpty())
    }

    @Test
    fun rulePatternIsTheDynamicTokenOnlyWhenKeepingToFollowWithEveryPidSelected() {
        val all = setOf(300, 100, 200)

        assertEquals("${TAG_PID_TOKEN_PREFIX}X", tagProcessRulePattern("X", all, all, keepFollowing = true))
    }

    @Test
    fun rulePatternForASubsetListsTheSelectedPidsAscending() {
        val all = setOf(100, 200, 300)

        assertEquals("100,300", tagProcessRulePattern("X", setOf(300, 100), all, keepFollowing = true))
    }

    @Test
    fun rulePatternWithoutKeepFollowingListsPidsEvenWhenAllAreSelected() {
        val all = setOf(300, 100, 200)

        assertEquals("100,200,300", tagProcessRulePattern("X", all, all, keepFollowing = false))
    }

    @Test
    fun rulePatternFallsBackToPidsForATagThatCannotBeWrittenAsAToken() {
        val all = setOf(300, 100)

        assertEquals("100,300", tagProcessRulePattern("My Tag", all, all, keepFollowing = true))
        assertEquals("100,300", tagProcessRulePattern("a,b", all, all, keepFollowing = true))
    }

    @Test
    fun onlyTagsWithoutWhitespaceOrCommasCanBeFollowedByToken() {
        assertTrue(canFollowTagByToken("com.example.Car-Service_1"))
        assertFalse(canFollowTagByToken("My Tag"))
        assertFalse(canFollowTagByToken("tab\there"))
        assertFalse(canFollowTagByToken("a,b"))
        assertFalse(canFollowTagByToken(""))
    }
}
