# Manual Sequence Diagram Authoring Improvement Plan

## Status

Proposal only. This document reviews the current manual authoring experience and defines implementation tasks. It does not authorize or include production-code changes.

## Decision

Manual diagram creation should be organized around this workflow:

1. choose or verify the source log rows;
2. optionally build a better editable starting point from inference;
3. edit a compact, grouped list of interactions;
4. order only the lifelines currently used by those interactions;
5. customize exceptional lifelines, frames, notes, or activations;
6. adjust presentation settings that actually affect manual rendering.

The current inspector exposes inferred-mode controls and default participant records alongside manual editing. That makes the workflow longer, duplicates information, and gives equal visual weight to advanced or ineffective controls. The redesign should be mode-aware and progressively disclose advanced features.

## Evidence from the current implementation

### 1. Lifeline order is draggable but does not look or behave like a standard collapsible section

`LifelineOrderEditor` in `SeqDiagramManualEditor.kt` has a custom header with a separate `Collapse` button. Its rows already support pointer-drag reordering and arrow-button fallback, but there is no drag handle or dragged-state affordance. This should reuse the same full-width collapsible section treatment as `Saved filters` and the other inspector sections.

### 2. Apply actions are strategies, not a configurable starting-point action

The manual editor currently shows three adjacent buttons:

- `Apply source execution trace`;
- `Apply same-thread handoffs`;
- `Revert previous apply`.

`SeqDiagramCoordinator.applyManualSeed` accepts one `ManualDiagramSeedStrategy`, replaces the complete manual document, and stores a one-step undo snapshot. The desired interaction is a small set of seed options followed by one Apply action and a Reset action. The current strategy enum also prevents the UI from selecting both sources of evidence.

### 3. Grouping and detaching already exist, but the group rows are visually broken

The underlying design is close to the requested behavior:

- seeded occurrences remain separate durable `ManualDiagramInteraction` records;
- records with the same `groupKey` are presented as one group;
- group-level edits fan out to the member records;
- an occurrence can clear its `groupKey` through `Detach` and become independently editable;
- the renderer includes only participants referenced by enabled interactions, so unused lifelines disappear and reappear when an interaction references them again.

The empty rows in the screenshot have a concrete layout cause. `CheckRow` always applies `fillMaxWidth()`, but `ManualInteractionGroupRow`, its occurrence rows, and `ManualInteractionCard` place `CheckRow` inside another `Row` beside text and actions. The full-width child consumes the row and pushes the useful siblings outside the inspector. `SeqDiagramInspector.kt` already documents this exact Compose failure mode in its Unmapped rows section.

Grouping also needs stronger semantic normalization. `manualInteractionGroupKey` currently:

- prefers source method/site provenance without also distinguishing endpoints or message kind;
- otherwise groups by source participant, destination participant, message kind, and a regex-normalized label;
- strips some numeric and `name=value` values, but does not consistently split a base operation/log template from occurrence parameters.

This is enough for a first grouping pass, but not enough for a reliable “same log or method, different parameters” editor.

### 4. Manual structure actions are technically valid but are exposed too early

`Add group`, `Add note`, and `Add activation` create real UML constructs. The problem is their placement and editing model:

- a group is edited through comma-separated internal interaction IDs;
- a note requires an interaction anchor and participant, but the action is detached from the interaction row;
- an activation requires start/end interaction IDs and a participant, but the action is detached from a selected span;
- `Refresh preview` duplicates the normal `onSpec` rebuild path;
- `Reset manual` clears the whole manual document, while `Revert previous apply` has a different reset meaning.

These actions should become contextual or advanced actions, not a permanent primary button strip.

### 5. Active tags and Components duplicate default lifelines

`ParticipantsSection` says that plain single-tag components should remain in `Active tags`, but `componentCards` is currently assigned all real components. Consequently, every default one-tag lifeline appears both as an active-tag pill and as a large component card.

A component card is useful only when a record has something exceptional to edit, such as:

- a custom display name/alias;
- two or more tags grouped into one lifeline;
- source-owner mapping;
- another explicit non-default customization.

The default `displayName == tag`, one-tag, unmapped record should consume no card space.

### 6. Inferred controls remain visible even though the manual builder ignores them

`WorkspaceInspector` always renders `ModeSection` and `OptionsSection`. However, `buildSequenceDiagram` returns directly through `buildManualSequenceDiagram` when authoring mode is manual. In that path, component-flow/rules/timeline selection, source enrichment, handoff inference, repeat collapsing, inferred-source visibility, inferred error notes, and similar generation controls are not consumed as inferred-mode controls.

Manual mode needs a smaller, audited presentation surface instead of the entire inferred-mode inspector.

## Proposed manual-mode information architecture

### 1. Selected rows

Move `Scope / Range` to the first content block below the workspace title/mode switch.

- Keep Selection, Whole view, and Time.
- Show the resolved row count and bounds in the collapsed header.
- Keep this block expanded by default for a newly opened workspace.
- Changing the range must update both candidate lifelines and the editable seed scope.

### 2. Build starting point

Replace the three seed buttons with one collapsible block:

- checkbox: `Reconstruct source execution trace`;
- checkbox: `Infer same-thread handoffs (PID + TID)`;
- primary action: `Apply to manual lines`;
- secondary action: `Reset`;
- inline progress, result, or failure status.

Behavior:

- Apply rebuilds the starting manual interactions from the current selected rows and selected evidence options.
- A complete source trace remains the semantic owner of calls/returns/activations. Handoffs may support fallback or uncovered log-only interactions; the two outputs must not be blindly overlaid.
- If the manual document contains user edits made after the last seed, Apply must ask before replacing them.
- Reset restores the snapshot from immediately before the latest Apply. It must not mean “delete all manual content.”
- A separate danger action, `Clear all manual lines`, may exist under an overflow/advanced area with confirmation.
- The checkbox state is workspace UI state; it does not need to become part of the saved diagram format unless product behavior later requires it.

### 3. Manual lines

Make the grouped interaction list the main editor. A collapsed row should have the following compact columns:

| Column | Behavior |
|---|---|
| Enabled | Tri-state group checkbox; individual occurrence checkboxes appear when expanded. |
| Message / method | Detected method name when reliable; otherwise normalized log text without volatile parameters. |
| From | Lifeline dropdown. |
| To | Lifeline dropdown. |
| Type | Call, return, self, or async dropdown. |
| Visibility | Unspecified, public, protected, package, or private dropdown. |
| Count / expand | `xN` for grouped occurrences and a full-row disclosure control. |
| Reorder | Drag handle with keyboard/arrow fallback. |

Expanded content should show:

- the group-level editable fields;
- additional optional values such as result text and formatted parameters;
- the occurrence list with source row ID, timestamp when available, original log text, and occurrence-specific parameters;
- per-occurrence enable/disable;
- `Move out` (current `Detach`) to turn one occurrence into its own editable row;
- an individual edit action that either moves the occurrence out automatically or clearly warns that a group edit affects every member.

Group edits must preserve occurrence-specific parameters unless the user explicitly replaces them for the whole group.

### 4. Lifeline order

Render this as a normal `SectionHeader` block with the active count and disclosure caret.

- Show only lifelines referenced by enabled manual interactions.
- Preserve the full durable order so a currently unused lifeline returns to its previous relative position when referenced again.
- Use a visible drag handle and dragged-row state, following the saved-filter reordering interaction.
- Keep up/down or keyboard actions as an accessibility fallback.
- Persist the order at drop/end, not on every pointer delta.

### 5. Lifelines

Replace the duplicate participant sections with progressive disclosure:

- `Active lifelines`: compact pills for the lifelines used by current manual lines, plus search/add for another tag or actor.
- `Lifeline customizations`: only aliases, multi-tag component groups, source mappings, and other non-default records.
- `Actors`: collapsed by default when empty; expose an Add actor action without rendering empty default cards.
- `Available but unused`: optional collapsed list if users need to prepare a lifeline before assigning it to a line.

