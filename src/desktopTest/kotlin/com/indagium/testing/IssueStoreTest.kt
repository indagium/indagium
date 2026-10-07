package com.indagium.testing

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDestinationResult
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueRecheck
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.store.ISSUE_FILE_NAME
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.decodeIssueFile
import com.indagium.testing.store.encodeIssueFile
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueStoreTest {
    private lateinit var root: File
    private lateinit var store: IssueStore
    private var now = 1_000L

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("issue-store").toFile()
        store = IssueStore(File(root, "issues")) { now++ }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val source = IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", iteration = 2, caseName = "Login", stepNumber = 3)

    private fun file(name: String, text: String): File = File(root, "src/$name").apply { parentFile.mkdirs(); writeText(text) }

    private fun draft(vararg attachments: IssueAttachment) = IssueDraft(
        title = "Login fails",
        severity = IssueSeverity.CRITICAL,
        labels = listOf("smoke", "found-by-agent"),
        stepsToReproduce = listOf("Open the app", "Tap login"),
        expected = "Home", actual = "Crash", judgeNotes = "Judge: FAIL",
        environment = IssueEnvironment(appPackage = "com.example.app", deviceSerial = "SER-1", agent = "agent", runId = "run-1", build = "42"),
        attachments = attachments.toList(),
    )

    private fun fromFile(name: String, text: String, kind: IssueAttachmentKind = IssueAttachmentKind.SCREENSHOT) =
        file(name, text).let { IssueAttachment(kind, "A $name", name, sizeBytes = it.length(), sourcePath = it.absolutePath) }

    private fun fromText(name: String, text: String) =
        IssueAttachment(IssueAttachmentKind.LOG_RANGE, "Log", name, sizeBytes = text.length.toLong(), text = text)

    private fun ok(result: StoreResult<com.indagium.testing.model.IssueRecord>) = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(result)

    @Test
    fun createCopiesAttachmentsIntoTheIssueFolderAndTheRecordRoundTrips() {
        val evidence = draft(fromFile("shot.png", "PNG-DATA"), fromText("log-range.txt", "log text"))
        val created = ok(store.create(evidence, source, IssueStatus.DRAFT, linkToCase = true)).value

        assertTrue(created.id.startsWith("issue-"))
        val dir = store.issueDir(created.id)
        assertEquals("PNG-DATA", File(dir, "attachments/shot.png").readText(), "a source file is copied")
        assertEquals("log text", File(dir, "attachments/log-range.txt").readText(), "generated text is written as a file")
        assertTrue(File(dir, ISSUE_FILE_NAME).isFile)

        val loaded = assertNotNull(store.load(created.id))
        assertEquals(created, loaded)
        assertEquals("Login fails", loaded.draft.title)
        assertEquals(IssueSeverity.CRITICAL, loaded.draft.severity)
        assertEquals(listOf("Open the app", "Tap login"), loaded.draft.stepsToReproduce)
        assertEquals("42", loaded.draft.environment.build)
        assertEquals(source, loaded.source)
        assertTrue(loaded.linkToCase)
        assertEquals(listOf("attachments/shot.png", "attachments/log-range.txt"), loaded.draft.attachments.map { it.storedPath })
        assertTrue(loaded.draft.attachments.all { it.sourcePath == null && it.text == null }, "transient content is not kept")
        assertEquals(8L, loaded.draft.attachments[0].sizeBytes)
        assertEquals("PNG-DATA", store.attachmentFile(loaded, loaded.draft.attachments[0])?.readText())
    }

    @Test
    fun excludedAttachmentsRemainSelectableAndUnreadableSourcesWarn() {
        val excluded = fromFile("skip.png", "x").copy(include = false)
        val missing = IssueAttachment(IssueAttachmentKind.GOLDEN, "Gone", "gone.png", sourcePath = File(root, "nope.png").absolutePath)

        val result = ok(store.create(draft(excluded, missing, fromText("keep.txt", "k")), source))

        assertEquals(listOf("skip.png", "gone.png", "keep.txt"), result.value.draft.attachments.map { it.fileName })
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().startsWith("Gone"), result.warnings.toString())
        assertTrue(File(store.issueDir(result.value.id), "attachments/skip.png").exists(), "unchecked evidence remains available for later selection")
        assertFalse(result.value.draft.attachments[0].include)
    }

    @Test
    fun attachmentFileNamesAreMadeSafeAndUnique() {
        val created = ok(
            store.create(draft(fromText("../evil name.txt", "a"), fromText("same.txt", "b"), fromText("same.txt", "c")), source),
        ).value

        val names = created.draft.attachments.map { it.fileName }
        assertEquals(3, names.toSet().size, names.toString())
        assertTrue(names.none { it.contains('/') || it.contains(' ') || it.contains("..") }, names.toString())
        val dir = File(store.issueDir(created.id), "attachments")
        assertEquals(names.toSet(), dir.list()!!.toSet())
    }

    @Test
    fun listIsNewestFirstAndSkipsUnreadableFolders() {
        val first = ok(store.create(draft(), source)).value
        val second = ok(store.create(draft(), source)).value
        File(root, "issues/issue-broken").apply { mkdirs() }.resolve(ISSUE_FILE_NAME).writeText("{ not json")
        File(root, "issues/not an id").mkdirs()

        assertEquals(listOf(second.id, first.id), store.list().map { it.id })
        assertNull(store.load("issue-broken"))
    }

    @Test
    fun updateKeepsIdSourceAndCreationTimeAndRetainsFilesWhenUnchecked() {
        val created = ok(store.create(draft(fromFile("a.png", "A"), fromText("b.txt", "B")), source)).value
        val dir = store.issueDir(created.id)

        val updated = ok(
            store.update(created.id) { record ->
                record.copy(
                    id = "issue-other", source = source.copy(stepId = "step-9"), createdAt = 5L, status = IssueStatus.SAVED,
                    draft = record.draft.copy(
                        title = "New title",
                        attachments = record.draft.attachments.map { if (it.fileName == "a.png") it.copy(include = false) else it } + fromText("c.txt", "C"),
                    ),
                    recheck = IssueRecheck("run-2", RecheckOutcome.STILL_FAILING, 77L),
                    destinationResults = listOf(IssueDestinationResult(IssueDestination.LOCAL, true, "Saved locally.", 9L)),
                )
            },
        ).value

        assertEquals(created.id, updated.id)
        assertEquals(source, updated.source)
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > created.updatedAt)
        assertEquals(IssueStatus.SAVED, updated.status)
        assertTrue(File(dir, "attachments/a.png").exists(), "an unchecked attachment remains available")
        assertTrue(File(dir, "attachments/b.txt").isFile)
        assertEquals("C", File(dir, "attachments/c.txt").readText(), "a new attachment is copied in")
        val loaded = assertNotNull(store.load(created.id))
        assertEquals(listOf("a.png", "b.txt", "c.txt"), loaded.draft.attachments.map { it.fileName })
        assertFalse(loaded.draft.attachments.first().include)
        assertTrue(ok(store.update(created.id) { record ->
            record.copy(draft = record.draft.copy(attachments = record.draft.attachments.map { it.copy(include = true) }))
        }).value.draft.attachments.all { it.include }, "reselecting after restart keeps the original bytes")
        assertEquals(RecheckOutcome.STILL_FAILING, loaded.recheck?.outcome)
        assertEquals("Saved locally.", loaded.destinationResults.single().message)
    }

    @Test
    fun aFailedRecordWritePreservesOldBytesAndRemovesOnlyNewAssets() {
        val issuesDir = File(root, "write-failure/issues")
        val failingStore = IssueStore(
            issuesDir,
            recordWriter = { file, record ->
                if (record.draft.title == "rejected") throw IOException("simulated write failure")
                writeFileAtomically(file) { it.write(encodeIssueFile(record)) }
            },
        )
        val created = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(
            failingStore.create(draft(fromText("kept.txt", "original")), source),
        ).value
        val original = assertNotNull(failingStore.attachmentFile(created, created.draft.attachments.single())).readText()
        val result = failingStore.update(created.id) { record ->
            record.copy(draft = record.draft.copy(title = "rejected", attachments = record.draft.attachments + fromText("new.txt", "staged")))
        }
        assertIs<StoreResult.Invalid>(result)
        assertEquals("original", original)
        assertEquals("original", File(failingStore.issueDir(created.id), "attachments/kept.txt").readText())
        assertFalse(File(failingStore.issueDir(created.id), "attachments/new.txt").exists())
        assertEquals(created, failingStore.load(created.id))
    }

    @Test
    fun aLaterCopyFailureCleansEarlierStagedAttachmentsAndLeavesTheRecordUntouched() {
        val issuesDir = File(root, "copy-failure/issues")
        val failingStore = IssueStore(
            issuesDir,
            attachmentWriter = { dir, name, attachment ->
                if (name.startsWith("second")) throw IOException("simulated second-copy failure")
                val bytes = attachment.text?.toByteArray() ?: File(attachment.sourcePath!!).readBytes()
                File(dir, name).writeBytes(bytes)
                bytes.size.toLong()
            },
        )
        val created = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(failingStore.create(draft(), source)).value
        val result = failingStore.update(created.id) { record ->
            record.copy(draft = record.draft.copy(attachments = listOf(fromText("first.txt", "one"), fromText("second.txt", "two"))))
        }
        assertIs<StoreResult.Invalid>(result)
        assertEquals(created, failingStore.load(created.id))
        assertEquals(emptyList(), File(failingStore.issueDir(created.id), "attachments").list()?.toList().orEmpty())
    }

    @Test
    fun updateAndDeleteOfAnUnknownOrUnsafeIdAreNotFound() {
        assertIs<StoreResult.NotFound>(store.update("issue-nope") { it })
        assertIs<StoreResult.NotFound>(store.update("../x") { it })
        assertIs<StoreResult.NotFound>(store.delete("issue-nope"))
        assertIs<StoreResult.NotFound>(store.delete("../x"))
        assertNull(store.load("../x"))
        assertFailsOnUnsafe { store.issueDir("../x") }
    }

    private fun assertFailsOnUnsafe(block: () -> Unit) {
        val failed = runCatching(block).exceptionOrNull()
        assertTrue(failed is IllegalArgumentException, "an unsafe id must not reach the file system: $failed")
    }

    @Test
    fun deleteRemovesTheWholeFolder() {
        val created = ok(store.create(draft(fromFile("a.png", "A")), source)).value
        assertTrue(store.issueDir(created.id).isDirectory)

        assertIs<StoreResult.Ok<Unit>>(store.delete(created.id))

        assertFalse(store.issueDir(created.id).exists())
        assertNull(store.load(created.id))
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun writesAreAtomicAndLeaveNoTemporaryFile() {
        val created = ok(store.create(draft(), source)).value
        repeat(3) { n -> store.update(created.id) { it.copy(draft = it.draft.copy(title = "Title $n")) } }

        val leftovers = store.issueDir(created.id).list()!!.filter { it.startsWith(".") || it.contains(".tmp") }
        assertEquals(emptyList(), leftovers)
        assertEquals("Title 2", store.load(created.id)?.draft?.title)
    }

    @Test
    fun decodingIsTolerantOfUnknownKeysMissingFieldsAndBadEnums() {
        val text = """
            {"format":"indagium-issue","version":1,"extra":true,"issue":{
              "id":"issue-abc","status":"WHATEVER","future":[1,2],
              "draft":{"title":"Only a title","severity":"EXTREME","labels":["a",3],"attachments":[
                {"kind":"SCREENSHOT","fileName":"s.png","storedPath":"attachments/s.png","label":"S"},
                {"kind":"UNKNOWN_KIND","fileName":"x"},{"kind":"LOG_RANGE"}]},
              "destinationResults":[{"destination":"NOWHERE"},{"destination":"NOTES","ok":true,"message":"m","at":5}]
            }}
        """.trimIndent()

        val record = decodeIssueFile(text).getOrThrow()

        assertEquals("issue-abc", record.id)
        assertEquals(IssueStatus.DRAFT, record.status)
        assertEquals(IssueSeverity.MEDIUM, record.draft.severity)
        assertEquals(listOf("a"), record.draft.labels)
        assertEquals(listOf("s.png"), record.draft.attachments.map { it.fileName }, "entries of an unknown kind or without a file name are skipped")
        assertEquals(listOf(IssueDestination.NOTES), record.destinationResults.map { it.destination })
        assertEquals("", record.draft.expected)
        assertEquals(1, record.source.iteration)
        assertFalse(record.readOnly)
    }

    @Test
    fun theEnvelopeIsStrictAndANewerVersionIsReadOnlyAndNeverRewritten() {
        assertTrue(decodeIssueFile("not json").isFailure)
        assertTrue(decodeIssueFile("""{"format":"other","version":1,"issue":{}}""").isFailure)
        assertTrue(decodeIssueFile("""{"format":"indagium-issue","version":1}""").isFailure)
        assertTrue(decodeIssueFile("""{"format":"indagium-issue","version":1,"issue":{"id":"../bad"}}""").isFailure, "an unsafe id is not usable")

        val created = ok(store.create(draft(), source)).value
        val file = File(store.issueDir(created.id), ISSUE_FILE_NAME)
        file.writeText(encodeIssueFile(created).replace("\"version\": 2", "\"version\": 99"))
        val newer = assertNotNull(store.load(created.id))
        assertTrue(newer.readOnly)
        val refused = store.update(created.id) { it.copy(status = IssueStatus.SENT) }
        assertIs<StoreResult.Invalid>(refused)
        assertEquals(IssueStatus.DRAFT, store.load(created.id)?.status, "the file from the newer version was not rewritten")
    }

    @Test
    fun attachmentPathsThatLeaveTheIssueFolderAreNotResolved() {
        val created = ok(store.create(draft(fromText("a.txt", "A")), source)).value
        File(root, "secret.txt").writeText("secret")
        val evil = created.draft.attachments.single().copy(storedPath = "../../secret.txt")

        assertNull(store.attachmentFile(created, evil))
        assertNull(store.attachmentFile(created, evil.copy(storedPath = null)))
        assertNotNull(store.attachmentFile(created, created.draft.attachments.single()))
    }

    @Test
    fun revisionChangesAfterEverySuccessfulMutationOnly() {
        val before = store.revision.value
        val created = ok(store.create(draft(), source)).value
        val afterCreate = store.revision.value
        store.update(created.id) { it.copy(status = IssueStatus.SAVED) }
        val afterUpdate = store.revision.value
        store.update("issue-nope") { it }
        store.delete("issue-nope")
        val afterFailures = store.revision.value
        store.delete(created.id)

        assertTrue(afterCreate > before)
        assertTrue(afterUpdate > afterCreate)
        assertEquals(afterUpdate, afterFailures)
        assertTrue(store.revision.value > afterFailures)
    }
}
