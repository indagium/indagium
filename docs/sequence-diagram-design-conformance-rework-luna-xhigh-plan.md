# Sequence diagram design-conformance rework plan for Luna xhigh

Status: implementation-ready design and code review  
Prepared from: `Design.pdf`, the supplied application screenshot, `CLAUDE.md` (treated as `AGENTS.md`), `docs/SAAD.md`,
and the current branch implementation  
Reviewed branch: `feat/fixes_and_sequence_diagramm_generation_12_08_26`  
Review date: 2026-08-13  
Implementation work in this review: none

## 1. Objective

Rework sequence-diagram creation so its durable model and authoring workflow correspond to the design in `Design.pdf`.

The central design rule is:

> The editable row is a sequence-diagram message — `from → to : label` — backed by one or more real log occurrences. It
> is not a raw log line and it is not merely an inference rule.

The current branch implements a substantial portion of the intended surface, but it still models the row primarily as a
projection over occurrence records. That approximation is now the main source of UX and correctness gaps. The
implementation should introduce a first-class message definition without discarding evidence provenance or breaking
saved diagrams.

This plan is deliberately a gap-closure plan. Preserve the branch work that already corresponds to the design.

## 2. Mandatory repository constraints

Luna must read and obey `CLAUDE.md` as if it were `AGENTS.md` before editing.

- Use the IntelliJ IDEA MCP for repository navigation, inspection, editing, test execution, and problem checks.
- Treat `docs/SAAD.md` as the authoritative architecture document.
- Keep the diagram domain and builders Compose-free.
- Keep the saved-spec codec backward compatible. Add fields and versions deliberately; do not break older cached
  diagrams.
- Preserve the user's existing dirty worktree. Do not revert, overwrite, or reformat unrelated changes.
- Do not push. Do not commit unless the user explicitly requests it.
- Do not add network or AI dependencies to manual/offline diagram reconstruction.
- Keep manual diagram rendering source-independent after the durable document exists.

## 3. Sources reviewed

### 3.1 Product design

The PDF defines:

- a message queue with visible `From` and `To`;
- a durable match/pattern with capture tokens for repeated occurrences;
- a label template independent from the match;
- message kind, evidence, per-message repeat presentation, derived order, and state;
- a needs-target counter and inline guided pass;
- two-way queue/canvas identity;
- targetless dashed stubs and endpoint drag-to-lifeline;
- row-oriented multi-selection and explicit bulk verbs;
- log-derived message order and authored lifeline order;
- reviewed regeneration with edited messages locked and one-step undo;
- keyboard-first operation;
- three first-ship priorities: row endpoints, guided target resolution, and safe regeneration.

### 3.2 Current implementation areas

The review concentrated on:

- `src/desktopMain/kotlin/com/indagium/diagram/DiagramModel.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/DiagramSpecCodec.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/ManualDiagramSeedService.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/ManualDiagramMessageQueue.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/ManualDiagramGuidedTargetPass.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/ManualDiagramBuilder.kt`
- `src/desktopMain/kotlin/com/indagium/diagram/ManualDiagramRegeneration.kt`
- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramManualEditor.kt`
- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramWorkspace.kt`
- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramInspector.kt`
- `src/desktopMain/kotlin/com/indagium/ui/SeqDiagramCoordinator.kt`
- `src/desktopMain/kotlin/com/indagium/ui/App.kt`
- the associated diagram, queue, seed, codec, workspace, and regeneration tests.

## 4. Executive assessment

The branch is a strong partial correspondence, not a faithful final implementation.

What is already solid:

- a manual durable document exists;
- evidence snapshots and source provenance survive reconstruction;
- `From` and `To` are visible and editable in queue rows;
- targetless messages render as dashed stubs;
- queue filtering and sorting are presentation-only;
- row/canvas hover and selection use stable identities;
- lifeline reordering is separate from message time order;
- bulk set-from, set-target, hide, merge, fragment, and note actions exist;
- guided target assignment has suggestions, number keys, self-call, new-lifeline, skip, apply-all, and three-line log
  context;
- regeneration already has a review model, locks edited interactions, distinguishes new/changed/orphaned/kept records,
  and has an undo entry point;
- focused current tests pass.

The largest mismatch is that the durable unit remains an occurrence-like `ManualDiagramInteraction`, with a `groupKey`
used to approximate a message. This prevents the implementation from expressing the PDF's
`Match + captures + label template + occurrences` contract cleanly.

The rework should therefore begin in the domain/codec, then flow through seeding, builder, queue, guided pass, canvas
interactions, regeneration, and keyboard behavior. A UI-only patch would preserve the underlying inconsistencies.

## 5. Design-conformance matrix

| Design contract                                                              | Current branch                                                                                  | Assessment           | Required action                                                            |
|------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|----------------------|----------------------------------------------------------------------------|
| Row is one message backed by n occurrences                                   | Queue buckets occurrence records by durable `groupKey`                                          | Partial              | Add a durable message definition that owns occurrence references           |
| Match pattern with visible capture tokens                                    | Grouping normalizes text, but ordinary seeded rows keep a literal label and no durable captures | Missing structurally | Add explicit match pattern, captures, compiler, and editor                 |
| `From → To` visible in row                                                   | Present                                                                                         | Corresponds          | Preserve; use display names and canvas order consistently                  |
| Label template separate from match                                           | Literal label is stored on each interaction                                                     | Partial              | Move authored label to message definition                                  |
| Kind is a row-level message property                                         | Present per interaction/group projection                                                        | Partial              | Own it at message level and migrate legacy values                          |
| Evidence is read-only and jumps to log                                       | Evidence snapshots and jump action exist                                                        | Mostly corresponds   | Preserve; expose values/captures without making evidence editable          |
| Repeat presentation belongs to a message                                     | One document-global repeat mode                                                                 | Mismatch             | Make repeat policy per message with threshold/default                      |
| Order derives from log clock; only tied events can be pinned                 | Persisted numeric order exists, no tie-only pin/nudge semantics                                 | Missing              | Introduce derived order key plus constrained override                      |
| State is auto/edited/needs target; hidden remains visible and struck through | Derived row state and hidden filtering exist                                                    | Mostly corresponds   | Preserve, but make state message-level                                     |
| One top needs-target number                                                  | Present                                                                                         | Corresponds          | Preserve and ensure it counts messages, not occurrences                    |
| Guided pass is an inline mode                                                | Guided card opens a modal `Dialog`                                                              | Mismatch             | Replace with queue-panel mode owned by workspace/session state             |
| Guided progress is stable and monotonic                                      | Current unresolved recomputation/current index can reset or wrap                                | Mismatch             | Track initial worklist, completed, skipped, and current explicitly         |
| Suggested target is never silently applied                                   | Present                                                                                         | Corresponds          | Preserve                                                                   |
| Number keys follow canvas lifeline order                                     | Choices come from authoring lists/raw IDs                                                       | Partial              | Derive one ordered display-lifeline list from `lifelineOrder`              |
| Row selection supports click, Shift range, Cmd/Ctrl additive                 | Selection stores occurrence IDs; checkbox toggling only                                         | Mismatch             | Add row/message selection model and modifier semantics                     |
| Bulk merge creates a pattern with captures and is reversible                 | Compatible occurrences can share `groupKey`; ungroup exists only in domain                      | Partial              | Merge message definitions, infer/preview pattern, expose Unmerge, add undo |
| Note spans selected messages                                                 | Note action anchors a single interaction/participant                                            | Mismatch             | Add selection-span anchors and renderer support                            |
| No message drag reorder                                                      | No message drag reorder                                                                         | Corresponds          | Preserve                                                                   |
| Lifelines may be dragged/reordered                                           | Inspector lifeline reorder writes `lifelineOrder`                                               | Corresponds          | Preserve and use it everywhere                                             |
| Canvas endpoint may be dragged to a lifeline                                 | No endpoint drag                                                                                | Missing              | Add endpoint handle, preview, drop resolution, and command                 |
| Double-click arrow edits label                                               | No canvas double-click label edit                                                               | Missing              | Add hit handling that reveals/focuses the label editor                     |
| Regeneration review shows new/changed/orphaned/edits-kept                    | Present                                                                                         | Corresponds          | Preserve and lift comparison to message definitions                        |
| Edited messages are locked during regeneration                               | Present for edited interactions                                                                 | Mostly corresponds   | Preserve at message level                                                  |
| Regeneration is one undoable transaction                                     | Undo snapshot stores document/order, while apply can alter other spec collections               | Partial/risky        | Snapshot and restore the complete affected spec                            |
| J/K, digits, Shift-digits, F, E, H, M, G, L, /, undo, Esc                    | Most exist; `/` missing; `E` opens details rather than focusing label                           | Partial              | Complete and test focus-safe behavior                                      |

## 6. Prioritized review findings

### P0 — No first-class durable message/match abstraction

Current evidence:

- `ManualDiagramInteraction` owns endpoints, label, kind, state, order, group key, provenance, and evidence.
- `ManualDiagramMessageQueue` creates rows by grouping interactions with `groupKey`.
- `ManualDiagramSeedService` stores ordinary log text as a literal label.
- the tokenized display template is bypassed when the representative interaction has a literal label.

Why this matters:

- a repeated group such as `USB poll tick: portCount=4 devices=3/2/...` can collapse by normalized key while still
  displaying one concrete occurrence;
- the varying values are not durable named captures;
- editing the label and editing the match are conflated;
- regeneration cannot reason about an authored matching rule independently of an occurrence;
- one logical message has properties duplicated across multiple records.

Required resolution:

Introduce a message definition that explicitly owns:

- match tag/text pattern;
- named captures;
- source and target lifeline IDs;
- label template;
- kind;
- repeat policy;
- enabled/hidden state;
- authoring state;
- derived/pinned ordering metadata;
- references to occurrence/evidence records.

### P0 — Selection identity is occurrence-oriented

Current evidence:

- the UI stores `selectedInteractionIds`;
- selecting one repeated row expands to all occurrence IDs;
- the visible count can say “6 selected” when the user selected one message;
- no Shift-range or Cmd/Ctrl-additive row gesture model exists.

Why this matters:

The design's nouns and verbs are row/message-oriented. Selection counts, keyboard navigation, bulk operations, canvas
highlighting, and undo descriptions must all refer to the same logical unit.

Required resolution:

Store selected message IDs and a selection anchor. Resolve message IDs to occurrence IDs only inside domain commands
that need evidence records.

### P0 — Repeat presentation is document-global

Current evidence:

- `ManualDiagramDocument.repeatPresentation` stores one mode;
- the builder applies it to every repeated group;
- repeat controls shown inside a row mutate the document-wide value.

Why this matters:

The design puts repeat presentation in “what a message must carry.” One noisy polling message may collapse while a
different message shows every occurrence.

Required resolution:

Move repeat policy to the message definition. Retain a document default only for new messages if useful.

### P0 — Guided target resolution is modal and its progress model is unstable

Current evidence:

- the guided card is implemented with Compose `Dialog`;
- unresolved rows are recomputed after each assignment;
- current-index normalization can make progress appear to reset;
- state lives in the composable rather than a coordinator/workspace session.

Why this matters:

The PDF specifies a mode that replaces the queue content, keeps evidence in frame, and provides reliable “n of total”
progress. Modal state and recomputed indices make the worklist harder to trust.

Required resolution:

Create an inline guided-pass state machine owned by the workspace session. Keep an immutable initial worklist and
explicit completed/skipped sets.

### P1 — Order semantics do not encode the evidence constraint

Current evidence:

- interactions have a persisted `Long order`;
- there is no command or guard for pinning only timestamp-tied neighbors;
- there is no pinned badge or revert action.

Why this matters:

Arbitrary persisted order can imply evidence was reordered. The design only permits authored order within an actual
timestamp tie.

Required resolution:

Separate the derived evidence key from an optional tie-only override. Validate every nudge in the domain, not only in
the UI.

### P1 — Bulk actions are not fully reversible or design-complete

Current evidence:

- merge is reversible at the domain level through an ungroup operation, but the queue does not expose Unmerge;
- merge changes grouping rather than creating an explicit match/capture rule;
- Group and Note use placeholder/default semantics;
- notes anchor one interaction instead of spanning the selected message range;
- general manual edits do not share a transaction history.

Required resolution:

Make every structural action a named command with a reversible before/after snapshot. Expose Unmerge. Require a
preview/validation result for pattern inference. Store selection-spanning note anchors.

### P1 — Canvas authoring is incomplete

Current evidence:

- row/canvas hover and click synchronization exist;
- targetless stubs exist;
- no arrow endpoint drag/drop exists;
- no double-click-to-edit-label behavior exists.

Required resolution:

Add UI-only hit/gesture handling that emits domain commands. Do not put Compose or pointer state into the diagram
domain.

### P1 — Regeneration undo does not snapshot the complete affected spec

Current evidence:

- the coordinator's seed/regeneration undo snapshot records the manual document and lifeline order;
- applying a candidate can also add inferred components/participants to the spec.

Why this matters:

Undo may restore the document while leaving palette/lifeline data introduced by regeneration. That violates “restore the
draft intact.”

Required resolution:

Apply regeneration as one full-spec transaction, or snapshot every collection the operation is allowed to mutate. Prefer
a full immutable spec snapshot in workspace memory.

### P1 — Endpoint labels and digit choices are not driven by one canvas-order source

Current evidence:

- guided choices are built from components, actors, and legacy participants;
- queue endpoint chips can display raw IDs;
- lifeline renames and `lifelineOrder` are not consistently reflected.

Required resolution:

Create one Compose-free ordered-lifeline projection with stable ID, display label, kind, and canvas index. Use it for
row labels, dropdowns, guided digits, keyboard help, and canvas hit targets.

### P2 — Keyboard behavior is incomplete

Current evidence:

- J/K, digits, Shift-digits, F, H, M, G, L, and Esc largely exist;
- plain `/` does not focus the queue filter;
- `E` expands details instead of directly focusing/selecting the label field;
- shortcut dispatch needs stronger text-input guards.

Required resolution:

Complete the exact mapping and add behavioral tests around typing fields, modal surfaces, guided mode, selection, and
canvas focus.

## 7. Preserve these branch behaviors

Do not regress the following while reworking the model:

1. Durable evidence snapshots and log jump targets.
2. Manual rendering that works from the saved document without source log access.
3. Targetless dashed stubs.
4. Stable queue/canvas identity and two-way reveal.
5. View-only filters and sorts.
6. Authored lifeline ordering that does not mutate message chronology.
7. Conservative target suggestions that are never silently applied.
8. Three-line guided evidence context.
9. Edited-record protection during regeneration.
10. Explicit regeneration review categories and per-row decisions.
11. Existing codec compatibility for versions 1–4.
12. Existing offline/cached diagram behavior.

## 8. Target domain design

### 8.1 Recommended compatibility-oriented shape

Minimize collateral migration by retaining `ManualDiagramInteraction` as the occurrence/evidence store for version-4
documents and introducing explicit row-level definitions.

```kotlin
data class ManualDiagramDocument(
    val interactions: List<ManualDiagramInteraction>, // legacy + occurrence store
    val messages: List<ManualDiagramMessageDefinition> = emptyList(),
    val groups: List<ManualDiagramGroup> = emptyList(),
    val notes: List<ManualDiagramNote> = emptyList(),
    val activations: List<ManualDiagramActivation> = emptyList(),
    val defaultRepeatPolicy: ManualMessageRepeatPolicy =
        ManualMessageRepeatPolicy.collapseAbove(3),
)