Do not render a card for a default one-tag component whose name equals its tag and which has no source mapping or other customization.

### 6. Advanced structure

Keep the functionality, but make it contextual:

- `Add frame/group` appears after selecting two or more manual rows and creates a frame around that selection. Do not expose comma-separated interaction IDs.
- `Add note after` is an action on an interaction/group row and opens the note editor already anchored to that row.
- `Add activation` appears after selecting a participant and start/end interaction span, or under a collapsed Advanced structure block.
- Existing frames, notes, and activations remain editable and removable in the Advanced structure block.
- Hide unavailable actions until their required anchors exist.

### 7. Presentation

Audit every setting against the manual build and render paths.

Keep settings that demonstrably affect manual output, such as output format and applicable label/lifeline wrapping limits. Keep manual activation visibility if manual activation spans remain supported. Hide inferred-only and no-op controls in manual mode.

The audit must classify each current control as one of:

1. effective and shared by both modes;
2. effective only in inferred mode;
3. intended for manual mode but currently not wired;
4. obsolete/no-op and removable.

Do not leave a visible manual-mode control unless a focused test proves its effect.

## Group identity and parameter model

The existing durable model can support the requested editor; a second manual-document format is not required. Refine seed-time parsing and grouping as follows.

### Display identity

For each seeded occurrence, derive:

- `operation`: detected method name/signature when source confidence is sufficient; otherwise normalized message text with volatile arguments removed;
- `parameters`: values extracted from the log template/message or source call when available;
- `label`: only a literal user override, not a duplicate of the generated operation label;
- provenance: source method, source log site, source owner, and source row IDs.

### Group key

Group occurrences by a semantic key that ignores only occurrence values:

```text
source-backed:
  source method/site identity + normalized operation + from + to + kind

log-backed:
  source lifeline/tag + normalized message template + from + to + kind
```

Visibility should normally be a group-editable attribute rather than part of the initial identity. If one occurrence is intentionally changed to a different visibility, moving it out creates the separate entity.

This prevents both failure modes:

- under-grouping the same log solely because IDs or parameter values differ;
- over-grouping different directions or message kinds merely because they share one source site.

### Compatibility

- Preserve existing persisted `groupKey` values when opening old diagrams.
- New seeds use the refined key.
- Do not silently regroup a user-edited durable document on open.
- Codec additions, if any become necessary, must be optional with safe defaults and round-trip tests.

## Button decisions

| Current action | Decision |
|---|---|
| Apply source execution trace | Replace with a seed checkbox plus one Apply action. |
| Apply same-thread handoffs | Replace with a seed checkbox plus one Apply action. |
| Revert previous apply | Rename/reframe as Reset in the Build starting point block. |
| Add interaction | Keep as `Add manual line` at the end of the interaction list. |
| Add group | Keep only as selection-driven `Add frame/group`. |
| Add note | Keep as contextual `Add note after`. |
| Add activation | Keep as an advanced span/participant action. |
| Refresh preview | Remove from the normal path; preview already rebuilds from spec changes. Offer Retry only in a failed preview state. |
| Reset manual | Replace with confirmed `Clear all manual lines` in the danger/overflow area. |

## Implementation tasks

### Phase 0 — Restore visible interaction rows

- [ ] **M0.1** Replace nested full-width `CheckRow` usage in manual group headers, occurrence rows, and interaction cards with a compact checkbox control that accepts a caller-owned modifier/width.
- [ ] **M0.2** Add a regression test or screenshot test proving that the operation label, count, actions, and checkbox are simultaneously visible in the inspector width used by the reported screenshot.
- [ ] **M0.3** Add mixed-enabled group behavior with an explicit indeterminate state instead of treating “some enabled” as simply unchecked.

Primary files: `ui/SeqDiagramManualEditor.kt`, optionally `ui/Components.kt`, UI-focused tests.

