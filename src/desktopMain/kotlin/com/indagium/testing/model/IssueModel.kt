package com.indagium.testing.model

// Domain types of ISSUES created from failed test steps. An IssueDraft is the editable text and evidence of one issue; an
// IssueRecord is a draft that was stored (with a status, where it came from and where it was sent). Pure data and
// immutable. Evidence attachments are described here and materialised by the IssueStore: while the issue is a draft an
// attachment names a source file (or carries its text); once stored it names the copy inside the issue's folder.

const val ISSUE_ID_PREFIX = "issue-"

fun newIssueId(): String = newPrefixedId(ISSUE_ID_PREFIX)

enum class IssueSeverity { LOW, MEDIUM, HIGH, CRITICAL }

/** DRAFT: created automatically or saved unfinished. SAVED: kept locally. SENT: delivered to notes, Markdown or a tracker. */
enum class IssueStatus { DRAFT, SAVED, SENT }

/** TRACKER needs the issue tracker configuration that arrives in a later version; it is modelled so records and tools are stable. */
enum class IssueDestination { LOCAL, NOTES, MARKDOWN, TRACKER }

enum class IssueAttachmentKind { VIDEO_CLIP, LOG_RANGE, SCREENSHOT, TRANSCRIPT, JUDGE_VERDICT, GOLDEN }

/**
 * One piece of evidence. Exactly one of [sourcePath] (an absolute file to copy), [text] (generated text to write as a
 * file) or [storedPath] (a path relative to the issue's folder, set by the store) says where the content is. [include]
 * is the user's choice in the evidence checklist; the store keeps only included attachments. [note] is extra context
 * (a video attachment says where the step starts in it).
 */
data class IssueAttachment(
    val kind: IssueAttachmentKind,
    val label: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val include: Boolean = true,
    val sourcePath: String? = null,
    val text: String? = null,
    val storedPath: String? = null,
    val note: String = "",
)

/** Where the issue was seen. Every part may be unknown. */
data class IssueEnvironment(
    val appPackage: String = "",
    val deviceModel: String = "",
    val deviceSerial: String = "",
    val agent: String = "",
    val runId: String = "",
    val build: String = "",
)

data class IssueDraft(
    val title: String,
    val severity: IssueSeverity = IssueSeverity.MEDIUM,
    val labels: List<String> = emptyList(),
    val stepsToReproduce: List<String> = emptyList(),
    val expected: String = "",
    val actual: String = "",
    val judgeNotes: String = "",
    val environment: IssueEnvironment = IssueEnvironment(),
    val attachments: List<IssueAttachment> = emptyList(),
)

/** The step of a run an issue is about. [suiteId] and [caseId] let a later run of that case re-check it. */
data class IssueSource(
    val runId: String,
    val laneId: String,
    val suiteId: String,
    val caseId: String,
    val stepId: String,
    val iteration: Int = 1,
    val caseName: String = "",
    val stepNumber: Int = 0,
)

/** What a delivery to one destination came to. [reference] is where to find it (a tab id, a path, ...). */
data class IssueDestinationResult(
    val destination: IssueDestination,
    val ok: Boolean,
    val message: String,
    val at: Long,
    val reference: String? = null,
)

enum class RecheckOutcome { STILL_FAILING, PASSING_NOW }

/** What the latest later run of the issue's case said about its step. */
data class IssueRecheck(val runId: String, val outcome: RecheckOutcome, val checkedAt: Long)

/**
 * [linkToCase]: a later run of the issue's case marks the issue "still failing" or "passing now" in [recheck].
 * [readOnly] is never persisted: it marks a record written by a newer version, which is shown but not rewritten.
 */
data class IssueRecord(
    val id: String,
    val draft: IssueDraft,
    val source: IssueSource,
    val status: IssueStatus = IssueStatus.DRAFT,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val destinationResults: List<IssueDestinationResult> = emptyList(),
    val linkToCase: Boolean = false,
    val recheck: IssueRecheck? = null,
    val readOnly: Boolean = false,
)

/** A one-line view of an issue for lists. */
data class IssueSummary(
    val id: String,
    val title: String,
    val severity: IssueSeverity,
    val status: IssueStatus,
    val caseName: String,
    val runId: String,
    val createdAt: Long,
    val recheck: RecheckOutcome?,
)

fun IssueRecord.summary(): IssueSummary =
    IssueSummary(id, draft.title, draft.severity, status, source.caseName, source.runId, createdAt, recheck?.outcome)