data class ManualDiagramMessageDefinition(
    val id: String,
    val occurrenceIds: List<String>,
    val match: ManualMessageMatch,
    val fromParticipantId: String,
    val toParticipantId: String?,
    val labelTemplate: String,
    val kind: MessageKind,
    val repeatPolicy: ManualMessageRepeatPolicy,
    val visibility: ManualInteractionVisibility,
    val authoring: ManualInteractionAuthoring,
    val orderOverride: ManualMessageOrderOverride? = null,
)

data class ManualMessageMatch(
    val tagPattern: String?,
    val textPattern: String,
    val captures: List<ManualMessageCapture>,
)

data class ManualMessageCapture(
    val name: String,
    val source: ManualCaptureSource,
)

data class ManualMessageOccurrence(
    val interactionId: String,
    val captureValues: Map<String, String>,
    val evidence: List<ManualDiagramEvidence>,
    val derivedOrder: ManualDerivedOrder,
)

data class ManualDerivedOrder(
    val timestampMillis: Long?,
    val sourceOrdinal: Long,
)

data class ManualMessageOrderOverride(
    val tiedTimestampMillis: Long,
    val tieRank: Int,
)
```

Names are illustrative; Luna should align them with repository conventions. The ownership boundaries are not optional.

### 8.2 Canonical adapter

Add one Compose-free canonicalizer:

```kotlin
fun canonicalManualMessages(
    document: ManualDiagramDocument,
): List<CanonicalManualMessage>
```

Rules:

1. If explicit message definitions exist, validate and project them.
2. If not, derive definitions from version-1–4 interaction/group data.
3. Preserve legacy interaction IDs as occurrence IDs.
4. Produce deterministic message IDs from existing group identity where possible.
5. If legacy grouped interactions disagree on endpoints/kind/state, split conservatively instead of guessing.
6. Never discard evidence or source provenance.
7. Emit validation diagnostics for dangling occurrence, group, note, or activation references.

Queue, builder, regeneration, and editor commands must consume the same canonical projection. Do not independently
reimplement grouping in each layer.

### 8.3 Codec version and migration

Because this is a structural saved-document change, prefer a deliberate version 5:

- keep decoding versions 1–4;
- add version-5 message definitions, match/capture data, per-message repeat policy, and order overrides;
- use defaults for absent fields;
- write version 5 only after the new document has been normalized;
- add golden decode fixtures for every supported version;
- add a version-4 → canonical → version-5 round-trip test;
- prove no evidence IDs, line references, participant IDs, edited states, or labels disappear;
- handle unknown future enum values defensively if the codec conventions permit it.

Do not silently rewrite a saved diagram merely by opening it. Migration may occur in memory; persistence occurs on the
user's next actual save.

### 8.4 Match and capture compiler

Create a pure compiler used by seeding and Merge:

```kotlin
fun compileManualMessageMatch(
    occurrences: List<ManualMessageMatchInput>,
): ManualMessageMatchCompilation
```

Compilation requirements:

- all input occurrences must be matched exactly by the result;
- detect stable text and varying runs;
- prefer semantic names from `key=value` tokens, for example `deviceKey`, `portCount`, or `status`;
- use deterministic fallback names such as `value`, `value2`;
- do not tokenize timestamp, level, tag, PID/TID, or other log envelope fields as message text;
- preserve punctuation and whitespace intentionally;
- expose capture values per occurrence;
- reject ambiguous or over-broad patterns rather than manufacturing a risky rule;
- return preview text and warnings for the Merge UI;
- keep `labelTemplate` separate so a user can rename the arrow without weakening/altering the match.

The initial seed may use conservative exact patterns for single occurrences. Multi-occurrence groups must show tokenized
variation instead of the first concrete label.

### 8.5 Repeat policy

Define repeat presentation per message:

- collapsed consecutive occurrences with `×n`;
- every occurrence;
- first and last with explicit ellipsis;
- collapse threshold, default 3.

The builder must only collapse adjacent compatible occurrences. Interleaved events retain chronological position. A
message's repeat choice must not affect another message.

### 8.6 Order contract

The canonical message order is derived from:

1. earliest occurrence timestamp, when available;
2. original source ordinal as deterministic tie breaker;
3. stable message ID only as a final deterministic fallback.

An order override is valid only if:

- both messages have the same real timestamp;
- they are neighbors in the same tied bucket;
- the operation changes only tie rank.

The domain command must reject invalid nudges even if invoked outside the UI. The queue shows a pin/tie badge and a
revert action. Filters and sort modes never affect canvas chronology.

### 8.7 Structural spans

Fragments and notes must use stable message anchors:

```kotlin
data class ManualMessageSpan(
    val firstMessageId: String,
    val lastMessageId: String,
)
```

Resolve the span against canonical log order. If an anchor no longer exists after regeneration, surface it during review
and repair conservatively; do not silently attach to an unrelated message.

## 9. Command, selection, and undo design

### 9.1 Message-oriented selection

Introduce workspace selection state:

```kotlin
data class ManualQueueSelection(
    val selectedMessageIds: Set<String>,
    val anchorMessageId: String?,
    val focusedMessageId: String?,
)
```

Gesture rules:

- click: select only that message and set anchor;
- Shift-click: select the inclusive range between anchor and clicked row in the currently visible queue order;
- Cmd/Ctrl-click: toggle only the clicked message and retain a sensible anchor;
- canvas click: select/reveal the corresponding message;
- Esc: clear selection first; if already clear, exit guided/edit mode;
- filtering must not delete hidden selections, but the UI must state when selected messages are outside the current
  view;
- the selection count always counts logical messages.

Bulk commands receive message IDs. They resolve occurrence IDs internally.

### 9.2 Named edit commands

Route manual mutations through a small command boundary, for example:

- `SetMessageSource`
- `SetMessageTarget`
- `SetMessageLabel`
- `SetMessageKind`
- `SetMessageRepeatPolicy`
- `SetMessageVisibility`
- `MergeMessages`
- `UnmergeMessage`
- `CreateFragment`
- `CreateSpanningNote`
- `PinTiedMessageOrder`
- `ClearMessageOrderPin`
- `ApplyRegenerationReview`

Each command must:

- validate domain invariants;
- produce a complete updated immutable spec/document;
- return a concise user-facing description;
- be applied as one undoable transaction;
- preserve stable IDs wherever semantics did not change.

### 9.3 Undo

Maintain a bounded workspace-local undo stack of full affected specs, not partial mutable fragments.

- regeneration apply is exactly one entry;
- Merge/Unmerge, Group, Note, Hide, endpoint drag, and tie pin are each one entry;
- text edits commit on field commit/focus loss, or coalesce into one entry per edit session;
- undo restores participants/components/lifeline order/manual document together;
- redo is optional unless already expected by repository conventions, but the design's Cmd/Ctrl-Z must be reliable;
- unsaved undo state does not need to persist across application restarts.

## 10. Guided target pass state machine

Move guided state out of a modal composable:

```kotlin
data class GuidedTargetPassState(
    val initialMessageIds: List<String>,
    val currentMessageId: String?,
    val completedMessageIds: Set<String>,
    val skippedMessageIds: Set<String>,
    val initialTotal: Int,
)
```

Rules:

1. Entering with zero unresolved messages is a no-op with feedback.
2. The initial worklist is stable for the pass.
3. Resolving advances to the next unresolved item.
4. Skip advances without marking resolved and remains revisitable.
5. Progress is monotonic: completed count never decreases during the pass.
6. If another action resolves a future item, it is omitted when reached but total remains understandable.
7. Leaving the diagram surface and returning in the same workspace restores the active pass.
8. Closing the source/workspace may discard the session state; document edits remain.

The guided mode replaces the queue body and keeps:

- current `from → ? : label`;
- capture tokens;
- exactly three evidence-context lines centered on the occurrence where possible;
- suggested target, visually distinguished but not applied;
- choices 1–9 in canvas lifeline order with display names;
- Enter to accept suggestion;
- number to override;
- self call;
- create lifeline;
- skip;
- apply to all occurrences represented by the message.

Do not use a `Dialog`.

## 11. Queue and inspector behavior

### 11.1 Compact row

Every row must expose without expansion:

- state marker and word;
- label template with capture tokens;
- From dropdown;
- To dropdown or “needs target”;
- occurrence count;
- edited/pinned/hidden badges as applicable.

Hidden rows remain available through the hidden filter and render struck through. Needs-target count is row/message
based.

### 11.2 Focused details

Use the selected/focused message for details. Avoid expanding a repeated group into a queue-sized wall that destroys
scanability.

Details include:

- editable match/pattern field;
- read-only capture list and sample values;
- editable label template;
- kind segmented control;
- per-message repeat policy;
- evidence occurrences with line/timestamp and jump-to-log;
- order/pin status;
- authoring state;
- Unmerge when applicable.

Changing the pattern requires validation against existing occurrences. An invalid pattern remains a draft with a clear
error and cannot be committed.

### 11.3 Bulk bar

When one or more logical messages are selected, show:

- Set from;
- Set target;
- Merge, only when compiler/compatibility permits it;
- Unmerge, when selection includes merged messages;
- Group fragment with explicit kind/name;
- Hide/Show;
- Note spanning selection.

Do not offer bulk delete, bulk pattern editing, or arbitrary bulk reorder.

## 12. Canvas authoring

### 12.1 Endpoint drag

Extend canvas hit metadata without polluting domain models with Compose types:

- distinguish arrow body, source endpoint, target endpoint, and targetless stub endpoint;
- show an endpoint handle on hover/selection;
- on drag, render a temporary preview only;
- resolve the drop to the nearest eligible lifeline hit area;
- commit `SetMessageSource` or `SetMessageTarget` once on successful drop;
- reject drops outside lifelines and restore the original;
- Esc cancels the preview;
- dragging a targetless stub onto a lifeline resolves it;
- dragging onto the current source may create a self-call after explicit supported semantics.

Add coordinate/hit tests at multiple zoom and fit modes.

### 12.2 Arrow label editing

Double-clicking an arrow:

- selects/reveals the queue message;
- exits guided mode if needed using a defined rule;
- focuses the label-template field;
- selects the existing label text for immediate typing;
- commits through the same command/undo path as row editing.

### 12.3 Lifeline order projection

Create one ordered-lifeline projection used by:

- canvas columns;
- queue dropdowns;
- guided 1–9 choices;
- Shift+1–9 source choices;
- display-name lookup;
- shortcut help.

Respect `spec.lifelineOrder`, then append unlisted durable lifelines deterministically. Never show raw IDs when a
display name exists.

## 13. Regeneration behavior

Lift the existing reviewed regeneration flow from interactions to canonical messages.

### 13.1 Matching

Match old and candidate messages in this order:

1. exact stable message ID/provenance;
2. exact authored match plus participants and kind;
3. unique semantic fallback;
4. otherwise ambiguous/new.

Never match solely by mutable display label.

### 13.2 Edited protection

For an edited message:

- preserve match, endpoints, label, kind, repeat policy, visibility, order pin, and structural anchors;
- update evidence/capture occurrences only when review explicitly permits it;
- make conflicts visible;
- never silently replace authored content.

### 13.3 Review categories

Retain at least:

- new;
- changed automatic;
- no longer in source;
- edited kept;
- ambiguous/conflicted, when applicable.

The review should summarize message counts and evidence-count changes. If regeneration proposes lifeline palette
changes, show them explicitly or keep them out of the candidate until approved.

### 13.4 Atomic apply and undo

Apply the accepted review as one full-spec transaction. One Cmd/Ctrl-Z must restore:

- manual document;
- explicit message definitions;
- occurrences/evidence;
- groups/notes/activations;
- participants/components/actors changed by the operation;
- lifeline order;
- selection/focus mapped back to surviving IDs where possible.

## 14. Keyboard and accessibility contract

Implement and test:

| Key        | Behavior                                             |
|------------|------------------------------------------------------|
| J / K      | next / previous visible message                      |
| 1–9        | set target by visible canvas lifeline order          |
| Shift+1–9  | set source by visible canvas lifeline order          |
| F          | enter/leave guided target mode                       |
| E          | focus and select label editor                        |
| H          | hide/show selected messages                          |
| M          | merge selected messages when valid                   |
| G          | open/apply fragment grouping for selection           |
| L          | jump to focused message's primary evidence line      |
| /          | focus and select queue filter                        |
| Cmd/Ctrl-Z | undo last manual transaction, including regeneration |
| Esc        | clear selection or exit the active mode              |

Safety:

- printable shortcuts must not fire while a text field is accepting text;
- digits in pattern/label/filter fields must type normally;
- shortcuts must not leak through another dialog/sheet;
- every icon/button/control needs tooltip or accessible label;
- focus must return predictably after guided assignment and canvas edits.

## 15. Implementation phases

Each phase must leave tests green. Do not combine the model migration and major Compose rewrites into one unreviewable
patch.

### Phase 0 — Baseline and worktree safety

Tasks:

1. Read `CLAUDE.md` and `docs/SAAD.md`.
2. Record current branch and dirty status.
3. Identify user-owned changes overlapping the target files.
4. Run the focused baseline tests listed in section 17.
5. Capture a baseline screenshot of the supplied scenario if runnable.

Exit criteria:

- baseline behavior and failures are recorded;
- no existing changes were reverted;
- implementation scope is explicit.

### Phase 1 — Characterization tests and design contract

Add failing tests for the gaps before changing the domain:

- an ordinary repeated log message produces a tokenized pattern and capture values;
- one logical queue row owns n evidence occurrences;
- repeat policy differs between two messages;
- legacy version-4 data canonicalizes deterministically;
- selection counts logical messages;
- guided progress remains monotonic;
- invalid non-tied nudge is rejected;
- regeneration undo restores all affected spec collections.

Also update the relevant SAAD section with the target ownership boundaries before or alongside implementation, as
required by repository practice.

Exit criteria:

- tests demonstrate the current mismatch;
- new architecture terminology is agreed in code/docs.

### Phase 2 — Model, canonical adapter, and codec v5

Primary files:

- `DiagramModel.kt`
- `DiagramSpecCodec.kt`
- codec/model tests

Tasks:

1. Add explicit message definition, match, capture, per-message repeat, derived order, and tie override types.
2. Add the canonical adapter and validation diagnostics.
3. Decode versions 1–4 through the legacy adapter.
4. Write/read version 5.
5. Map legacy groups, notes, and activations to stable message anchors.
6. Keep model code Compose-free and immutable.

Exit criteria:

- v1–v4 fixtures still decode;
- v5 round-trips;
- version-4 diagrams project to the same visible diagram before new editing;
- no evidence/provenance loss.

### Phase 3 — Match compiler and seeding

Primary files:

- `ManualDiagramSeedService.kt`
- a new pure `ManualMessageMatchCompiler.kt` if useful
- seed/compiler tests

Tasks:

1. Compile repeated ordinary log text into stable pattern + captures.
2. Seed explicit message definitions.
3. Keep exact patterns for singletons.
4. Make label templates readable and independent.
5. Add conservative failure diagnostics.
6. Use the same compiler for Merge.

Exit criteria:

- the PDF's varying-value examples display named tokens;
- every occurrence is exactly matched;
- ambiguous merges are rejected with a reason.

### Phase 4 — Builder, repeats, ordering, and spans

Primary files:

- `ManualDiagramBuilder.kt`
- `SeqDiagramBuilder.kt`
- builder tests

Tasks:

1. Build from canonical messages.
2. Apply repeat policy per message.
3. Preserve adjacency-only collapse.
4. Derive canvas order from evidence keys.
5. Apply only validated same-timestamp tie overrides.
6. Resolve fragments/notes by message spans.
7. Preserve targetless stubs and stable arrow identity.

Exit criteria:

- two messages can render different repeat modes;
- sorting the queue never changes canvas order;
- non-tied messages cannot be authored out of time order.

### Phase 5 — Queue identity, selection, details, and commands

Primary files:

- `ManualDiagramMessageQueue.kt`
- `SeqDiagramManualEditor.kt`
- coordinator/session command support
- queue/editor tests

Tasks:

1. Make queue rows direct projections of canonical messages.
2. Replace occurrence-ID UI selection with message-ID selection.
3. Implement click/Shift/Cmd-Ctrl semantics.
4. Add compact rows and focused details.
5. Add match validation and per-message repeat controls.
6. Route edits through named undoable commands.
7. Expose Unmerge.
8. Implement selection-spanning notes and explicit fragment inputs.

Exit criteria:

- one selected repeated row reports one selected message;
- bulk actions operate on the intended rows;
- all structural actions are undoable;
- 150-row scanning remains usable.

### Phase 6 — Inline guided target pass

Primary files:

- `ManualDiagramGuidedTargetPass.kt`
- `SeqDiagramManualEditor.kt`
- `SeqDiagramCoordinator.kt` or workspace session owner
- guided-pass tests

Tasks:

1. Replace the modal dialog with inline queue mode.
2. Implement the stable worklist state machine.
3. Use ordered display lifelines for digits.
4. Preserve suggestion/manual-confirmation semantics.
5. Keep evidence context in frame.
6. Test skip, wrap prevention, external resolution, and re-entry.

Exit criteria:

- progress is stable and monotonic;
- no `Dialog` is used;
- all PDF actions work from keyboard and pointer.

### Phase 7 — Canvas endpoint and label editing

Primary files:

- `SeqDiagramWorkspace.kt`
- canvas hit/projection helpers
- workspace tests

Tasks:

1. Add endpoint-specific hit targets.
2. Add drag preview and lifeline drop resolution.
3. Commit through endpoint commands.
4. Add double-click label focus.
5. Verify behavior under zoom, Fit, and Fit width.

Exit criteria:

- targetless stubs can be resolved by drag;
- failed/cancelled drops do not mutate the spec;
- double-click focuses the same label editor used by `E`.

### Phase 8 — Regeneration and full transaction undo

Primary files:

- `ManualDiagramRegeneration.kt`
- `SeqDiagramCoordinator.kt`
- regeneration tests

Tasks:

1. Compare canonical messages.
2. Keep all edited message fields locked.
3. Surface capture/evidence and lifeline changes.
4. Apply a review through the command transaction boundary.
5. Replace partial undo snapshots with full affected-spec snapshots.
6. Map selection/focus after apply and undo.

Exit criteria:

- one undo restores the exact pre-regeneration draft;
- no component/participant/lifeline residue remains;
- ambiguous matches never overwrite an authored message.

### Phase 9 — Keyboard, accessibility, and performance

Primary files:

- `SeqDiagramManualEditor.kt`
- `SeqDiagramWorkspace.kt`
- `App.kt` only where global routing is truly required
- UI/session tests

Tasks:

1. Implement `/` and correct `E`.
2. Unify digit ordering with canvas lifelines.
3. Add text-input and overlay guards.
4. Add tooltips/semantics.
5. Measure queue operations and rendering with 150 messages/large evidence sets.
6. Avoid rebuilding expensive occurrence projections per row per recomposition.

Exit criteria:

- exact shortcut table passes;
- typing is never intercepted;
- no obvious input/render regression at the design scale.

### Phase 10 — Documentation and final QA

Update:

- `docs/SAAD.md`
- `docs/USER_GUIDE.md`
- other feature docs only where they are authoritative

Document:

- message vs occurrence ownership;
- match/capture syntax;
- repeat policy;
- guided mode;
- selection/bulk verbs;
- order constraints;
- regeneration review/undo;
- keyboard shortcuts;
- version-5 compatibility.

Run all gates in section 17 and perform the manual scenarios in section 18.

## 16. File-by-file responsibility map

| File/area                          | Intended responsibility after rework                                              |
|------------------------------------|-----------------------------------------------------------------------------------|
| `DiagramModel.kt`                  | Durable message, match, capture, repeat, order, span, and validation types        |
| `DiagramSpecCodec.kt`              | v1–v5 backward-compatible persistence                                             |
| `ManualDiagramSeedService.kt`      | Source-to-occurrence extraction and initial explicit messages                     |
| new match compiler                 | Pure pattern/capture inference shared by seed and Merge                           |
| `ManualDiagramMessageQueue.kt`     | Canonical row projection, filters/sorts, selection-compatible bulk command inputs |
| `ManualDiagramGuidedTargetPass.kt` | Pure guided state transitions and suggestion selection                            |
| `ManualDiagramBuilder.kt`          | Source-independent canonical-message rendering                                    |
| `ManualDiagramRegeneration.kt`     | Candidate matching and reviewed message-level merge                               |
| `SeqDiagramCoordinator.kt`         | Workspace session, command transactions, full undo, regeneration orchestration    |
| `SeqDiagramManualEditor.kt`        | Queue/guided/details UI and keyboard handling                                     |
| `SeqDiagramWorkspace.kt`           | Queue/canvas sync, endpoint drag, arrow double-click                              |
| `SeqDiagramInspector.kt`           | Lifeline authoring and ordered display-lifeline projection integration            |
| `App.kt`                           | Only top-level shortcut/surface routing that cannot remain local                  |
| tests                              | Domain invariants first, then UI/session integration                              |

Avoid placing domain grouping, pattern inference, or order validation directly in composables.

## 17. Test and quality gates

Run focused tests continuously, then the full desktop suite.

Baseline focused configurations/tests already pass in the reviewed worktree:

- `DiagramBuilderTest`
- `DiagramWorkspaceSessionTest`
- `ManualDiagramMessageQueueTest`
- `ManualDiagramRegenerationTest`
- `DiagramSpecCodecTest`
- `ManualDiagramSeedServiceTest`

Required final gates:

1. all model/codec fixtures and version migrations;
2. seed and match compiler tests;
3. manual builder tests;
4. queue/selection/bulk action tests;
5. guided state-machine tests;
6. regeneration and full undo tests;
7. workspace hit/drag/identity tests;
8. keyboard/focus tests;
9. full `desktopTest`;
10. project compile/build gate required by `CLAUDE.md`;
11. IntelliJ problem inspection for every changed Kotlin file.

Add targeted tests for:

- named and positional captures;
- repeated values, missing values, Unicode, punctuation, and multi-line text;
- singletons vs repeated groups;
- interleaved repeat occurrences;
- per-message repeat settings;
- legacy disagreement splitting;
- dangling references;
- Shift selection after filtering/sorting;
- selection preserved across regeneration;
- same-timestamp pin vs invalid different-timestamp pin;
- zoomed endpoint hit testing;
- rename display names in guided digits;
- text fields suppressing shortcuts;
- full-spec regeneration undo;
- cached rendering with the original source unavailable.

## 18. Manual acceptance scenarios

Use a log selection comparable to the supplied screenshot, with at least four lifelines and 40–150 queue rows.

### Scenario A — Initial queue

1. Create a sequence diagram from a log range.
2. Confirm each row is a message, not a raw log line.
3. Confirm From/To are visible without expansion.
4. Confirm the top number counts unresolved messages.
5. Confirm repeated varying values display capture tokens.
6. Confirm evidence opens read-only and jumps to the exact log line.

### Scenario B — Guided pass

1. Press F.
2. Confirm the queue becomes inline guided mode, not a dialog.
3. Confirm three context lines remain visible.
4. Confirm suggestion is highlighted but not applied.
5. Assign with Enter and with a number key.
6. Skip one item and return to it.
7. Confirm progress never resets or wraps unexpectedly.
8. Finish and confirm the banner disappears at zero.

### Scenario C — Selection and bulk verbs

1. Click one repeated row; count must be one.
2. Shift-click a later row; range selection must match visible order.
3. Cmd/Ctrl-click one row; only that row toggles.
4. Merge compatible rows and inspect generated match/captures.
5. Undo, redo if supported, merge again, then Unmerge.
6. Create a fragment and spanning note.
7. Hide selected rows and confirm struck-through hidden-state behavior.

### Scenario D — Canvas editing

1. Hover/select a row and confirm the matching arrow highlights.
2. Click an arrow and confirm its row reveals.
3. Drag a targetless stub to a lifeline.
4. Cancel a second drag with Esc; confirm no mutation.
5. Double-click an arrow and type a new label.
6. Confirm label editing does not change the match.

### Scenario E — Order

1. Attempt to reorder non-tied messages; action must be unavailable/rejected.
2. Nudge two same-timestamp messages.
3. Confirm pin badge and canvas change.
4. Clear the pin.
5. Sort queue by occurrence count and state; canvas order must remain unchanged.
6. Reorder lifelines; canvas columns change while message chronology does not.

### Scenario F — Regeneration

1. Edit label, endpoints, repeat policy, and visibility on several messages.
2. Regenerate with a changed source range.
3. Review new/changed/no-longer-in-source/edited-kept counts.
4. Confirm authored fields remain locked.
5. Apply mixed per-row decisions.
6. Undo once.
7. Confirm the complete original draft, lifelines, participants, groups, notes, and selection are restored.

### Scenario G — Offline reopen

1. Save the diagram.
2. Remove/unavailable the source log in a controlled test fixture.
3. Reopen the saved diagram.
4. Confirm messages, captures, evidence snapshots, repeats, fragments, notes, and order render from the durable
   document.

## 19. Risks and mitigations

### Saved-document migration

Risk: message definitions can invalidate legacy group/note/activation references.

Mitigation: canonical adapter, deterministic ID mapping, diagnostics, golden fixtures, and no eager persistence.

### Over-generalized patterns

Risk: automatic capture inference merges semantically different messages.

Mitigation: exact-match proof for all selected occurrences, conservative compiler, preview, explicit rejection, and
undo.

### Identity churn

Risk: regeneration or migration changes message IDs, breaking selection and canvas links.

Mitigation: preserve stable IDs by provenance/group mapping and centralize identity generation.

### UI/domain duplication

Risk: queue, guided mode, builder, and regeneration each derive different groups.

Mitigation: one canonical message adapter and one command boundary.

### Compose performance

Risk: capture/evidence projections are recomputed for every row.

Mitigation: immutable precomputed queue models, keyed rows, memoized projections at the workspace boundary, and a
150-message performance fixture.

### Undo memory

Risk: full-spec snapshots consume memory.

Mitigation: bounded history, immutable structural sharing, coalesced text edits, and workspace-local lifetime.

## 20. Explicit non-goals

- Do not replace the UI with a text DSL.
- Do not make the full-width rule table the default surface.
- Do not group the queue by lifeline by default; it may remain a view-only sort.
- Do not introduce arbitrary message drag reordering.
- Do not remove or synthesize evidence to make patterns look cleaner.
- Do not require the source log to render an already saved manual diagram.
- Do not add cloud/AI dependency to pattern compilation or regeneration.
- Do not redesign unrelated log, note, or application chrome surfaces.

## 21. Definition of done

The rework is complete only when:

1. one durable message definition owns its match, endpoints, label, kind, repeat, state, order metadata, and occurrence
   references;
2. old diagrams decode and render without evidence loss;
3. repeated ordinary log messages show durable capture tokens;
4. row selection and counts are message-oriented;
5. guided targeting is inline, stable, keyboard-complete, and canvas-order aware;
6. repeats are per message;
7. arbitrary message reorder is impossible and tie pins are validated;
8. merge/unmerge, spans, endpoint editing, and regeneration are undoable commands;
9. regeneration undo restores the complete draft/spec state;
10. the exact keyboard contract is implemented without stealing text input;
11. all focused and full test/build gates pass;
12. SAAD and user documentation match the code;
13. the supplied screenshot scenario can be completed efficiently without opening every row.

## 22. Copy-ready prompt for Luna xhigh

```text
You are Luna running at xhigh reasoning. Work in:

