package com.indagium.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.indagium.capture.CaptureClockReference
import com.indagium.capture.CaptureSyncReliability
import com.indagium.capture.CaptureSyncSegment
import com.indagium.capture.CaptureTimeline
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoSource
import com.indagium.utils.computeItems
import java.awt.EventQueue
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capture navigation index is built on a background thread: the UI thread and anything under
 * stateLock only ever read a finished one. The build is held open here with
 * [AppState.captureIndexBuildGate] so the pending state is deterministic.
 */
@Suppress("MagicNumber")
class CaptureIndexPendingTest {
    @Test
    fun followIsUnavailableOnTheUiThreadUntilTheBackgroundBuildPublishes() = withPendingBuild { state, tab, gate ->
        val pending = onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertEquals(FollowMappingStatus.INDEX_PENDING, pending.status)
        assertNull(pending.mappedNearestLogId)
        assertEquals(VideoMappingKind.CAPTURE, pending.mappingKind)
        assertNull(onUiThread { state.followTargetVisibleLogId(tab.id, 150) })
        assertNull(onUiThread { state.logIdToVideoMs(tab, tab.logData[2].id) })
        assertNull(onUiThread { state.videoMsToNearestLogId(tab, 150) })
        assertTrue(gate.started.await(10, TimeUnit.SECONDS), "the build starts in the background")

        // Composition reads the version state, so a publish invalidates what was told "pending".
        val reads = mutableSetOf<Any>()
        onUiThread { Snapshot.observe(readObserver = { reads += it }) { state.videoFollowMapping(tab.id, 150) } }
        val invalidated = CountDownLatch(1)
        val registration = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) invalidated.countDown() }
        gate.release.countDown()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (invalidated.count > 0 && System.nanoTime() < deadline) {
                Snapshot.sendApplyNotifications()
                Thread.sleep(10)
            }
            assertEquals(0L, invalidated.count, "publishing the index must invalidate pending readers")
        } finally {
            registration.dispose()
        }

        val ready = onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, ready.status)
        assertEquals(tab.logData[2].id, ready.mappedNearestLogId)
        assertEquals(tab.logData[2].id, onUiThread { state.followTargetVisibleLogId(tab.id, 150) })
        assertEquals(1, gate.builds.get(), "repeated pending reads share one build, and so does the ready index")
    }

    @Test
    fun aCallerOffTheUiThreadGetsTheIndexAtOnceFromTheSameBuild() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))
        gate.release.countDown()

        // Not the UI thread and not under stateLock: joins the build already running (or its result).
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, state.videoFollowMapping(tab.id, 150).status)
        assertEquals(1, gate.builds.get())
    }

    @Test
    fun clearingTheLinkWorksWhileTheBuildIsPending() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        state.clearVideoAnchor(tab.id)

        val cleared = assertNotNull(state.tab(tab.id))
        assertNull(cleared.captureTimeline)
        val video = assertNotNull(cleared.attachedVideo)
        assertNull(video.captureSourcePath)
        assertTrue(video.linkCleared)
        assertTrue(!video.doubleClickSeekEnabled)
        gate.release.countDown()
        Thread.sleep(200)
        // The stale build neither resurrects the link nor breaks the tab.
        assertNull(assertNotNull(state.tab(tab.id)).captureTimeline)
        assertEquals(FollowMappingStatus.NO_ANCHOR, state.videoFollowMapping(tab.id, 150).status)
    }

    @Test
    fun linkingWhilePendingIsDeferredUntilTheIndexIsReadyNotDropped() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        onUiThread { state.setVideoAnchor(tab.id, 300, tab.logData[5].id) }

        val before = assertNotNull(assertNotNull(state.tab(tab.id)).attachedVideo)
        assertNull(before.anchor, "a capture link never takes a plain anchor")
        assertTrue(before.captureSegmentAnchors.isEmpty(), "nothing is applied before the index exists")

        gate.release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && assertNotNull(state.tab(tab.id)?.attachedVideo).captureSegmentAnchors.isEmpty()) {
            Thread.sleep(10)
        }
        val after = assertNotNull(state.tab(tab.id)?.attachedVideo)
        assertEquals(1, after.captureSegmentAnchors.size, "the deferred link lands once the index is ready")
        assertNull(after.anchor)
        assertNotNull(state.tab(tab.id)?.captureTimeline)
    }

    @Test
    fun doubleClickSeekPreferenceSurvivesAPendingBuild() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        // Presence of the capture link, not index readiness, decides whether the toggle is offered.
        assertTrue(state.isVideoDoubleClickSeekEnabled(tab.id))
        state.setVideoDoubleClickSeekEnabled(tab.id, true)
        assertTrue(assertNotNull(state.tab(tab.id)?.attachedVideo).doubleClickSeekEnabled)
        assertTrue(!onUiThread { state.seekVideoToLogRow(tab.id, tab.logData[2].id) }, "no seek until a mapping exists")
        gate.release.countDown()
    }

    @Test
    fun unlinkingWhileAnAlignmentIsPendingIsNotUndoneWhenTheBuildFinishes() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        onUiThread { state.setVideoAnchor(tab.id, 300, tab.logData[5].id) }
        state.clearVideoAnchor(tab.id)
        gate.release.countDown()
        settleBackgroundWork()

        val after = assertNotNull(state.tab(tab.id))
        val video = assertNotNull(after.attachedVideo)
        assertNull(video.anchor, "the deferred alignment must not turn the unlinked tab into a plain-anchored one")
        assertTrue(video.linkCleared, "Unlink stays in force")
        assertNull(video.captureSourcePath)
        assertNull(after.captureTimeline)
        assertTrue(video.captureSegmentAnchors.isEmpty(), "no segment calibration was written")
        assertEquals(0L, video.captureOffsetMs, "no capture offset was written")
        assertTrue(!video.doubleClickSeekEnabled)
    }

    @Test
    fun aNewerAlignmentSupersedesAnOlderOneStillPending() = withPendingBuild(twoSegmentTab()) { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        // Rows 3 and 9 sit in different segments, so an older alignment that also landed would leave a second anchor.
        onUiThread { state.setVideoAnchor(tab.id, 300, tab.logData[2].id) }
        onUiThread { state.setVideoAnchor(tab.id, 400, tab.logData[8].id) }
        gate.release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && assertNotNull(state.tab(tab.id)?.attachedVideo).captureSegmentAnchors.isEmpty()) {
            Thread.sleep(10)
        }
        settleBackgroundWork()

        val anchors = assertNotNull(state.tab(tab.id)?.attachedVideo).captureSegmentAnchors
        assertEquals(listOf(1), anchors.map { it.segmentId }, "only the latest pending alignment lands")
        assertEquals(400L, anchors.single().videoMs)
    }

    @Test
    fun attachingAnotherVideoWhileAnAlignmentIsPendingDropsIt() = withPendingBuild { state, tab, gate ->
        onUiThread { state.videoFollowMapping(tab.id, 150) }
        assertTrue(gate.started.await(10, TimeUnit.SECONDS))

        onUiThread { state.setVideoAnchor(tab.id, 300, tab.logData[5].id) }
        state.attachVideoToTab(File("/tmp/capture-index-pending-other.mkv"), tab.id)
        gate.release.countDown()
        settleBackgroundWork()

        val video = assertNotNull(assertNotNull(state.tab(tab.id)).attachedVideo)
        assertEquals(VideoSource.LocalFile("/tmp/capture-index-pending-other.mkv"), video.source)
        assertNull(video.anchor, "the alignment was meant for the previous recording, not the one attached since")
        assertTrue(video.captureSegmentAnchors.isEmpty())
    }

    /** The deferred alignment has no observable completion, so a negative assertion needs the IO scope to have had its turn. */
    private fun settleBackgroundWork() = Thread.sleep(400)

    private class Gate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
    }

    private fun <T> onUiThread(block: () -> T): T {
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun withPendingBuild(tab: LogTab = syntheticTab(), block: (AppState, LogTab, Gate) -> Unit) {
        val root = createTempDirectory("capture-index-pending").toFile()
        val state = AppState(autosaveFile = File(root, "autosave.cache"))
        val gate = Gate()
        try {
            state.tabs = listOf(tab)
            state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
            state.captureIndexBuildGate = {
                gate.builds.incrementAndGet()
                gate.started.countDown()
                check(gate.release.await(20, TimeUnit.SECONDS)) { "test never released the build" }
            }
            block(state, tab, gate)
        } finally {
            gate.release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    /** [syntheticTab] split in two reliable segments (rows 1-6 and 7-12), so alignments can target different ones. */
    private fun twoSegmentTab(): LogTab {
        val base = syntheticTab()
        val timeline = requireNotNull(base.captureTimeline)
        val first = timeline.syncSegments.single()
        val epoch = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val segments = listOf(
            first.copy(lastRowExclusive = 7),
            CaptureSyncSegment(
                id = 1,
                firstRow = 7,
                lastRowExclusive = 13,
                anchorLogTimeMs = epoch + 700L,
                anchorCaptureElapsedMs = 700L,
                reliability = CaptureSyncReliability.RELIABLE,
                clockReference = CaptureClockReference(700L, null, 2026),
            ),
        )
        return base.copy(captureTimeline = timeline.copy(syncSegments = segments))
    }

    /** 12 rows 100 ms apart; the video starts 100 ms after the first row, so row N sits at (N - 2) * 100 ms. */
    private fun syntheticTab(): LogTab {
        val entries = (1..12).map { ordinal ->
            LogEntry(ordinal * 10, "00:00:%02d.%03d".format(ordinal * 100 / 1000, ordinal * 100 % 1000), LogLevel.I, "Tag", "row $ordinal")
        }
        val segment = CaptureSyncSegment(
            id = 0,
            firstRow = 1,
            lastRowExclusive = entries.size + 1,
            anchorLogTimeMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + 100L,
            anchorCaptureElapsedMs = 100L,
            reliability = CaptureSyncReliability.RELIABLE,
            clockReference = CaptureClockReference(100L, null, 2026),
        )
        return LogTab(
            id = "capture-index-pending",
            filename = "capture.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = VideoAttachment(
                path = "/tmp/capture-index-pending.mkv",
                sourceLabel = "capture.mkv",
                durationMs = 1_000,
                captureSourcePath = "/tmp/capture-index-pending.zip",
                doubleClickSeekEnabled = true,
            ),
            captureTimeline = CaptureTimeline(
                rows = emptyList(),
                timelineDurationMs = 2_000,
                videoOriginElapsedMs = 200L,
                syncSegments = listOf(segment),
                videoDurationMs = 1_000,
                sparseTiming = true,
            ),
        )
    }
}
