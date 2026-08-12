# Sequence Diagram Message-Editing UX — Luna Implementation Plan

## Goal

Replace the current dense, expandable **Interactions** editor with the proposal's message-oriented editing flow while keeping Indagium's existing visual language and evidence guarantees.

The implementation must make a manual interaction read as a message:

```text
from → to : label, backed by one or more real log entries
```

The desired result is a finishable workflow for diagrams with 40–150 messages: unresolved targets are visible, quick to fix in a guided pass, safely bulk-editable, and always traceable back to log evidence. The proposal's information architecture and interaction behavior are the UX source of truth; existing Compose components, spacing, typography, colors, and theme behavior are the visual source of truth.

## Design decisions from the supplied proposal

Implement these behaviors.

- A row represents a message/group, not an anonymous literal log line or a low-level rule.
- `From → To` is always visible in the row and editable in one action; do not make users expand a card merely to set an endpoint.
- Repeated normalized messages remain one row with an `×n` count. Render their generalized template with token-like variable values where useful.
- Messages without a destination are explicit work: count them, filter them, show an unambiguous state, and render them as dashed stubs rather than silently omitting them.
- **Fix these** enters a workspace-local guided pass, not a one-message-per-dialog flow. It shows log context, a non-destructive suggested target, numbered choices, a self-call option, a new-lifeline option, skip, and progress.
- Multi-selection has visible verbs: set source, set target, merge, group as fragment, hide/show, and add a note. It must not offer bulk delete, bulk reorder, or bulk pattern editing.
- Message order is evidence-derived. Remove drag-reorder from the message list. Keep lifeline-column reordering, because that is presentation authorship rather than event-order rewriting. Only introduce an explicit, reversible ordering override later if timestamp ties demonstrably need it.
- The message list may be filtered/sorted for navigation, but sorting must not change the diagram order.
- Regeneration is a reviewed proposal. It protects edited messages by default and is undoable.
- Row and canvas are two-way: hovering a row highlights its arrow; clicking an arrow selects and reveals its row. Evidence navigation remains explicit from the row/guided pass and shortcut.

Do **not** implement the discarded alternatives in the proposal: a raw textual diagram syntax as the primary editor, a spreadsheet/grid editor, or drag-reordering messages.

## Repository reality and boundaries

This feature already has a durable manual diagram document, a workspace preview pipeline, themed raster renderer, note/draft codec, source-log evidence, grouped manual rows, an interaction-selection concept, advanced structure, and a one-step seed undo. Extend those contracts rather than creating a parallel diagram editor.

Primary files and their roles:

| Area | Files | Required direction |
| --- | --- | --- |
| Durable model and compatibility | `diagram/DiagramModel.kt`, `diagram/DiagramSpecCodec.kt` | Add only the state needed for targetless and user-edited manual messages. Decode all existing diagram notes/drafts unchanged. |
| Seed and regeneration | `diagram/ManualDiagramSeedService.kt`, `ui/SeqDiagramCoordinator.kt` | Preserve individual user edits; generate a reviewable diff instead of replacing the entire document. |
| Message list/editor | `ui/SeqDiagramManualEditor.kt`, `ui/SeqDiagramInspector.kt` | Replace the interaction-card-first flow with a compact message queue and a workspace-local guided mode. |
| Canvas behavior | `ui/SeqDiagramWorkspace.kt`, `diagram/SeqDiagramRenderer.kt`, `diagram/ManualDiagramBuilder.kt` | Add targetless stubs and stable message hit identity for row/canvas synchronization. |
| Shared styling and shortcuts | `ui/Components.kt`, `ui/Theme.kt`, `ui/Shortcuts.kt` as needed | Reuse `AppButton`, `AppText`, `SectionHeader`, theme colors (`tc()`), existing corners, and the app's keyboard-dispatch conventions. No web-style palette or standalone design system. |
| Tests | existing diagram/workspace/codec test files, plus focused new tests | Put pure behavior in UI-free helpers where possible; avoid testing raster pixels. |

Preserve these invariants:

- The `diagram` package remains Compose-free.
- Raw tag ids and `sourceEntryIds` remain the provenance identity; aliases are display-only.
- Existing notes and draft documents stay readable. New codec fields are optional/defaulted; do not modify the `.ann` file format or unrelated autosave tokens.
- Preview work stays in the coordinator's latest-only/background pipeline. Do not build a diagram during Compose composition.
- A source log may be closed; cached diagrams must remain viewable, and editing/regeneration must remain correctly disabled until relinked.
- Do not overwrite or revert the user’s existing uncommitted work.

## Proposed model contract

Implement the model change before the UI. Keep the persisted shape minimal.

1. Introduce a targetless manual interaction without using a fake participant id. Prefer a nullable destination (`toParticipantId: String?`) and update the builder/codec validation accordingly. A missing destination means **needs target**; it is not a self-call and it is not hidden.
2. Add an append-only authoring marker on `ManualDiagramInteraction`, with old documents decoding as `AUTO`. A minimal enum is sufficient: `AUTO` and `EDITED`. Derive `NEEDS_TARGET` from `toParticipantId == null`; do not persist it as a competing mutable state.
3. Keep visibility (`enabled`) independent. A hidden interaction retains its evidence and target, renders no normal arrow, and appears struck through/muted in the queue.
4. Give arrow hit-testing a stable manual interaction/group identity in addition to the existing entry id and drawing bounds. Do not rely on list index after grouping, filtering, sorting, or regeneration.
5. Add pure, deterministic helpers for:
   - grouping and the display template for a message group;
   - deriving the visible state (`needs target`, `edited`, `auto`, `hidden`);
   - filtering/sorting a view without changing `ManualDiagramInteraction.order`;
   - selecting/deselecting a whole group and deriving applicable bulk actions;
   - deterministic target suggestions from source evidence;
   - matching the old auto-generated set to a newly seeded set for regeneration review.

Mark an interaction `EDITED` whenever the user changes an endpoint, kind, label/operation/parameters, visibility, group membership, fragment/note membership, or creates it manually. Applying a suggested target is an edit. A purely derived display-template refresh is not.

## Implementation sequence

### 0. Baseline and safety

1. Inspect `git status --short` and identify pre-existing changes; preserve them.
2. Use IDEA MCP for project exploration, builds, inspections, and test execution.
3. Run and record focused baseline tests for `DiagramBuilderTest`, `DiagramSpecCodecTest`, `DiagramWorkspaceSessionTest`, and `ManualDiagramSeedServiceTest`. Do not hide pre-existing failures.
4. Read `CLAUDE.md` and `docs/SAAD.md` before changing persistence, rendering, or workspace state.

### 1. Durable targetless/edit state and renderer support

1. Extend `ManualDiagramInteraction` and `DiagramSpecCodec` as described above. Bump the diagram-note schema only if the codec requires it; keep the decoder compatible with every prior supported version.
2. Update `ManualDiagramBuilder` so targetless enabled interactions become a valid `SeqDiagram` representation rather than being dropped or treated as a self-message.
3. Update `SeqDiagramRenderer` to draw a short dashed outgoing stub from the source lifeline with a clear unresolved marker/label treatment. It must have a normal stable hit target. Do not draw a fabricated destination lifeline or an ordinary call arrow.
4. Retain the established active theme through `DiagramTheme`; do not hard-code the beige/green colors from the proposal.
5. Add codec/model/builder/renderer-layout tests for old document decoding, new round trips, a targetless message, hidden targetless message, and stable hit identity.

### 2. Message-queue domain helpers

Create UI-free or `internal` testable helpers that adapt the existing `ManualDiagramDocument` to the proposal's message rows.

1. Reuse existing `groupKey` semantics for seed-created near-identical occurrences. Derive a readable template from the normalized message and expose occurrence count and representative evidence. Do not destroy individual occurrence evidence.
2. Define queue filters: **All**, **Needs target**, **Edited**, and **Hidden**, plus text filtering. Define stable sort views: log order, lifeline, occurrence count, and state. No sort writes to `order`.
3. Add a bounded target-suggestion helper. It may suggest the next distinct, mapped tag on the same PID/TID near the representative source entry; it must never silently apply a target, invent a lifeline, use missing thread metadata, or replace explicit source/rule evidence.
4. Define bulk transformations that operate on selected interaction ids and return a copied document: set source, set target, hide/show, merge into a reversible group, create a frame/group, and create a note. Reject invalid selections and leave the document unchanged.
5. Make "merge" reversible by retaining each occurrence and a group identity; ungrouping must restore independent rows and evidence. Existing structural `ManualDiagramGroup` remains for UML frames and must not be conflated with occurrence grouping.
6. Add unit tests for grouping, token/template display, each filter/sort, suggestions, each bulk action, reversibility, and no-op invalid cases.