/Users/romanarnaut/IdeaProjects/openLog2

Your task is to implement the sequence-diagram design-conformance rework described in:

docs/sequence-diagram-design-conformance-rework-luna-xhigh-plan.md

The product source of truth is:

/Users/romanarnaut/Downloads/Design.pdf

The supplied reference screenshot is:

/var/folders/bg/xsm0wl1x23v0yy0lypf6c56m0000gn/T/codex-clipboard-4ba89a11-d16b-4000-aea8-b1929dc824ee.png

Before changing anything:

1. Read CLAUDE.md completely and treat it as AGENTS.md.
2. Read docs/SAAD.md and the entire plan completely.
3. Inspect the current branch and dirty worktree. Existing modifications belong to the user. Never revert, overwrite, or broadly reformat them.
4. Use the IntelliJ IDEA MCP for repository navigation, edits, inspections, and test execution.
5. Run the focused baseline tests in plan section 17.
6. Compare the plan against the actual current code; if line-level details have moved, preserve the plan's architectural intent.

Core outcome:

The editable durable unit must be a first-class message (from → to : label) backed by one or more evidence occurrences. It must explicitly own match/captures, endpoints, label template, kind, per-message repeat policy, state, order metadata, and occurrence references. Do not attempt to solve the design with Compose-only grouping over occurrence records.