### Phase 1 — Make the inspector mode-aware and reorder sections

- [ ] **M1.1** Move Range/Selected rows to the first manual-mode content block.
- [ ] **M1.2** Define separate inferred-mode and manual-mode section composition rather than rendering every section unconditionally.
- [ ] **M1.3** Hide `ModeSection` inference controls in manual mode and route initial inference only through Build starting point.
- [ ] **M1.4** Preserve section expansion state per workspace without putting ephemeral disclosure state in the diagram codec.

Primary file: `ui/SeqDiagramInspector.kt`.

### Phase 2 — Replace seed strategy buttons with Apply/Reset options

- [ ] **M2.1** Introduce an internal seed request/configuration that can represent source trace and same-thread handoffs independently.
- [ ] **M2.2** Define deterministic precedence: a complete source trace owns semantic structure; handoffs are fallback evidence, never duplicate overlays.
- [ ] **M2.3** Implement the collapsible Build starting point UI with two checkboxes, Apply, Reset, and inline status.
- [ ] **M2.4** Track whether manual edits occurred after the last seed and confirm before replacing them.
- [ ] **M2.5** Retain exactly one pre-Apply undo snapshot per workspace and make Reset restore it.
- [ ] **M2.6** Keep apply work cancellable/latest-only and preserve the existing “no inferred interactions means keep manual content” safety behavior.

Primary files: `diagram/ManualDiagramSeedService.kt`, `ui/SeqDiagramCoordinator.kt`, `ui/SeqDiagramManualEditor.kt`, workspace session tests.

### Phase 3 — Build the compact grouped interaction editor

- [ ] **M3.1** Create a UI projection for grouped rows with stable group identity, representative display values, member count, aggregate enabled state, and stable ordering.
- [ ] **M3.2** Replace the card-first layout with the compact row/column design specified above.
- [ ] **M3.3** Add dropdown editors for From, To, Type, and Visibility at group level.
- [ ] **M3.4** Expand a group into occurrence rows showing source evidence and occurrence parameters.
- [ ] **M3.5** Rename `Detach` to `Move out`; preserve the detached occurrence's data and relative order.
- [ ] **M3.6** Ensure group edits fan out only the intended shared fields and do not erase occurrence-specific parameters or provenance.
- [ ] **M3.7** Add drag reordering for groups/individual rows with the saved-filter interaction pattern and accessible fallback controls.
- [ ] **M3.8** Keep `Add manual line`, with sensible defaults based on the nearest row or active lifelines rather than always choosing the first lifeline twice.

Primary files: `ui/SeqDiagramManualEditor.kt`, focused UI/state tests.

### Phase 4 — Refine grouping and seed-time parameter extraction

- [ ] **M4.1** Define and test normalized operation/message templates for common Android log forms: numbers, IDs, hex values, quoted strings, `name=value`, `name: value`, argument lists, and source method calls.
- [ ] **M4.2** Populate `operation` and `parameters` structurally during seeding instead of copying the complete rendered label into both `operation` and `label`.
- [ ] **M4.3** Refine `manualInteractionGroupKey` to include provenance/template plus endpoints and message kind while excluding occurrence values.
- [ ] **M4.4** Preserve old persisted group keys and avoid automatic regrouping on document load.
- [ ] **M4.5** Add tests for same tag/text with different parameters, same source site with different direction/type, source-detected method grouping, and deliberate Move out behavior.

Primary files: `diagram/ManualDiagramSeedService.kt`, `diagram/DiagramModel.kt` only if documentation/model semantics need clarification, codec compatibility tests.

### Phase 5 — Remove default component-card duplication

