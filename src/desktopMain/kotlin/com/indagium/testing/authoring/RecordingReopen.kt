package com.indagium.testing.authoring

import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep

// Reopening a recording whose evidence was kept: the stored rows, screen readings, screenshots and video come back as a STOPPED
// TestStepRecordingSession, with no device behind it, so the review panel, the AI rewrite, editing, reordering and applying work exactly
// as they do on a recording made just now. Reading the stored files never changes them; discarding the reopened recording leaves them be.

/** The stored recording a reopened session came from, kept so applying it can replace the steps it produced and update what is stored. */
internal class ReopenedRecording(
    val stored: StoredRecording,
    val notes: List<String>,
    /** Exact step values when reopened; replacement compares them atomically before changing the library. */
    val baselineSteps: List<TestStep> = emptyList(),
) {
    val recordingId: String get() = stored.recordingId

    /** The ids of the library steps the recording produced when it was last applied, in order. */
    val producedStepIds: List<String> get() = stored.stepIds

    fun info() = ReopenedRecordingInfo(
        recordingId = stored.recordingId,
        createdAtMs = stored.createdAtMs,
        updatedAtMs = stored.updatedAtMs,
        passwordSensitive = stored.privacy.passwordSensitive,
        videoStored = stored.video.stored,
        producedStepCount = stored.stepIds.size,
        notes = buildList {
            if (stored.privacy.passwordSensitive) add(RECORDING_PRIVACY_STATEMENT)
            else if (!stored.video.stored) stored.video.reason?.let { add("No video was stored: $it") }
            addAll(stored.notices)
            addAll(notes)
        }.distinct(),
    )
}

/**
 * A stopped session with the contents of [loaded]. Its video, when it has one, is opened read-only from the stored segments, so
 * [TestStepRecordingSession.close] on it never deletes anything.
 */
internal fun restoreRecordingSession(loaded: LoadedRecording, baselineSteps: List<TestStep> = emptyList()): TestStepRecordingSession {
    val timeline = loaded.video?.let(RecordedVideoTimeline::openStored)
    val notes = loaded.notes + if (loaded.video != null && timeline == null) listOf(VIDEO_UNUSABLE_NOTE) else emptyList()
    val session = TestStepRecordingSession(loaded.recording.deviceSerial, videoTimeline = timeline)
    session.loadStopped(loaded.recording, ReopenedRecording(loaded.recording, notes, baselineSteps.toList()))
    return session
}

private const val VIDEO_UNUSABLE_NOTE = "The stored video could not be opened, so the rewrite works from the stored screenshots only."

/** What applying a reopened recording does to the steps it made earlier. */
internal sealed interface ReplacementPlan {
    /** The produced steps are all still in the case, together and in order: they are replaced by the new ones, in place. */
    class Replace(val stepIds: List<String>) : ReplacementPlan

    /** The mapping is not clear; [reason] says why in words the person can act on. */
    class Ambiguous(val reason: String) : ReplacementPlan
}

/**
 * Which steps of [case] applying a reopened recording replaces. The steps [producedStepIds] must all still be there, next to each other
 * and in the order they were made in: anything else (one deleted, one moved away, a step added among them) leaves it unclear which steps
 * the new rows stand for, and the person is offered to insert them as new steps instead.
 */
internal fun planReplacement(case: TestCase, producedStepIds: List<String>): ReplacementPlan {
    if (producedStepIds.isEmpty()) return ReplacementPlan.Ambiguous("This recording did not record which steps it made.")
    val positions = producedStepIds.map { id -> case.steps.indexOfFirst { it.id == id } }
    val missing = positions.count { it < 0 }
    return when {
        missing > 0 -> ReplacementPlan.Ambiguous(
            "$missing of the ${producedStepIds.size} steps this recording made are no longer in the case, so it is unclear which steps to replace.",
        )
        positions.zipWithNext().any { (a, b) -> b != a + 1 } -> ReplacementPlan.Ambiguous(
            "The steps this recording made were moved, reordered or have other steps between them, so it is unclear which steps to replace.",
        )
        else -> ReplacementPlan.Replace(producedStepIds)
    }
}