Hard constraints:

- diagram domain/builders remain Compose-free;
- saved diagrams versions 1–4 remain readable;
- prefer an explicit backward-compatible version 5 for the new structural document shape;
- no source log is required to render a saved manual diagram;
- no network/AI dependency;
- no arbitrary message drag ordering;
- suggestions are never silently applied;
- edited messages are protected during regeneration;
- regeneration apply is one full-spec undoable transaction;
- do not push or commit unless explicitly asked.

Implement in the phases specified by the plan. Begin with characterization tests and the domain/codec/canonical adapter. Do not start with a large composable rewrite. Keep each phase green and inspect all changed Kotlin files with IDEA.

Use subagents only for bounded, non-overlapping review work. Good assignments are:

- a model/codec reviewer to audit v1–v5 migration, durable identity, and evidence preservation;
- a UI/input reviewer to audit selection gestures, guided-mode state, endpoint dragging, focus, and keyboard routing;
- a test/architecture reviewer to compare the implementation with Design.pdf, CLAUDE.md, and docs/SAAD.md and identify missing acceptance coverage.

The primary Luna agent owns implementation and integration. If subagents edit, assign exclusive files and tell them not to revert other work. Prefer review-only subagents to avoid conflicts. After each review, independently verify findings against the repository before applying them.

