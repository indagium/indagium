# Sequence Diagram Inference Improvements — Luna Execution Plan

## Purpose and outcome

Implement the full sequence-diagram inference improvement set on a new branch named:

```text
feat/fixes_and_sequence_diagramm_generation_12_08_26
```

The outcome is a diagram generator that is useful without manual configuration, stays honest about uncertain evidence, and recognises common Android/Kotlin callback patterns. The implementation must preserve explicit user configuration and existing persisted diagram notes.

The current code intentionally falls back to `SELF` events in `ArrowMode.EVIDENCE_FLOW` unless it has explicit actor, rule, same-thread, or source-trace evidence. This plan changes the default experience without reintroducing arbitrary tag-transition arrows.

## Non-negotiable rules

- Work from the current checked-out branch; first create and switch to the target branch above.
- Use IDEA MCP for project search, editing, compilation, inspections, and test execution.
- Do not push, reset, or discard unrelated work.
- Preserve the existing non-zero PID/TID and 250 ms time-gap guards. Missing thread data must never create a handoff arrow.
- Preserve explicit actors, rules, manual diagrams, message overrides, and persisted option values over new inferred defaults.
- The source index remains lightweight text/brace-based analysis: do not introduce a compiler, PSI dependency, or broad parser rewrite.
- All new persisted index semantics require a `SOURCE_INDEX_VERSION` bump. Diagram-note fields remain optional and backward compatible.
- Keep source inference bounded and cancellation-aware.

## Architecture and contracts

### Diagram defaults and evidence provenance

Update `DiagramOptions` and diagram generation so that:

1. `threadHandoffArrows` defaults to `true`.
2. `labelSource` defaults to `BOTH`.
3. A generated `Caller` actor opens an inferred `EVIDENCE_FLOW` diagram only when all are true:
   - authoring mode is inferred;
   - no explicit entry-point actor is configured;
   - the diagram is not using a complete source trace;
   - at least one represented entry has a participant.
4. The generated caller is transient output state. It must not be inserted into `SeqDiagramSpec.participants`, diagram notes, or manual documents.
5. Add `MessageEvidence.CORRELATION_TOKEN` at the end of the enum. It denotes a log-only cross-lifeline arrow based on a high-confidence shared token.

`resolveEvidenceEdge` becomes the single decision point for the fallback order:

```text
explicit entry actor or transient Caller for first event
→ same-PID/TID handoff (when valid)
→ high-confidence token correlation to the immediately preceding represented event on another lifeline
→ SELF
```

Thread handoff takes precedence over token correlation. A token arrow must have `MessageKind.CALL`, `MessageEvidence.CORRELATION_TOKEN`, and preserve the entry as a primary log event.

### High-confidence correlation tokens

Implement a pure helper in the diagram package, called from `SeqDiagramBuilder`; it must not change log parsing or source indexing.

Accepted token formats, case-insensitively where applicable:

- UUID: `8-4-4-4-12` hexadecimal format;
- compact hex trace ID of at least 16 hexadecimal characters;
- named identifier values with a recognised key: `requestId`, `request_id`, `sessionId`, `session_id`, `traceId`, `trace_id`, `correlationId`, `correlation_id`, or `spanId`; allow `=` or `:` separators and quoted/unquoted values.

Required rejection rules:

- Ignore numeric-only values.
- Ignore values shorter than 8 characters unless they are UUID segments (which still will not qualify alone).
- Ignore generic words and unnamed `id`/`ID` values.
- Ignore a candidate when it occurs in only one of the adjacent messages.
- Ignore correlation on the same resolved lifeline; it is already a self-event.
- Use the same 250 ms parsed-timestamp bound as thread handoff; missing/unparseable timestamps do not correlate.

Use a normalised value as the comparison key. If several candidates match, choose the longest token; ties are deterministic by lexical order. No correlation history beyond the immediately previous represented entry is permitted in this change.

### Auto-lifeline ranking

Replace the unconfigured tag selection's raw-row-count ranking in `resolveTagParticipants` with a deterministic signal score:

```text
score = 4 × errorCount + 2 × distinctNormalisedMessageTemplateCount + min(entryCount, 10)
```

