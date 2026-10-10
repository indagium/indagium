package com.indagium.testing.authoring

import com.indagium.testing.model.TestStep
import com.indagium.testing.store.RECORDING_ID_PREFIX
import com.indagium.testing.store.RecordingEvidenceFiles
import com.indagium.testing.store.RecordingEvidenceUsage
import com.indagium.testing.store.StoreResult
import java.util.Locale
import java.util.UUID

// The part of "Keep recording evidence with recorded cases" that AppState wires in: what applying a recording does about its evidence
// (save it with the case, or update what a reopened recording stored), reading a stored recording back, and the disk usage and the
// "delete everything" of Settings. All blocking disk work happens here and is only ever called from IO threads, with no lock held.

private const val BYTES_PER_MB = 1024.0 * 1024.0

/** [bytes] as "12.3 MB" (one decimal, always a dot), the unit the settings and the notices speak. */
internal fun megabytesText(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB)

internal class RecordingEvidenceService(
    /** The recordings of the Test suites folder in use right now (the folder can change in Settings). */
    private val files: () -> RecordingEvidenceFiles,
    /** Removes the links of all steps to these recordings (TestLibraryStore.clearRecordingRefs). */
    private val clearRefs: (Set<String>) -> Unit,
    /** Removes the links of these steps (TestLibraryStore.clearRecordingRefsOfSteps). */
    private val unlinkSteps: (Set<String>) -> Unit,
    private val saver: () -> RecordingEvidenceSaver = { RecordingEvidenceSaver(files()) },
) {
    fun newRecordingId(): String = RECORDING_ID_PREFIX + UUID.randomUUID()

    /**
     * How to apply a recording made just now so its evidence is kept: the steps carry the new recording's id, and once they are in the
     * library the evidence is written. A failed write takes the link off the steps again and says so; the steps stay.
     */
    fun optionsForNewRecording(session: TestStepRecordingSession, suiteId: String, caseId: String): RecordingApplyOptions {
        val recordingId = newRecordingId()
        return RecordingApplyOptions(
            recordingRef = recordingId,
            afterApplied = { snapshot, inserted ->
                val evidence = session.evidence().withSnapshot(snapshot)
                when (val saved = saver().saveNew(suiteId, caseId, recordingId, evidence, inserted.map(TestStep::id))) {
                    is StoreResult.Ok -> listOf("Recording evidence saved with the case (${megabytesText(saved.value.bytes)}).") + saved.value.notes
                    else -> {
                        clearRefs(setOf(recordingId))
                        listOf("The recording's evidence was not saved: ${saved.failureText()}. The steps were applied without it.")
                    }
                }
            },
        )
    }

    /**
     * How to apply a reopened recording: the new steps carry its id and, once they are in the library, its stored rows are updated.
     * With [replaceStepIds] the steps it made before are replaced in place; without, the steps are inserted and [replacedStepIds] (the
     * ones it made before, which stay in the case) lose their link, since the recording now stands for the new ones.
     */
    fun optionsForReopened(
        session: TestStepRecordingSession,
        reopened: ReopenedRecording,
        suiteId: String,
        replaceStepIds: List<String>?,
        replacedStepIds: List<String>,
    ): RecordingApplyOptions = RecordingApplyOptions(
        recordingRef = reopened.recordingId,
        replaceStepIds = replaceStepIds,
        replaceStepBaseline = reopened.baselineSteps.takeIf { replaceStepIds != null },
        afterApplied = { snapshot, inserted ->
            if (replaceStepIds == null) unlinkSteps(replacedStepIds.toSet())
            val evidence = session.evidence().withSnapshot(snapshot)
            when (val saved = saver().updateRows(suiteId, reopened.stored, evidence, inserted.map(TestStep::id))) {
                is StoreResult.Ok -> listOf("The stored recording was updated (${megabytesText(saved.value.bytes)}).")
                else -> listOf("The stored recording could not be updated: ${saved.failureText()}. The steps were applied.")
            }
        },
    )

    /** Reads a stored recording back (blocking; IO thread). */
    fun load(suiteId: String, recordingId: String): StoreResult<LoadedRecording> = loadStoredRecording(files(), suiteId, recordingId)

    fun exists(suiteId: String, recordingId: String): Boolean = files().exists(suiteId, recordingId)

    /** How many recordings are stored in the Test suites folder and how much disk they use (blocking; IO thread). */
    fun usage(): RecordingEvidenceUsage = files().usage()

    /** Removes every stored recording and the links to them; returns what was removed (blocking; IO thread). */
    fun deleteAll(): RecordingEvidenceUsage {
        val folder = files()
        val ids = folder.suiteIdsWithRecordings().flatMap { suiteId -> folder.listIds(suiteId) }.toSet()
        val removed = folder.deleteAll()
        if (ids.isNotEmpty()) clearRefs(ids)
        return removed
    }

    private fun SessionEvidence.withSnapshot(snapshot: TestStepRecordingSnapshot) =
        SessionEvidence(snapshot, screenStates, probeScreenshots, video, deviceSerial)

    private fun StoreResult<*>.failureText(): String = when (this) {
        is StoreResult.Invalid -> reason
        is StoreResult.NotFound -> message
        is StoreResult.LimitReached -> decision.message
        is StoreResult.Ok -> ""
    }
}