Required implementation sequence:

1. Baseline and failing characterization tests.
2. Explicit message/match/capture/repeat/order/span model.
3. Canonical legacy adapter and backward-compatible codec.
4. Pure conservative match compiler used by seeding and Merge.
5. Builder conversion to canonical messages, per-message repeats, evidence order, and tie-only pins.
6. Message-ID queue selection with click, Shift range, and Cmd/Ctrl additive semantics.
7. Compact rows, focused pattern/details editor, reversible bulk commands, Unmerge, fragments, and spanning notes.
8. Inline non-dialog guided target pass with stable progress and canvas-order digit choices.
9. Canvas endpoint drag and arrow double-click label editing through domain commands.
10. Message-level reviewed regeneration and full-spec transaction undo.
11. Exact keyboard/focus/accessibility contract.
12. SAAD/user docs, focused tests, full desktop tests, build gate, and manual QA.

At every phase:

- preserve targetless dashed stubs, evidence/log jumps, queue/canvas identity, view-only sorts, authored lifeline order, and current safe regeneration review behavior;
- put domain invariants in pure Kotlin, not composables;
- preserve stable IDs where semantics are unchanged;
- add tests before or with each behavior;
- do not hide ambiguity with guesses.

When you believe implementation is complete:

1. Run every gate in plan section 17.
2. Execute the manual acceptance scenarios in section 18 where the environment supports them.
3. Ask bounded review subagents to audit final design correspondence, codec compatibility, and UI/input behavior.
4. Fix all substantiated P0/P1 findings and rerun affected gates.
5. Report:
   - files changed;
   - architectural decisions;
   - migration behavior;
   - tests/builds run and exact outcomes;
   - manual scenarios verified;
   - remaining deviations or risks;
   - confirmation that no unrelated user changes were reverted.

Do not declare success merely because the current tests pass. The definition of done is plan section 21 and behavioral correspondence with Design.pdf.
```