### 3. Replace the interaction list with the compact message queue

Refactor `ManualInteractionEditor` in `SeqDiagramManualEditor.kt`; keep `DiagramAuthoringSection` as the integration point instead of adding another inspector.

1. Replace the draggable card list with a bounded, scrollable queue headed **Messages** and a count.
2. Add the unresolved-work banner: `N messages need a target` plus **Fix these**. Hide the banner once `N == 0`.
3. Add filter chips, a text field, and a sort selector. They are workspace-local view state and must survive harmless recomposition but need not be persisted in a draft.
4. Each compact row must include:
   - a checkbox/selection affordance;
   - generalized label/template and `×n` count;
   - visible source and destination chips/dropdowns separated by an arrow;
   - state text/badge (`needs target`, `edited`, `auto`) and a hidden treatment;
   - evidence summary that opens/log-navigates through an explicit action, not an implicit loss of selection;
   - an expand/details affordance only for secondary fields (label, kind, visibility, parameters, occurrence evidence), never as the sole way to set endpoints.
5. Remove message-list drag/reorder code and tests. Do not remove lifeline drag/reorder.
6. When there is a multi-selection, replace the normal footer with a persistent action bar: **Set from**, **Set target**, **Merge**, **Group as fragment**, **Hide/Show**, **Add note**, and **Esc**. Ensure every action’s preconditions are visible and disabled rather than silently failing.
7. Use existing Indagium controls and density. Match the app screenshot’s compact inspector/canvas composition, not the proposal's web-page styling.

### 4. Guided target pass and keyboard behavior

Implement a workspace-local `GuidedTargetPass` state owned by the workspace/coordinator, not a stack of dialogs.

1. Enter from **Fix these** or `F`; construct a stable ordered snapshot of unresolved message-group ids. On each successful edit, advance to the next still-unresolved group; update progress and finish cleanly when none remain.
2. Replace the queue region while active with a focused card showing source line/timestamp/tag, occurrence count, the generalized label, and three surrounding log lines when available.
3. Show numbered target choices from enabled lifelines; preselect the evidence-based suggestion if one exists, but never apply it without user confirmation. Include **New lifeline**, **Make self-call**, **Skip**, and **Esc**.
4. Support `Enter` to accept the selected choice, `1`–`9` to choose a target, `Shift+1`–`Shift+9` only where the source-setting behavior is unambiguous, `S` to skip, and `Esc` to exit/clear. Do not steal keystrokes from active text fields.
5. Add concise tooltips/shortcut labels consistent with existing shortcut infrastructure. `J/K`, `E`, `H`, `M`, `G`, `L`, `/`, and undo may follow the proposal only after conflict checking; document any conflict and prefer current app bindings.
6. Apply-to-all must be explicit and default-safe: it may set the same destination on all occurrences in the selected message group, not unrelated groups.

### 5. Canvas-to-queue synchronization

1. On row hover, draw a lightweight Compose overlay from the arrow hit bounds for that message/group. On canvas hover, apply the same focused row treatment. Keep raster rendering deterministic and use UI overlay state for transient emphasis.
2. On ordinary arrow click, select the corresponding queue row and scroll it into view. Preserve source navigation through the row evidence action and `L`; if a modifier-click behavior is retained, document it in a tooltip.
3. Targetless stub clicks select their unresolved message. Hidden rows do not produce ordinary canvas arrows but remain discoverable in the **Hidden** filter.
4. Ensure selection/hovers are keyed by workspace id and stable interaction id so they cannot leak across diagram tabs or after a preview rebuild.
5. Add pure hit-identity and coordinate conversion tests; use focused Compose behavior tests only where the project’s existing test harness supports them.

