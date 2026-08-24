package com.indagium

import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3TokenizeInput
import com.indagium.diagram3.compileNamedValuePattern
import com.indagium.diagram3.isGenericValueSet
import com.indagium.diagram3.matchesText
import com.indagium.diagram3.sanitizeCaptureName
import com.indagium.diagram3.tokenizeSeq3Messages
import com.indagium.diagram3.tokenizeSeq3MessagesFullPath
import com.indagium.diagram3.unquote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3TokenizerTest {
    private fun input(id: Int, text: String) = Seq3TokenizeInput(id.toString(), text)

    @Test
    fun nearIdenticalNamedValueTextsCollapseToOneMatchWithATokenNamedFromTheKey() {
        val result = tokenizeSeq3Messages(
            "UsbEventMonitor",
            listOf(
                input(1, "attach deviceKey=usb-dev-016"),
                input(2, "attach deviceKey=usb-dev-017"),
                input(3, "attach deviceKey=usb-dev-018"),
            ),
        )

        assertTrue(result.compiled, result.error)
        val match = result.match!!
        assertEquals("attach deviceKey={deviceKey}", match.template)
        assertEquals(listOf(Seq3CaptureSource.NAMED_VALUE), match.captures.map { it.source })
        assertEquals("usb-dev-016", result.captureValuesByOccurrence.getValue("1").getValue("deviceKey"))
        assertEquals("usb-dev-018", result.captureValuesByOccurrence.getValue("3").getValue("deviceKey"))
    }

    @Test
    fun aSingleOccurrenceYieldsALiteralMatchWithNoCaptures() {
        val result = tokenizeSeq3Messages("A", listOf(input(1, "device connected id=42")))

        assertTrue(result.compiled, result.error)
        val match = result.match!!
        assertEquals("device connected id=42", match.template)
        assertTrue(match.captures.isEmpty())
        assertEquals(mapOf("1" to emptyMap<String, String>()), result.captureValuesByOccurrence)
    }

    @Test
    fun tokenNamesAreStableAndDeterministicAcrossRepeatedRuns() {
        val occurrences = listOf(input(1, "push seq 4821"), input(2, "push seq 91"), input(3, "push seq 500217"))

        val first = tokenizeSeq3Messages("Pusher", occurrences)
        val second = tokenizeSeq3Messages("Pusher", occurrences)

        assertTrue(first.compiled, first.error)
        assertEquals(first.match, second.match, "the same input set must tokenize to the exact same template every time")
        assertEquals("push seq {n}", first.match!!.template, "an all-digit varying run gets the stable generated name 'n'")
    }

    @Test
    fun aHexIshVaryingRunGetsTheStableGeneratedNameId() {
        // No '=' here on purpose: a key=value run is captured under its OWN key name (see the
        // deviceKey test above) — this exercises the ANONYMOUS positional-run path instead, where
        // the generated name must come from the captured content's own shape.
        val result = tokenizeSeq3Messages(
            "A",
            listOf(input(1, "resolved token deadbeef01"), input(2, "resolved token cafebabe99")),
        )

        assertTrue(result.compiled, result.error)
        assertEquals("resolved token {id}", result.match!!.template)
    }

    @Test
    fun theDenylistKeepsAGenericNamedValueFromBecomingItsOwnNamedCapture() {
        // 'connected' only ever varies between denylisted generic words (true/false) — the
        // generator must not merge these into a noisy, mistaken {connected} token. It may still
        // fall back to capturing the differing text anonymously (see the positional-run path
        // above); what matters is that the named key itself never becomes the capture's name.
        val result = tokenizeSeq3Messages("A", listOf(input(1, "connected=true"), input(2, "connected=false")))

        assertTrue(result.compiled, result.error)
        assertFalse("connected" in result.match!!.captures.map { it.name }, "a generic true/false split must not become a {connected} token")
    }

    @Test
    fun matchesTextRoundTripsCapturedValuesAndRejectsANonMatchingText() {
        val result = tokenizeSeq3Messages("A", listOf(input(1, "fetch id=1"), input(2, "fetch id=2")))
        val match = result.match!!

        assertEquals(mapOf("id" to "3"), matchesText(match, "fetch id=3"))
        assertNull(matchesText(match, "totally different text"))
    }

    @Test
    fun structurallyIncompatibleOccurrencesFailToCompile() {
        val result = tokenizeSeq3Messages(
            "A",
            listOf(input(1, "alpha 1 beta 2"), input(2, "gamma 3 delta 4")),
        )

        assertFalse(result.compiled)
        assertEquals(null, result.match)
    }

    // ── Item 9 (WP9) — two silent data-loss bugs found during exploration ──────────────────────

    @Test
    fun quotedEmptyNamedValueIsNeverCapturedAsEmpty() {
        // Before the fix, this compiled via the NAMED_VALUE path with template "state: {state}"
        // and an unquoted, EMPTY captured value for occurrence 1 -- which then substitutes back to
        // nothing (`state: `, no visible marker the value was ever captured). The fix disqualifies
        // an empty unquoted value from becoming a capture at all, so this must now fall back to the
        // single-run path, whose raw (non-unquoted) captured value is never empty.
        val result = tokenizeSeq3Messages("A", listOf(input(1, "state: \"\""), input(2, "state: on")))

        assertTrue(result.compiled, result.error)
        val captured = result.captureValuesByOccurrence.values.flatMap { it.values }
        assertTrue(captured.isNotEmpty(), "expected at least one captured value")
        assertTrue(captured.none { it.isEmpty() }, "a quoted-empty named value must never surface as an empty capture; got $captured")
    }

    @Test
    fun aQuotedEmptyValueOnBothSidesFailsToCompileRatherThanCapturingEmpty() {
        // Both occurrences are quoted-empty-vs-quoted-non-empty on the SAME literal quote
        // boundary, so even the single-run fallback's own empty-middle guard rejects it. Failing
        // to compile (and falling back to one literal message per occurrence upstream) is the
        // honest outcome -- never a silently empty capture.
        val result = tokenizeSeq3Messages("A", listOf(input(1, "state: \"\""), input(2, "state: \"on\"")))

        val captured = result.captureValuesByOccurrence.values.flatMap { it.values }
        assertTrue(captured.none { it.isEmpty() }, "must never surface an empty capture even when compilation itself fails; got $captured")
    }

    @Test
    fun aDottedNamedKeySanitizesToAValidCaptureTokenAndRoundTrips() {
        // NAMED_VALUE's key charset allows '.'/'-' but CAPTURE_TOKEN does not -- an unsanitized
        // "{screen.mode}" token would be invisible to seq3CaptureTokenNames, failing matchesText
        // for every occurrence and silently degrading the whole group to per-occurrence literals.
        val result = tokenizeSeq3Messages("A", listOf(input(1, "screen.mode=on"), input(2, "screen.mode=off")))

        assertTrue(result.compiled, result.error)
        val match = result.match!!
        assertEquals(listOf("screen_mode"), match.captures.map { it.name })
        assertEquals(
            mapOf("screen_mode" to "loud"),
            matchesText(match, "screen.mode=loud"),
            "the sanitized token must round-trip through matchesText, not silently fail every occurrence",
        )
    }

    @Test
    fun distinctKeysThatSanitizeToTheSameNameDoNotCollide() {
        val result = tokenizeSeq3Messages(
            "A",
            listOf(
                input(1, "screen.mode=on screen-mode=1"),
                input(2, "screen.mode=off screen-mode=2"),
            ),
        )

        assertTrue(result.compiled, result.error)
        val names = result.match!!.captures.map { it.name }
        assertEquals(names.size, names.distinct().size, "sanitized capture names must stay unique even when two raw keys collide after sanitizing; got $names")
    }

    // ── PERF: NAMED_VALUE bounding must not change what it extracts ────────────────────────────
    //
    // Seq3Tokenizer.kt's NAMED_VALUE gained possessive, length-capped quantifiers to fix an O(tail
    // length²) backtracking blowup (see that file's own comment on the pattern for the full
    // diagnosis and measured numbers). The OLD/NEW patterns below are byte-for-byte copies of the
    // pre-fix and post-fix regex source — duplicated here, not imported, because NAMED_VALUE is
    // file-private — so this test can prove the bound changed nothing about what a WELL-FORMED
    // line (every real key and value far under the new caps) extracts, across every shape this
    // file's own tests already care about: plain pairs, colon-separated, comma/semicolon-delimited,
    // quoted values with embedded spaces, single-quoted, dotted/hyphenated keys, no pairs at all, a
    // trailing pair with nothing after it, and the specific delimiterless-tail shape that motivated
    // the fix (which the OLD pattern still gets right — it's just slow).
    private val oldNamedValuePattern = Regex(
        "([A-Za-z_][A-Za-z0-9_.-]*)\\s*(=|:)\\s*(\"[^\"]*\"|'[^']*'|[^=,:;\\s)]+)" +
            "(?=\\s+(?:[A-Za-z_][A-Za-z0-9_.-]*\\s*(?:=|:))|\\s*$|[,;)])",
    )
    private val newNamedValuePattern = Regex(
        "([A-Za-z_][A-Za-z0-9_.-]{0,64}+)\\s*+(=|:)\\s*+(\"[^\"]*\"|'[^']*'|[^=,:;\\s)]{1,256}+)" +
            "(?=\\s++(?:[A-Za-z_][A-Za-z0-9_.-]{0,64}+\\s*+(?:=|:))|\\s*+$|[,;)])",
    )

    /** (key, operator, rawValue) triples — everything a consumer of NAMED_VALUE actually reads
     *  (groups 1-3) — for every match [pattern] finds in [text], in order. */
    private fun namedValuePairs(pattern: Regex, text: String): List<Triple<String, String, String>> =
        pattern.findAll(text).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()

    @Test
    fun theBoundedPatternExtractsIdenticalPairsToTheUnboundedOneAcrossARealisticCorpus() {
        val corpus = listOf(
            "attach deviceKey=usb-dev-016",
            "state: on ready: true",
            "push seq=4821, retries=2; final=true",
            "message=\"user said hello world\" priority=high",
            "note='single quoted value here' level=2",
            "screen.mode=on screen-mode=1",
            "just a plain sentence with no pairs at all",
            "final status=done",
            "token=Binder@7 " + "detail".repeat(80),
        )
        for (text in corpus) {
            assertEquals(
                namedValuePairs(oldNamedValuePattern, text),
                namedValuePairs(newNamedValuePattern, text),
                "extraction diverged for: $text",
            )
        }
    }

    // ── PERF: single-occurrence short-circuit must be a no-op on the result (P3b) ──────────────
    //
    // tokenizeSeq3Messages(tag, [one]) now returns from an early branch instead of running
    // compileNamedValuePattern/compileSingleRunPattern at all -- see that branch's own comment for
    // the two independent arguments proving both compilers were always forced onto the identical
    // literal-template answer for a single occurrence anyway. This proves it empirically rather
    // than by argument alone: every corpus line, run through BOTH the short-circuited public
    // function and tokenizeSeq3MessagesFullPath (the never-short-circuiting internal seam), must
    // agree on match (tag/template/captures), captured values, and error.

    @Test
    fun theSingleOccurrenceShortCircuitAgreesWithTheFullCompilePathAcrossARealisticCorpus() {
        val corpus = listOf(
            // Reused verbatim from theBoundedPatternExtractsIdenticalPairsToTheUnboundedOneAcross
            // ARealisticCorpus above -- every shape that file already cares about.
            "attach deviceKey=usb-dev-016",
            "state: on ready: true",
            "push seq=4821, retries=2; final=true",
            "message=\"user said hello world\" priority=high",
            "note='single quoted value here' level=2",
            "screen.mode=on screen-mode=1",
            "just a plain sentence with no pairs at all",
            "final status=done",
            "token=Binder@7 " + "detail".repeat(80),
            // Single-occurrence-specific edge shapes not already covered above.
            "no operator characters anywhere in this whole line at all",
            "device connected id=42",
            "quoted empty value: \"\"",
            "trailing pair with nothing after it key=value",
            "dotted.and-hyphenated.key=value here too",
        )
        for (text in corpus) {
            val short = tokenizeSeq3Messages("A", listOf(input(1, text)))
            val full = tokenizeSeq3MessagesFullPath("A", listOf(input(1, text)))
            assertEquals(full.match, short.match, "match diverged for: $text")
            assertEquals(full.captureValuesByOccurrence, short.captureValuesByOccurrence, "captured values diverged for: $text")
            assertEquals(full.error, short.error, "error diverged for: $text")
            // Pin what the short-circuit is actually claiming, not just that it agrees with itself.
            assertEquals(text, short.match?.template, "a single occurrence must always template to its own literal text: $text")
            assertTrue(short.match?.captures.orEmpty().isEmpty(), "a single occurrence must never produce a capture: $text")
        }
    }

    @Test
    fun theSingleOccurrenceShortCircuitStillRunsTheExistingValidationGuardsFirst() {
        // The short-circuit sits AFTER the blank-id/empty-text guards in source order -- pinned so
        // a future edit can't accidentally move it above them and silently skip validation.
        assertEquals(
            "Every occurrence must have a stable id",
            tokenizeSeq3Messages("A", listOf(Seq3TokenizeInput("", "device connected id=42"))).error,
        )
        assertEquals(
            "Empty occurrence text cannot be tokenized",
            tokenizeSeq3Messages("A", listOf(input(1, ""))).error,
        )
    }

    // ── PERF: compileNamedValuePattern's redundant-recomputation fix must be a no-op (P3b) ─────
    //
    // The BATCHED path (a tag's occurrences share a shape, buildMessages calls tokenizeSeq3Messages
    // once with every occurrence) is the COMMON case -- the whole reason the tokenizer exists is to
    // collapse a shared shape into one message. compileNamedValuePattern used to recompute the same
    // O(occurrences x keys) `valuesFor` scan from scratch at every call site (up to 3x per varying
    // name) and re-ran NAMED_VALUE.findAll over every occurrence a SECOND time when building
    // `values`, even though `matches` already held that exact result. Neither redundancy changed
    // what gets returned -- both are memoize-or-reuse fixes over pure, already-computed values, not
    // logic changes -- so [oldCompileNamedValuePattern] below is a byte-for-byte copy of the
    // PRE-fix algorithm (kept here, not imported, for the same reason
    // theBoundedPatternExtractsIdenticalPairsToTheUnboundedOneAcrossARealisticCorpus above keeps
    // its own old/new regex copies: proving the fix changed nothing needs an independent
    // computation to diff against, not just a second look at the same one). It calls the REAL,
    // unchanged `sanitizeCaptureName`/`isGenericValueSet`/`unquote` -- only the redundant-scan
    // shape of `compileNamedValuePattern` itself is duplicated.

    /** Verbatim pre-PERF-fix `compileNamedValuePattern`: `valuesFor` recomputed from scratch on
     *  every call (no `valuesByName` memoization), and `values` re-ran a NAMED_VALUE findAll per
     *  occurrence instead of reusing `matches`. The tokenizer's real `NAMED_VALUE` regex is
     *  file-private, so this copy drives its matches through [newNamedValuePattern] instead --
     *  already proven byte-for-byte identical to production's `NAMED_VALUE` by
     *  theBoundedPatternExtractsIdenticalPairsToTheUnboundedOneAcrossARealisticCorpus above, so
     *  substituting it here changes nothing about what this duplicate computes. */
    private fun oldCompileNamedValuePattern(
        tag: String,
        occurrences: List<Seq3TokenizeInput>,
    ): Pair<Seq3Match, Map<String, Map<String, String>>>? {
        val matches = occurrences.map { newNamedValuePattern.findAll(it.text).toList() }
        if (matches.any { it.isEmpty() }) return null
        val firstKeys = matches.first().map { it.groupValues[1] }
        if (matches.any { it.map { part -> part.groupValues[1] } != firstKeys }) return null

        fun valuesFor(name: String): Set<String> =
            matches.map { parts -> unquote(parts.first { part -> part.groupValues[1] == name }.groupValues[3]) }.toSet()

        val varyingNames = firstKeys.filter { valuesFor(it).size > 1 }
        if (varyingNames.isEmpty()) {
            val exact = Seq3Match(tag = tag, template = occurrences.first().text)
            return exact to occurrences.associate { it.occurrenceId to emptyMap() }
        }
        if (varyingNames.any { name -> isGenericValueSet(valuesFor(name)) || valuesFor(name).any(String::isEmpty) }) return null

        val usedCaptureNames = mutableSetOf<String>()
        val sanitizedNames = varyingNames.associateWith { sanitizeCaptureName(it, usedCaptureNames) }

        val first = occurrences.first().text
        val replacements = matches.first().mapNotNull { part ->
            val name = part.groupValues[1]
            name.takeIf { it in varyingNames }?.let { part.groups[3]!!.range to "{${sanitizedNames.getValue(it)}}" }
        }.sortedByDescending { it.first.first }
        var template = first
        replacements.forEach { (range, replacement) -> template = template.replaceRange(range, replacement) }
        val captures = varyingNames.map { Seq3Capture(sanitizedNames.getValue(it), Seq3CaptureSource.NAMED_VALUE) }
        val values = occurrences.associate { occ ->
            val parts = newNamedValuePattern.findAll(occ.text).toList()
            occ.occurrenceId to varyingNames.associate { name ->
                sanitizedNames.getValue(name) to unquote(parts.first { it.groupValues[1] == name }.groupValues[3])
            }
        }
        return Seq3Match(tag = tag, template = template, captures = captures) to values
    }

    @Test
    fun theRedundantRecomputationFixAgreesWithThePreFixAlgorithmAcrossAGroupedCorpus() {
        val groupedCorpus = listOf(
            "collapses on ONE shared varying key" to listOf(
                input(1, "attach deviceKey=usb-dev-016"),
                input(2, "attach deviceKey=usb-dev-017"),
                input(3, "attach deviceKey=usb-dev-018"),
            ),
            "MULTIPLE named values per line, one of them varying" to listOf(
                input(1, "push seq=4821, retries=2; final=true"),
                input(2, "push seq=91, retries=2; final=true"),
                input(3, "push seq=500217, retries=2; final=true"),
            ),
            "a quoted value alongside a varying one" to listOf(
                input(1, "message=\"hello world\" priority=high seq=1"),
                input(2, "message=\"hello world\" priority=high seq=2"),
            ),
            "a dotted/hyphenated key that must sanitize" to listOf(
                input(1, "screen.mode=on screen-mode=1"),
                input(2, "screen.mode=off screen-mode=2"),
            ),
            "no variation at all (every value identical)" to listOf(
                input(1, "state: on ready: true"),
                input(2, "state: on ready: true"),
            ),
            // The two cases the algorithm deliberately bails on (returns null) -- must stay null.
            "bails: the varying value is generic-only (true/false)" to listOf(
                input(1, "connected=true"),
                input(2, "connected=false"),
            ),
            "bails: a quoted-empty value among the varying ones" to listOf(
                input(1, "state: \"\""),
                input(2, "state: on"),
            ),
        )
        for ((label, occurrences) in groupedCorpus) {
            val before = oldCompileNamedValuePattern("A", occurrences)
            val after = compileNamedValuePattern("A", occurrences)
            assertEquals(before?.first, after?.first, "match diverged for [$label]")
            assertEquals(before?.second, after?.second, "captured values diverged for [$label]")
            assertEquals(before == null, after == null, "null (bail-out) vs non-null diverged for [$label]")
        }
    }
}