For normalisation, lower-case, collapse whitespace, replace UUIDs/long hex tokens/numeric runs with placeholders, and retain word structure. Sort by descending score, then descending entry count, then tag name. Keep the existing eight-lifeline cap and `Other` grouping behavior.

Reuse this normalisation in candidate statistics where practical so that the inspector's ranking reflects generation. Do not change user-curated participants/components.

### Partial source traces

The source solver currently returns an empty usable trace when any anchor/path becomes incompatible, and `sourceTraceActive` requires exact all-row coverage. Change this to segment-level recovery:

- Retain a completed, highest-ranked compatible segment when the next anchor cannot be resolved, is ambiguous, or cannot be reached.
- Add diagnostics for the unresolved entry/span with the existing precise `TraceDiagnosticReason` whenever possible.
- Restart candidate search at the next viable anchor, yielding an ordered trace with one or more verified segments.
- Preserve all selected log entries in rendering. Entries outside verified segments remain primary `SELF` events.
- Project verified source calls, returns, and async handoffs as supplementary structure around the relevant primary events; do not convert a source call into the log event itself.
- Add `SourceTraceMode.PARTIAL_SOURCE_TRACE` (or the equivalent existing model extension) so UI/MCP diagnostics distinguish complete source trace, partial trace, fallback, and disabled modes.
- Complete source traces retain current semantic ownership. Partial traces may use the explicit fallback primary events plus only their verified source structure.

Do not treat stale source, low-confidence source, ambiguous anchor, unresolved callee, or branch incompatibility as a reason to discard an earlier verified segment.

### Callback and async source model

Extend the source index to expose callback bodies as call targets.

1. Discover Kotlin trailing-lambda bodies supplied to a direct call and Java/Kotlin anonymous class callback bodies supplied as call arguments.
2. Represent each as a stable synthetic `IndexedSourceMethod`:
   - stable ID derived from file path, body start offset, and registration call identity;
   - lexical owner inherited from the enclosing concrete source type;
   - generated name includes the registration method plus callback role/method where available;
   - source range covers exactly the callback body;
   - source set follows the containing file.
3. Attribute log calls inside synthetic bodies to the synthetic method, not the enclosing named method.
4. Create a resolved `IndexedSourceCall` from the registration/dispatch call to the synthetic method. Registration is async/callback evidence, not a synchronous method call.
5. Keep an anonymous body only when it is directly associated with one indexed call. Do not guess targets from unrelated lambdas.

Broaden `invocationKind` without classifying ordinary business methods as async:

- coroutine launch: `launch`, `async`, `launchIn`, `launchWhen*`, `resume*`;
- executor/dispatch: `submit*`, `execute*`, `post*`, `dispatch*`, `enqueue*`;
- callback registration: names containing `callback` or `listener`, and `register*`, `subscribe*`, `observe*`;
- binder/RPC: existing binder/rpc/transact/call logic remains.

In `SourceTraceInferenceEngine`, asynchronous paths may cross branch operations when locating a registration or dispatch. Synchronous paths must retain the existing straight-line operation proof. Keep the existing single-target requirement for drawing a handoff.

For synthetic callback methods:

- `onSuccess`, `onResult`, `onComplete`, and `onResponse` close the associated async invocation as `RETURNED` and attach runtime callback evidence;
- `onError`, `onFailure`, and `onException` close it as `THREW`;
- completion classification is based on the synthetic method's name and does not require `resultVariable` matching;
- normal synchronous result-variable inference remains unchanged.

## Task sequence

### Task 0 — Branch and baseline

1. Confirm a clean/understood worktree with `git status --short --branch`.
2. Create/switch to `feat/fixes_and_sequence_diagramm_generation_12_08_26`.
3. Use IDEA MCP to run the current focused suites:
   - `DiagramBuilderTest`
   - `SourceTraceInferenceTest`
   - `SourceIndexerTest`
   - `DiagramSpecCodecTest`
   - `SourceIndexStoreTest`
4. Record any pre-existing failures separately; do not mask them.

### Task 1 — Model, codecs, and default readability

Primary files:

- `src/desktopMain/kotlin/com/indagium/diagram/DiagramModel.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/DiagramSpecCodec.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/SeqDiagramBuilder.kt`
- `src/desktopTest/kotlin/com/indagium/DiagramBuilderTest.kt`
- `src/desktopTest/kotlin/com/indagium/DiagramSpecCodecTest.kt`

Implement the new defaults and appended evidence enum. Add transient caller resolution through the local participant registry only. Ensure an explicit entry actor has priority and that a manual diagram never gains a caller.

Add/adjust tests for:

- default same-thread cross-tag handoff;
- default label source `BOTH`, with message fallback when no method resolves;
- caller-to-first-tag arrow when unconfigured;
- explicit entry actor winning over generated caller;
- no generated caller in manual mode or complete source trace;
- zero PID/TID, different PID/TID, and stale timestamp never hand off;
- old note decoding with missing option keys and round-trip persistence of changed defaults.

### Task 2 — Token correlation and lifeline ranking

Primary files:

- `src/desktopMain/kotlin/com/indagium/diagram/SeqDiagramBuilder.kt`
- optionally a new UI-free helper under `diagram/`
- `src/desktopTest/kotlin/com/indagium/DiagramBuilderTest.kt`

Implement the token extractor/correlation helper and invoke it only from fallback evidence flow. Refactor tag statistics/ranking to the score defined above. Keep all helpers deterministic and bounded to per-message strings.

Add tests for:

- UUID, compact hex trace ID, and every named identifier spelling;
- quoted and unquoted named values;
- generic words, ordinary numbers, plain `id=`, unmatched values, same lifeline, and timestamps outside the bound;
- thread evidence precedence when both mechanisms match;
- a low-volume high-signal/error tag retaining a lifeline ahead of a noisy tag;
- deterministic ties and preservation of explicitly selected tags/components.

### Task 3 — Partial source-trace recovery

Primary files:

- `src/desktopMain/kotlin/com/indagium/source/SourceTraceInference.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/DiagramTraceModel.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/SeqDiagramBuilder.kt`
- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramInspector.kt` if trace mode/diagnostics need presentation changes
- `src/desktopTest/kotlin/com/indagium/source/SourceTraceInferenceTest.kt`
- `src/desktopTest/kotlin/com/indagium/DiagramBuilderTest.kt`

Refactor reconstruction around a reusable "finish current segment / emit diagnostic / restart at next anchor" flow. Avoid returning duplicate events and preserve source-order output. Do not make partial trace look complete.

Ensure the builder emits exactly one primary representation for every selected entry, plus verified structural source messages where available. Preserve existing complete-trace behavior and its activation projection.

Add tests for:

- resolved prefix + ambiguous middle + resolved suffix;
- branch-incompatible middle span;
- low-confidence/stale span;
- every selected entry retained once as primary;
- verified calls/returns retained only for verified segments;
- partial mode and diagnostics visible;
- no mixing of unsupported legacy one-hop interactions when a trace resolver is used.

### Task 4 — Synthetic callback methods and async path rules

Primary files:

- `src/desktopMain/kotlin/com/indagium/source/SourceIndexer.kt`
- `src/desktopMain/kotlin/com/indagium/source/SourceModel.kt`
- `src/desktopMain/kotlin/com/indagium/source/SourceIndexStore.kt`
- `src/desktopMain/kotlin/com/indagium/source/SourceTraceInference.kt`
- `src/desktopTest/kotlin/com/indagium/source/SourceIndexerTest.kt`
- `src/desktopTest/kotlin/com/indagium/source/SourceTraceInferenceTest.kt`
- `src/desktopTest/kotlin/com/indagium/source/SourceIndexStoreTest.kt`

First design synthetic method discovery against small source fixtures. Reuse existing code masking, brace matching, line indexing, and direct-call parsing; do not add a compiler parser.

Then:

1. Add synthetic methods before direct-call resolution so they participate in `byOwnerAndName`/method lookup as appropriate.
2. Associate a registration call with exactly one synthetic callback method ID.
3. Ensure extraction assigns callback-body log sites and operations to that synthetic method.
4. Persist synthetic methods/calls/operations through the current line format; bump `SOURCE_INDEX_VERSION` and update compatibility expectations so old indexes rebuild.
5. Relax only asynchronous verification across branches. Keep synchronous `verifiedCallAfter`, `verifiedMethodEntryToLog`, and return proof strict.
6. Implement callback method-name completion/failure classification without weakening synchronous return rules.

Fixture coverage must include:

- Kotlin trailing lambda: `setOnClickListener { Log.d(...) }`;
- Kotlin `api.enqueue(object : Callback<T> { override fun onResponse...; override fun onFailure... })`;
- Java anonymous callback with success and failure methods;
- `postDelayed`, `launchIn`, `launchWhenStarted`, `subscribeOn`, `observeForever`, `enqueueWith`, and `registerReceiver` classification;
- dispatch/registration nested in `if` or `when` with cross-lane follow-up log;
- ordinary synchronous call inside a branch still rejected unless straight-line proof exists;
- source index save/load after a rebuild and stale version returning `null`.

### Task 5 — Integration, diagnostics, and documentation

Primary files:

- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramInspector.kt`
- `src/desktopMain/kotlin/com/indagium/debug/IndagiumToolOperations.kt` if its result contract exposes trace mode/evidence
- `docs/SAAD.md`
- `docs/USER_GUIDE.md` only for user-visible changed defaults/diagnostics