### 6. Reviewed regeneration with protected edits

Replace the current all-or-nothing `Apply to interactions` behavior with an explicit review flow.

1. Seed a candidate manual document using the existing source trace/same-thread configuration without mutating the current document.
2. Match candidate and existing **auto** interactions by stable provenance first (`sourceEntryIds`, source method/site ids), then by deterministic normalized fallback identity. Never overwrite an `EDITED` interaction or a manually created one.
3. Produce a review model with `new`, `changed auto`, `no longer in source`, and `your edits kept` sections/counts. Show representative rows and let the user accept the calculated update or cancel.
4. Applying the review updates only safe auto interactions, preserves edited interactions and compatible structural references, records the existing one-step undo snapshot, and marks the workspace dirty. Cancel leaves every field unchanged.
5. Make an explicit **Unlock/revert to auto** action available from message details before a later regeneration. Its behavior must be clear and reversible through the same undo path.
6. Add tests for no-op cancellation, edited-target preservation, edited-label preservation, auto-row update/removal/addition, structural-reference integrity, undo, and old documents whose interactions have no authoring marker.

### 7. Verification and documentation

1. Update `docs/USER_GUIDE.md` with the message queue, guided pass, targetless stubs, multi-select actions, canvas linkage, and safe regeneration behavior only after tests define the final contract.
2. Update `docs/SAAD.md` if model persistence, renderer hit identity, or workspace state changed materially.
3. Run focused tests first, then `desktopTest`, IDEA build, and IDEA inspections for every changed Kotlin production file. Manually open a representative diagram with repeated messages, an unresolved target, hidden traffic, and an offline cached workspace.
4. Report exact results and any pre-existing failures. Do not claim completed verification otherwise.

## Acceptance criteria

- Existing diagram notes/drafts decode and render exactly as before when no new fields are present.
- An unresolved message remains source-backed, appears in the queue and count, and renders as a clickable dashed stub rather than disappearing or becoming a fake self-call.
- Setting a target takes one click from the normal queue and can be completed rapidly through the guided pass.
- Repeated occurrences remain inspectable and link to their evidence while displaying one readable `×n` message row.
- Message reordering is not available; lifeline reordering still works.
- Bulk selection has only predictable, non-destructive actions and every valid action updates the durable manual document.
- Row/canvas hover and click association survive filtering, sorting, zoom, preview rebuilds, and multiple open diagram workspaces.
- Regeneration reviews its changes, preserves per-message edits by default, supports one-step undo, and never overwrites on cancel.
- All visuals honor the active Indagium theme and reuse its shared Compose components.

## Copy-ready implementation prompt for GPT-5.6 Luna

```text
Implement the plan in docs/sequence-diagram-message-editing-ux-luna-plan.md in the current Indagium checkout.

You are modifying a dirty shared worktree. Preserve every unrelated change; do not reset, restore, reformat broadly, or overwrite existing plans. Do not push or create a PR. Use IDEA MCP for repository search, file editing, builds, test execution, and inspections. Read CLAUDE.md and the full plan before editing.

Implement the work in the plan's stated order. Keep the diagram package Compose-free; reuse Indagium's AppButton/AppText/SectionHeader/theme styles in the UI. The supplied UX proposal defines interaction behavior, but do not copy its web visual system: the final UI must look native to the existing application.

The key product requirements are: a compact message queue with visible From → To editing; targetless messages that are evidence-backed dashed stubs; an in-workspace guided target pass; explicit, safe bulk actions; no message drag-reordering; row/canvas two-way association; and reviewed regeneration that preserves individual edited messages.

Before each persistence or renderer change, inspect the existing codecs, model, builder, renderer, and tests. Maintain backward compatibility for existing notes and drafts. Do not invent lifelines or silently apply suggestions. Keep source evidence and source navigation intact.

Run the focused tests after each implementation stage. Before reporting completion, run the full desktop test suite, an IDEA build, and IDEA inspections for all changed production Kotlin files. Report changed files, test/build/inspection results, any remaining limitation, and any pre-existing failure separately.
```