- [ ] **M5.1** Define one predicate for a default one-tag lifeline versus a customized lifeline record.
- [ ] **M5.2** Keep default lifelines in Active lifelines only; show cards only for aliases, grouped tags, source mappings, or other custom state.
- [ ] **M5.3** Rename `Components` to a term that reflects its remaining purpose, such as `Lifeline customizations` or `Grouped lifelines & mappings`.
- [ ] **M5.4** Derive the visible lifeline-order list from enabled manual interactions while preserving dormant order entries.
- [ ] **M5.5** Verify that a lifeline with no enabled lines is absent from the rendered diagram and reappears in its prior order when a line references it again.

Primary files: `ui/SeqDiagramInspector.kt`, `ui/SeqDiagramManualEditor.kt`, `diagram/ManualDiagramBuilder.kt` tests.

### Phase 6 — Make advanced structure contextual

- [ ] **M6.1** Replace comma-separated group IDs with selection-driven frame creation.
- [ ] **M6.2** Move note creation to an interaction-row action with a preselected anchor.
- [ ] **M6.3** Move activation creation to a selected participant/span action and hide it when prerequisites do not exist.
- [ ] **M6.4** Keep a collapsed Advanced structure editor for existing frames, notes, and activations.
- [ ] **M6.5** Remove the normal Refresh preview button and provide Retry only for an actual preview failure.
- [ ] **M6.6** Separate Reset-to-pre-Apply from confirmed Clear-all-manual-content.

Primary files: `ui/SeqDiagramManualEditor.kt`, coordinator error/retry handling if needed.

### Phase 7 — Audit manual presentation controls

- [ ] **M7.1** Build a control-to-consumer matrix covering `SeqDiagramInspector`, `buildManualSequenceDiagram`, the renderer, and both emitters.
- [ ] **M7.2** Hide every inferred-only/no-op control in manual mode.
- [ ] **M7.3** Add focused tests for every setting retained in manual mode.
- [ ] **M7.4** Document intentionally unsupported inferred features rather than presenting inactive controls.

Primary files: `ui/SeqDiagramInspector.kt`, diagram renderer/emitter tests, `docs/USER_GUIDE.md`.

### Phase 8 — End-to-end quality gate

- [ ] **M8.1** Add unit tests for seed configuration, grouping identity, parameter normalization, group edits, Move out, and one-step Reset.
- [ ] **M8.2** Keep codec round-trip and legacy-default coverage for existing manual records.
- [ ] **M8.3** Add integration coverage for selected-row changes, auto preview, dormant/reappearing lifelines, frames, notes, and activations.
- [ ] **M8.4** Manually validate narrow and wide inspector widths, long tag/method names, 1/10/100+ occurrences, keyboard access, drag cancellation, and mixed enabled state.
- [ ] **M8.5** Update the user guide only after final UI wording and behavior are stable.

## Acceptance criteria

1. The first manual-mode content block identifies the selected source rows.
2. Lifeline order is a full collapsible block and supports visible drag-and-drop reordering.
3. The starting-point block offers source-trace and same-thread options with one Apply and one Reset action.
4. No manual interaction row can render as an unexplained checkbox-only line.
5. Equal semantic logs/methods with different parameter values appear as one `xN` row.
6. Expanding a group exposes every occurrence and its source evidence.
7. Editing a group updates shared fields for all members without erasing occurrence parameters.
8. Moving one occurrence out creates a separately editable row.
9. From, To, Type, and Visibility are directly editable with bounded choices.
10. Lifelines with no enabled interactions are absent from the diagram and return automatically when referenced again.
11. Default one-tag lifelines are not duplicated as large component cards.
12. Frame, note, and activation creation is contextual and does not require typing internal IDs.
13. Preview updates automatically after valid edits; no routine Refresh button is needed.
14. Manual mode shows no inferred-only or proven no-op settings.
15. Existing saved manual diagrams continue to decode and render without automatic regrouping or data loss.

## Recommended delivery order

Implement Phase 0 first because it restores the currently hidden rows with low semantic risk. Then deliver Phases 1–3 as one coherent UX slice, followed by grouping/parameter correctness in Phase 4. Phases 5–7 remove duplication and secondary complexity. Phase 8 is the release gate, not a cleanup pass.