Expose `PARTIAL_SOURCE_TRACE` in user-facing diagnostics with a concise explanation: verified source structure is shown where it could be proven; other selected rows remain log events. Ensure `CORRELATION_TOKEN` has a readable display name if evidence is exposed.

Update architecture/user documentation only after code and tests establish final behavior. Do not change the autosave format unless the implementation adds a genuinely durable option; any option must be append-last.

### Task 6 — Test, inspect, and hand off

Run, in order:

1. Focused IDEA test classes changed by the work.
2. IDEA build for all modified production files.
3. Full `./gradlew desktopTest` through IDEA terminal/run configuration.
4. Full IDEA project build.
5. IDEA inspections (`get_file_problems`) for every changed Kotlin production file.

Report exact commands/configurations, pass/fail results, and any pre-existing unrelated failures. Do not claim success when the full suite has not completed.

## Review assignment: Terra high

After implementation and all tests finish, assign exactly one independent review to GPT-5.6 Terra at `high` reasoning effort. The reviewer must be read-only and must not edit files.

Provide the reviewer this checklist:

1. Inspect the diff and confirm every plan task is either implemented or explicitly deferred with justification.
2. Check default changes do not override explicit actors/options or alter manual diagrams.
3. Look for false-positive token correlations, especially missing timestamps, generic IDs, and repeated status values.
4. Verify source-index version bump forces rebuild and parser/store fields round-trip safely.
5. Verify callback synthetic methods cannot misattribute ordinary lambda logs or resolve multiple targets arbitrarily.
6. Verify only async paths bypass branch straight-line proof; synchronous paths remain conservative.
7. Verify partial trace preserves all selected primary logs and never presents unverified structure as complete.
8. Verify cancellation and bounded search behavior are retained.
9. Evaluate test coverage against every fixture/edge case listed above.
10. Return findings prioritised as P0/P1/P2 with file and line references; if no blocking findings exist, state that explicitly.

The implementer must address P0/P1 findings, rerun affected focused tests and the full suite, then provide a final summary. Do not use GPT-5.6 Sol.

## Definition of done

- Target branch exists with only intended changes.
- New diagrams show a legitimate opening arrow and safe default handoffs without a user configuring actors.
- Token-based arrows appear only for the constrained high-confidence cases and carry their own evidence provenance.
- Auto-selected lifelines prefer meaningful/error-diverse tags without altering explicit curation.
- Source-trace failure no longer erases verified segments; every selected log remains represented.
- Trailing-lambda and anonymous callback logs have synthetic ownership and can form verified async handoffs/terminal outcomes.
- Old source indexes rebuild; existing diagram notes decode without failure.
- Focused tests, complete `desktopTest`, IDEA build, and inspections pass.
- One Terra-high review is completed and all P0/P1 findings are resolved.
