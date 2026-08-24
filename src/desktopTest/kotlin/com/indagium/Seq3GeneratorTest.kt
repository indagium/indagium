package com.indagium

import com.indagium.diagram3.MAX_SEQ3_HEADER_CHARS
import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.generateSeq3
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Seq3GeneratorTest {
    private fun entry(id: Int, ts: String, tag: String, msg: String, level: LogLevel = LogLevel.I, pid: Int = 0, tid: Int = 0) =
        LogEntry(id, ts, level, tag, msg, pid, tid)

    @Test
    fun rankingKeepsOnlyTheTopSignalTagsAsLifelinesAndCapsTheCount() {
        // 10 distinct tags: "Loud" carries every high-signal trait (errors + varied message shapes
        // + many occurrences); the other nine are single, unremarkable, low-volume rows — exactly
        // the shape the cap (DEFAULT_SEQ3_MAX_LIFELINES = 8) exists to trim down to a readable set.
        val loud = (1..5).map {
            entry(it, "10:00:00.%03d".format(it), "Loud", "failure kind $it", level = LogLevel.E)
        }
        val quiet = ('A'..'I').mapIndexed { index, tagSuffix ->
            entry(100 + index, "10:00:01.000", "Quiet$tagSuffix", "nothing much happens")
        }
        val doc = generateSeq3(loud + quiet, Seq3Range.VisibleView)

        assertTrue(doc.lifelines.size <= 8, "the lifeline count must stay capped: ${doc.lifelines.size}")
        assertEquals("Loud", doc.lifelines.first().name, "the highest-signal tag must rank first")
        assertTrue(doc.lifelines.none { it.name == "QuietI" }, "the least-signal tag must be dropped by the cap")
    }

    @Test
    fun sameThreadHandoffInfersTheAdjacentTagAsTarget() {
        val entries = (0 until 3).flatMap { i ->
            val base = i * 2 + 1
            listOf(
                entry(base, "10:00:00.%03d".format(i * 100), "Producer", "start op", pid = 7, tid = 11),
                entry(base + 1, "10:00:00.%03d".format(i * 100 + 50), "Consumer", "handle op", pid = 7, tid = 11),
            )
        }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val producerId = doc.lifelines.single { it.name == "Producer" }.id
        val consumerId = doc.lifelines.single { it.name == "Consumer" }.id
        val message = doc.messages.single { it.fromLifelineId == producerId }

        assertEquals(consumerId, message.toLifelineId)
        assertEquals(Seq3State.AUTO, message.state)
        assertEquals(3, message.occurrences.size)
    }

    @Test
    fun sharedCorrelationTokenInfersTheAdjacentTagAsTargetWithoutThreadIdentity() {
        val token = "abcdefghij"
        val entries = (0 until 3).flatMap { i ->
            val base = i * 2 + 1
            listOf(
                entry(base, "10:00:00.%03d".format(i * 100), "Client", "requestId=$token start"),
                entry(base + 1, "10:00:00.%03d".format(i * 100 + 50), "Server", "requestId=$token finish"),
            )
        }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val clientId = doc.lifelines.single { it.name == "Client" }.id
        val serverId = doc.lifelines.single { it.name == "Server" }.id
        val message = doc.messages.single { it.fromLifelineId == clientId }

        assertEquals(serverId, message.toLifelineId)
    }

    // ── WP5: callback/listener registration evidence ────────────────────────────────────────────

    @Test
    fun callbackRegistrationEvidenceResolvesATargetThreadAndTokenSignalsAloneWouldMiss() {
        // ButtonView registers a click listener once; three unrelated touch events each
        // immediately precede a ButtonView onClick firing. pid/tid are left at their zero default
        // (no thread handoff) and no message carries a correlation token — the ONLY signal linking
        // Touch to ButtonView is the callback registration/firing pair.
        val entries = listOf(
            entry(1, "10:00:00.000", "ButtonView", "setOnClickListener(handler)"),
            entry(2, "10:00:01.000", "Touch", "touch down"),
            entry(3, "10:00:02.000", "ButtonView", "onClick(view)"),
            entry(4, "10:00:03.000", "Touch", "touch down"),
            entry(5, "10:00:04.000", "ButtonView", "onClick(view)"),
            entry(6, "10:00:05.000", "Touch", "touch down"),
            entry(7, "10:00:06.000", "ButtonView", "onClick(view)"),
        )
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val touchId = doc.lifelines.single { it.name == "Touch" }.id
        val buttonId = doc.lifelines.single { it.name == "ButtonView" }.id
        val message = doc.messages.single { it.fromLifelineId == touchId }

        assertEquals(buttonId, message.toLifelineId, "a prior setOnClickListener registration plus a firing onClick must resolve the target")
        assertEquals(Seq3State.AUTO, message.state)
    }

    @Test
    fun callbackRegistrationEvidenceBelowTheConfidenceRatioLeavesTheTargetNull() {
        // Two registrars (ButtonView, Other), each firing back exactly once — a 1-vs-1 split of
        // callback-evidenced candidates, below TARGET_CONFIDENCE_RATIO (0.6).
        val entries = listOf(
            entry(1, "10:00:00.000", "ButtonView", "setOnClickListener(handler)"),
            entry(2, "10:00:01.000", "Other", "registerCallback(x)"),
            entry(3, "10:00:02.000", "Touch", "touch down"),
            entry(4, "10:00:03.000", "ButtonView", "onClick(view)"),
            entry(5, "10:00:04.000", "Touch", "touch down"),
            entry(6, "10:00:05.000", "Other", "onSomethingHappened(x)"),
        )
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val touchId = doc.lifelines.single { it.name == "Touch" }.id
        val message = doc.messages.single { it.fromLifelineId == touchId }

        assertEquals(null, message.toLifelineId, "a 1-vs-1 split of evidenced candidates must not clear the confidence ratio")
        assertEquals(Seq3State.NEEDS_TARGET, message.state)
    }

    @Test
    fun callbackRegistrationEvidenceIsSkippedWhenTheOptionIsDisabled() {
        val entries = listOf(
            entry(1, "10:00:00.000", "ButtonView", "setOnClickListener(handler)"),
            entry(2, "10:00:01.000", "Touch", "touch down"),
            entry(3, "10:00:02.000", "ButtonView", "onClick(view)"),
            entry(4, "10:00:03.000", "Touch", "touch down"),
            entry(5, "10:00:04.000", "ButtonView", "onClick(view)"),
            entry(6, "10:00:05.000", "Touch", "touch down"),
            entry(7, "10:00:06.000", "ButtonView", "onClick(view)"),
        )
        val doc = generateSeq3(entries, Seq3Range.VisibleView, Seq3GenerateOptions(callbackInferenceEnabled = false))

        val touchId = doc.lifelines.single { it.name == "Touch" }.id
        val message = doc.messages.single { it.fromLifelineId == touchId }

        assertEquals(null, message.toLifelineId, "turning the signal off must not resolve a target the callback heuristic alone would find")
    }

    @Test
    fun anUninferableTargetIsNullAndFabricatesNoLifeline() {
        val entries = listOf(
            entry(1, "10:00:00.000", "Lonely", "hello"),
            entry(2, "10:00:05.000", "Other", "unrelated, far in time"),
        )
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val message = doc.messages.single { it.fromLifelineId == doc.lifelines.single { l -> l.name == "Lonely" }.id }
        assertEquals(null, message.toLifelineId)
        assertEquals(Seq3State.NEEDS_TARGET, message.state)
        assertEquals(2, doc.lifelines.size, "no phantom lifeline may be created for the unresolved target")
    }

    @Test
    fun idsRangeIsOrderIndependentAndHonorsAnExactSelection() {
        val entries = (1..5).map { entry(it, "10:00:00.%03d".format(it), "A", "line $it") }

        val swapped = generateSeq3(entries, Seq3Range.Ids(from = 4, to = 2))
        assertEquals(setOf(2, 3, 4), swapped.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())

        val exact = generateSeq3(entries, Seq3Range.Ids(from = 0, to = 0, selectedIds = setOf(1, 5)))
        assertEquals(setOf(1, 5), exact.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())
    }

    @Test
    fun timeRangeCarriesForwardAnUnparseableTimestampFromThePreviousRow() {
        val entries = listOf(
            entry(1, "10:00:00.000", "A", "in range start"),
            entry(2, "", "A", "brief-format row, inherits row 1's timestamp"),
            entry(3, "10:00:10.000", "A", "well past the window"),
        )
        val doc = generateSeq3(entries, Seq3Range.Time("09:59:59.000", "10:00:01.000"))

        assertEquals(setOf(1, 2), doc.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())
    }

    @Test
    fun aMalformedTimeRangeBoundDegradesToAnEmptyDocumentInsteadOfThrowing() {
        val entries = listOf(entry(1, "10:00:00.000", "A", "line"))
        val doc = generateSeq3(entries, Seq3Range.Time("not-a-time", "10:00:01.000"))

        assertTrue(doc.messages.isEmpty())
        assertTrue(doc.lifelines.isEmpty())
    }

    @Test
    fun freshlyGeneratedMessagesDefaultToEveryOccurrenceNotCollapsed() {
        // "As they are, not grouped" — a freshly generated diagram must draw every occurrence as
        // its own arrow by default; collapsing behind a ×n badge is an opt-in Inspector choice.
        val entries = (1..5).map { entry(it, "10:00:00.%03d".format(it), "A", "repeated line") }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)
        assertTrue(doc.messages.isNotEmpty())
        assertTrue(doc.messages.all { it.repeat == Seq3Repeat.EVERY })
    }

    @Test
    fun cancellationCheckAbortsGeneration() {
        val entries = (1..50).map { entry(it, "10:00:00.%03d".format(it % 1000), "A", "line $it") }

        assertFailsWith<IllegalStateException> {
            generateSeq3(
                entries,
                Seq3Range.VisibleView,
                Seq3GenerateOptions(),
                cancellationCheck = { throw IllegalStateException("cancelled") },
            )
        }
    }

    // ── addSeq3MessageFromSelection (queue panel's "＋ Add") ────────────────────────────────────

    @Test
    fun emptySelectionIsRejected() {
        val doc = generateSeq3(listOf(entry(1, "10:00:00.000", "A", "line")), Seq3Range.VisibleView)
        val result = addSeq3MessageFromSelection(doc, emptyList())
        assertTrue(result is Seq3AddResult.Rejected)
        assertEquals("Select at least one log row", result.reason)
    }

    @Test
    fun mixedTagSelectionIsRejectedWithTheExactReason() {
        val doc = emptyDocument()
        val selection = listOf(entry(1, "10:00:00.000", "A", "line one"), entry(2, "10:00:00.100", "B", "line two"))
        val result = addSeq3MessageFromSelection(doc, selection)
        assertTrue(result is Seq3AddResult.Rejected)
        assertEquals("Select rows from a single tag", result.reason)
    }

    @Test
    fun singleTagSelectionAddsOneNeedsTargetMessageAndCreatesALifeline() {
        val doc = emptyDocument()
        val selection = listOf(entry(10, "10:00:00.000", "NewTag", "hello world"))
        val result = addSeq3MessageFromSelection(doc, selection)
        val added = result as? Seq3AddResult.Added ?: error("expected Added, got $result")

        val lifeline = added.document.lifelines.single { it.name == "NewTag" }
        val message = added.document.messages.single { it.id == added.newMessageId }
        assertEquals(lifeline.id, message.fromLifelineId)
        assertEquals(null, message.toLifelineId, "a row selection has no adjacent-entry evidence to infer a target from")
        assertEquals(Seq3State.NEEDS_TARGET, message.state)
        assertEquals(setOf(10), message.occurrences.map { it.entryId }.toSet())
    }

    @Test
    fun selectingRowsForATagThatAlreadyHasALifelineReusesItInsteadOfDuplicating() {
        val existing = emptyDocument().copy(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0)),
        )
        val selection = listOf(entry(1, "10:00:00.000", "A", "line one"))
        val result = addSeq3MessageFromSelection(existing, selection) as? Seq3AddResult.Added ?: error("expected Added")

        assertEquals(1, result.document.lifelines.size, "must reuse the existing lifeline, never duplicate it")
        assertEquals("A", result.document.messages.single().fromLifelineId)
    }

    @Test
    fun newMessageIdNeverCollidesWithAnExistingOne() {
        val entries = (1..3).map { entry(it, "10:00:00.%03d".format(it), "A", "line $it") }
        val generated = generateSeq3(entries, Seq3Range.VisibleView) // produces "msg-1"
        val selection = listOf(entry(10, "10:00:01.000", "B", "extra"))
        val result = addSeq3MessageFromSelection(generated, selection) as? Seq3AddResult.Added ?: error("expected Added")

        assertTrue(result.newMessageId !in generated.messages.map { it.id })
        assertTrue(result.document.messages.any { it.id == result.newMessageId })
    }

    private fun emptyDocument() = Seq3Document()

    // ── Section 3 (post-ship plan): shape-key grouping must survive short mixed-alnum ids ────────
    //
    // Root cause, confirmed here (not from static reading alone — see the plan's own step 0):
    // `messageShapeKey`'s old digit mask was `\b\d+\b`-only. `\b` fires only at a transition between
    // a "word" char (letter/digit/underscore) and a non-word char, so a digit embedded inside a
    // longer alnum run (a USB device handle like "1a2b") never had a boundary to mask on either
    // side and stayed literal. Every occurrence then produced a DIFFERENT masked shape, so
    // `groupByShape` never even offered the tokenizer a chance to prove one shared pattern across
    // them — each became its own single-occurrence Seq3Message, sorted into the document by its own
    // exact timestamp and interleaved with an unrelated tag's messages instead of staying one
    // grouped repeat (the reported "canvas arrows for a repeated message look wrong").

    @Test
    fun nearIdenticalOccurrencesWithShortMixedAlnumIdsStayOneMessageInsteadOfFragmenting() {
        val usbDeviceIds = listOf("1a2b", "3c4d", "5e6f", "7890", "a1b2")
        val usb = usbDeviceIds.mapIndexed { i, devId ->
            entry(i * 2 + 1, "10:00:00.%03d".format(i * 10), "Usb", "usb poll tick from $devId")
        }
        // An unrelated tag firing at timestamps interleaved between the USB bursts — before the fix
        // this tag's single message split the fragmented USB rows apart in `doc.messages`' sort.
        val cpu = (0 until 4).map { i -> entry(i * 2 + 2, "10:00:00.%03d".format(i * 10 + 5), "Cpu", "cpu idle") }

        val doc = generateSeq3(usb + cpu, Seq3Range.VisibleView)

        val usbLifeline = doc.lifelines.single { it.name == "Usb" }
        val usbMessages = doc.messages.filter { it.fromLifelineId == usbLifeline.id }
        assertEquals(1, usbMessages.size, "the five near-identical USB occurrences must merge into one message, not fragment")
        assertEquals(usbDeviceIds.size, usbMessages.single().occurrences.size)
        assertEquals("usb poll tick from {id}", usbMessages.single().match.template)

        // Exactly one Cpu message (its own occurrences already merge on their own, unaffected by
        // this fix) and it must NOT sit between two halves of the now-unfragmented Usb message.
        assertEquals(2, doc.messages.size)
    }

    // ── W1a: document-wide occurrence budget (see docs/plans/prepare-plan-to-fix-binary-wreath.md) ──

    @Test
    fun aFiftyThousandEntryRangeStaysWithinTheHeaderBoundAndReportsItsTrueOccurrenceCount() {
        // One tag, one shape (only the digit run varies) — groupByShape/the tokenizer merge every
        // occurrence into ONE Seq3Message, exactly the shape that used to blow past
        // MAX_SEQ3_HEADER_CHARS before W1a (2 000 occurrences already measured at 671 KB, well over
        // the 512 KB bound; 50 000 measured at 16.9 MB in the pre-fix probe).
        val entryCount = 50_000
        val entries = (1..entryCount).map { i -> entry(i, formatMillisOfDay(i.toLong()), "A", "processed item $i of batch") }

        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val message = doc.messages.single()
        assertEquals(entryCount, message.totalOccurrenceCount, "must report the TRUE pre-trim count, not the trimmed one")
        assertTrue(message.occurrences.size < entryCount, "the water-fill budget must have actually trimmed something")

        val encoded = encodeSeq3Note(doc)
        assertTrue(encoded.length < MAX_SEQ3_HEADER_CHARS, "encoded note must stay under the codec's own decode bound")
        val parsed = assertNotNull(parseSeq3Note(encoded), "an in-budget note must round-trip back through parseSeq3Note")
        assertEquals(entryCount, parsed.document.messages.single().totalOccurrenceCount)
    }

    // ── W1a follow-up: byte-aware budget (occurrence COUNT alone is not the actual header cost) ──

    @Test
    fun rangesWithLongOrdinaryLogLinesStayWithinTheHeaderBoundInsteadOfOnlyBeingCappedByCount() {
        // Reproduces the exact gap a count-only budget left open: at this size (1 000 entries, 5
        // tags, 600-char lines — a stack trace, a JSON payload, a dumpsys dump, all ordinary
        // logcat content) each tag's ~200 occurrences fit comfortably under the OLD count-only
        // allowance (1 200 / 5 messages = 240 per message, so nothing was even trimmed) and STILL
        // encoded to ~795 KB, well over MAX_SEQ3_HEADER_CHARS (measured against the pre-fix
        // algorithm). The byte-aware budget must trim where the count-only one wouldn't, so the
        // header still fits.
        val tagCount = 5
        val tags = (0 until tagCount).map { ('A' + it).toString() }
        val entries = (1..1_000).map { i ->
            val tagIdx = i % tagCount
            entry(i, formatMillisOfDay(i.toLong()), tags[tagIdx], paddedMessage(i, LONG_LINE_LENGTH), pid = 100 + tagIdx, tid = 200 + tagIdx)
        }

        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        assertTrue(doc.messages.isNotEmpty())
        val encoded = encodeSeq3Note(doc)
        assertTrue(
            encoded.length < MAX_SEQ3_HEADER_CHARS,
            "encoded note (${encoded.length} chars) must stay under the codec's own decode bound for ordinary long lines",
        )
        assertNotNull(parseSeq3Note(encoded), "an in-budget note must round-trip back through parseSeq3Note")
    }

    // ── P3a: document-wide message-count cap (see docs/plans/prepare-plan-to-fix-binary-wreath.md) ──

    @Test
    fun aRangeOfAllDissimilarLinesStaysWithinTheHeaderBoundAndReportsItsTrueMessageCount() {
        // Reproduces the exact gap MAX_SEQ3_MESSAGES (decode-only) left open: every one of these
        // 1 000 lines masks to its own distinct shape (a non-digit "word" varies per entry, so
        // messageShapeKey never collapses two of them together), so `buildMessages`' per-entry
        // fallback emits ONE single-occurrence message per entry — exactly the shape W1a's own
        // occurrence budget cannot trim (trimSeq3MessageOccurrences returns early at <=
        // SEQ3_MIN_OCCURRENCES_PER_MESSAGE). Before P3a this produced 1 000 messages and an encoded
        // note several times over MAX_SEQ3_HEADER_CHARS.
        val entryCount = 1_000
        val entries = (1..entryCount).map { i -> entry(i, formatMillisOfDay(i.toLong()), "A", dissimilarMessage(i, LONG_LINE_LENGTH)) }

        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        assertTrue(doc.messages.size < entryCount, "the message-count cap must have actually dropped something")
        assertEquals(
            entryCount - doc.messages.size,
            doc.elidedMessageCount,
            "elidedMessageCount must report the TRUE number of messages the cap dropped",
        )
        val encoded = encodeSeq3Note(doc)
        assertTrue(
            encoded.length < MAX_SEQ3_HEADER_CHARS,
            "encoded note (${encoded.length} chars) must stay under the codec's own decode bound for an all-dissimilar range",
        )
        val parsed = assertNotNull(parseSeq3Note(encoded), "an in-budget note must round-trip back through parseSeq3Note")
        assertEquals(doc.elidedMessageCount, parsed.document.elidedMessageCount, "the elided count itself must round-trip")
    }

    @Test
    fun messageCountCapPrefersTheMostRepeatedMessagesOverSingleOccurrenceNoise() {
        // A handful of genuinely repeated calls (real signal) plus far more than the cap's worth of
        // single-occurrence noise (the exact shape of `buildMessages`' fallback). The cap must keep
        // the repeated calls — the more useful thing to show on a canvas — rather than dropping them
        // in favor of whichever noise happened to sort first.
        val repeatedTags = (0 until 3).map { "Repeated$it" }
        val repeated = repeatedTags.flatMapIndexed { tagIdx, tag ->
            (0 until 20).map { occ ->
                entry(
                    tagIdx * 1_000 + occ + 1, formatMillisOfDay((tagIdx * 1_000 + occ).toLong()), tag,
                    "steady poll tick $occ",
                )
            }
        }
        val noiseCount = 500
        val noise = (0 until noiseCount).map { i ->
            entry(10_000 + i, formatMillisOfDay((10_000 + i).toLong()), "Noise", dissimilarMessage(i, 80))
        }

        val doc = generateSeq3(repeated + noise, Seq3Range.VisibleView)

        repeatedTags.forEach { tag ->
            val lifeline = doc.lifelines.singleOrNull { it.name == tag }
            assertNotNull(lifeline, "$tag must still rank as a lifeline")
            val message = doc.messages.singleOrNull { it.fromLifelineId == lifeline.id }
            assertNotNull(message, "$tag's repeated message must survive the message-count cap")
        }
    }

    /** Distinct per-entry, non-digit "word" so messageShapeKey never masks two entries to a shared
     *  placeholder — every entry lands in its own shape group, so `buildMessages`' fallback emits
     *  one single-occurrence message per entry, exactly [SEQ3_MAX_MESSAGES]'s own reproduction case. */
    private fun dissimilarMessage(i: Int, targetLen: Int): String {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val word = buildString {
            var n = i + 1
            while (n > 0) {
                append(letters[n % letters.length])
                n /= letters.length
            }
        }
        val head = "event kind $word detail "
        return head + "x".repeat((targetLen - head.length).coerceAtLeast(0))
    }

    // ── PERF regression: NAMED_VALUE's O(tail length²) backtracking fix (Seq3Tokenizer.kt) ──────

    @Test
    fun generatingOverManyDelimiterlessTailLinesStaysWellUnderTheQuadraticBlowupThreshold() {
        // Pins the fix at the level a caller actually feels it. This exact shape — a short
        // "key=value" prefix followed by ~580 characters of unbroken, delimiter-less text — is
        // precisely what made Seq3Tokenizer.kt's NAMED_VALUE regex quadratic before the fix
        // (measured directly against the isolated pattern: 9.2s total for this size on the
        // unbounded pattern; see that file's own comment for the full per-length table). The
        // threshold is deliberately loose — not a tight millisecond bound — so this stays robust on
        // a slow or loaded CI machine; the point being pinned is "not quadratic," not "exactly this
        // fast."
        val tagCount = 5
        val tags = (0 until tagCount).map { ('A' + it).toString() }
        val entries = (1..1_000).map { i ->
            val tagIdx = i % tagCount
            entry(
                i, formatMillisOfDay(i.toLong()), tags[tagIdx], delimiterlessTailMessage(i, LONG_LINE_LENGTH),
                pid = 100 + tagIdx, tid = 200 + tagIdx,
            )
        }

        val elapsedMs = measureTimeMillis { generateSeq3(entries, Seq3Range.VisibleView) }

        assertTrue(
            elapsedMs < PERF_REGRESSION_THRESHOLD_MS,
            "generateSeq3 took ${elapsedMs}ms over 1000 delimiterless-tail lines " +
                "(threshold ${PERF_REGRESSION_THRESHOLD_MS}ms) — NAMED_VALUE's bounding may have regressed",
        )
    }

    /** A short "key=value" prefix followed by an unbroken (no spaces, no '='/':' ) run of text —
     *  the exact shape that turned NAMED_VALUE.findAll quadratic before the fix: a value followed
     *  by a long tail with no further delimiter forces the lookahead to scan the whole tail
     *  looking for the next pair, over and over, from every position findAll tries. */
    private fun delimiterlessTailMessage(i: Int, targetLen: Int): String {
        val head = "token=v%05d ".format(i)
        val tailLen = (targetLen - head.length).coerceAtLeast(0)
        val tail = buildString { while (length < tailLen) append("detail") }.take(tailLen)
        return head + tail
    }

    /** Fixed-length synthetic log line with a FIXED-WIDTH (zero-padded) counter, so total message
     *  length — and therefore its masked shape — never depends on how many digits the counter
     *  itself has: groupByShape/the tokenizer merge every occurrence of a tag into one
     *  [Seq3Message] regardless of how long [targetLen] makes the line, exactly like a real
     *  repeated call whose payload differs in length-irrelevant detail (a changing id, a changing
     *  timestamp embedded in a dump). */
    private fun paddedMessage(i: Int, targetLen: Int): String {
        val head = "processed item %05d of batch ".format(i)
        return head + "y".repeat((targetLen - head.length).coerceAtLeast(0))
    }

    private fun formatMillisOfDay(millis: Long): String {
        val hh = millis / MILLIS_PER_HOUR
        val mm = (millis % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
        val ss = (millis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        val ms = millis % MILLIS_PER_SECOND
        return "%02d:%02d:%02d.%03d".format(hh, mm, ss, ms)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_HOUR = 3_600_000L
        const val LONG_LINE_LENGTH = 600
        const val PERF_REGRESSION_THRESHOLD_MS = 2_000L
    }
}
