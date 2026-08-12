# Source-First Sequence Trace Reconstruction Plan

## Decision

The current sequence-diagram inference path must not be incrementally tuned further as the primary solution.

It is built around this model:

1. create one diagram message from each log row;
2. resolve each row to a source log site;
3. find at most one nearby or incoming source call;
4. overlay that call onto the log-row message;
5. infer activation blocks from the resulting messages.

That model cannot produce a source-accurate execution diagram. It decorates a log diagram with one-hop guesses. The required feature instead needs to reconstruct an interprocedural execution path from source code, using ordered log rows as runtime anchors.

The implementation must therefore introduce a new source-first trace engine and retire the one-hop overlay path from source-enabled diagram generation.

## Required Result

Given indexed source code and selected logs, the engine must:

- locate every selected log statement in its owning method and class;
- build the method-call graph and relevant control-flow order from the source;
- identify which object/class calls the method containing the first log;
- walk through calls, nested calls, returns, callbacks, and subsequent log statements;
- use log order, values, timestamps, PID/TID, and source ownership to select the compatible execution path;
- emit call and return messages from source call-stack transitions;
- emit every selected log line at its actual execution point;
- derive activations directly from the reconstructed call stack;
- avoid participants that are not part of the reconstructed path.

PID/TID is additional runtime evidence. It must not be required for ordinary source-proven calls such as a controller field calling a service method.

## Why the Current Implementation Produces Incorrect Calls

### 1. It performs one-hop lookup instead of graph traversal

`SourceTraceInferenceEngine.resolve` iterates resolved log events and calls:

```text
sourceResolver.resolveOneHop(entry)
```

This inspects direct calls attached to that log site. It does not search paths through `SourceIndex.methods` and `SourceIndex.calls`.

As a result, it cannot reconstruct:

```text
Controller.start()
  -> Service.run()
       -> Repository.load()
       <- result
  <- service result
```

It can only attach a nearby edge to one log row at a time.

### 2. “Range-level” currently means candidate scoring, not execution tracing

The beam search selects which source log site best matches each log row. Its cross-row score uses:

- same method;
- same owner;
- PID/TID continuity;
- one direct reachability check from the previous matched site.

It does not maintain:

- current method and source offset;
- an invocation stack;
- caller return locations;
- branch/control-flow position;
- nested call state;
- object/receiver binding;
- async continuation state.

Therefore the selected source sites can be individually plausible while the combined diagram is not a valid execution path.

### 3. Logs and method calls are incorrectly treated as the same thing

A log statement is an observed event inside a method. A method call is a source operation that enters another method. They may be adjacent, but they are not the same event.

`promoteUniqueSourceCalls` overlays inferred endpoints and message kind onto a runtime log message. This conflates:

- “this log executed inside Service.run()”; and
- “Controller called Service.run()”.

The result loses the real execution structure and makes call direction dependent on which log row was chosen as the overlay target.

### 4. Return inference does not use real call-stack transitions

For a result log, the current inference may create a call and return using the same log entry as both boundaries. Even when rendering later suppresses a zero-duration activation, the semantic trace is still wrong.

Correct behavior requires:

1. find the call expression that produced the logged value;
2. enter its callee;
3. place any callee logs inside that invocation;
4. return to the caller's source location after the call;
5. associate the caller's result log with the returned value.

### 5. Activation blocks are reconstructed too late

Activations are currently inferred from final diagram messages. By then logs, inferred calls, actor relays, filtering, and collapsing have already modified the message list.

Activation is not a presentation guess. It is a direct projection of push/pop operations on the reconstructed invocation stack.

### 6. The source index is not yet a semantic execution index

Although the index now stores methods and candidate call edges, call discovery still depends heavily on regular-expression scanning and simple name/type matching.

That is insufficient for reliable handling of:

- overloaded methods;
- interfaces and implementations;
- inheritance and overrides;
- extension functions;
- constructors and dependency injection;
- Kotlin properties;
- lambdas and callbacks;
- coroutines;
- nested/qualified calls;
- generic receiver types;
- same simple class names in different packages.

### 7. Passing unit tests do not establish diagram correctness

The focused source and diagram suites currently pass, but they mainly validate isolated candidate resolution and message transformation. They do not prove that the generated sequence is a legal path through the indexed program.

The new quality gate must validate a complete execution path from source fixture to semantic diagram.

## Architectural Rule

When source enrichment is enabled and a compatible source index is available:

- the source-first trace engine owns call direction, call nesting, returns, and activations;
- log rows are anchors/observations inside that trace;
- tags are labels and fallback mapping evidence, not the source of call direction;
- PID/TID refines lane and ambiguity resolution, not basic source-call discovery;
- the legacy evidence-flow/one-hop builder is not mixed into the semantic result.

When source enrichment is disabled or no compatible trace can be reconstructed:

- the existing log-only/evidence-flow behavior remains the explicit fallback;
- the UI must state that the diagram is using fallback mode.

## Target Model

### Source execution index

The index must contain enough semantic information to walk executable paths.

```text
SourceType
  id
  qualifiedName
  sourceSet
  superTypes
  fields/properties and declared types

SourceMethod
  id
  ownerTypeId
  signature
  parameters
  returnType
  bodyRange

SourceOperation
  id
  methodId
  sourceOrder
  kind: LOG | CALL | RETURN | THROW | BRANCH | MERGE | ASYNC_DISPATCH
  successors

SourceCallSite
  operationId
  receiver expression
  receiver binding
  declared/runtime candidate types
  candidate callee method IDs
  result variable
  invocation kind

SourceLogSite
  operationId
  tag matcher
  message-template matcher
  referenced variables
```

### Runtime anchor

Each selected log entry maps to one or more candidate `SourceLogSite` nodes:

```text
RuntimeLogAnchor
  entryId
  timestamp/order
  pid/tid if present
  sourceLogSiteCandidates
  extracted dynamic values
```

### Reconstructed trace

```text
ExecutionTrace
  lanes
  ordered TraceOperation list
  invocation tree
  selected-log coverage
  ambiguity diagnostics

TraceOperation
  ENTER_METHOD
  SOURCE_CALL
  LOG_EVENT
  SOURCE_RETURN
  THROW
  ASYNC_HANDOFF
```

Each `LOG_EVENT` references exactly one selected entry ID. Calls and returns are separate structural operations.

## Correct Reconstruction Algorithm

### Step 1 — Resolve log anchors

For every selected log row:

1. match tag and message template against indexed log sites;
2. validate dynamic placeholders when values can be extracted;
3. record all viable source locations;
4. do not select each location independently.

Candidate selection happens as part of the whole-path search.

### Step 2 — Build a constrained interprocedural graph

For the methods containing candidate log sites:

1. include their callers recursively up to a configurable bounded depth;
2. include callees reachable between selected log anchors;
3. include source-order/control-flow edges inside each method;
4. include call-enter and return-to-caller edges;
5. classify synchronous, coroutine, callback, executor, and IPC edges.

The graph is bounded by relevant anchors and method reachability, not by “one hop per log”.

### Step 3 — Find a valid path through all ordered anchors

Search for a path that visits the selected anchors in runtime order.

The search state must contain:

- current method;
- current source operation;
- invocation stack;
- logical lane;
- receiver/object binding when known;
- visited runtime-anchor index;
- accumulated confidence and ambiguity.

Allowed transitions:

- next operation in the same method;
- branch to a compatible successor;
- enter a callee at a call site;
- return to the caller after a callee return;
- throw/unwind;
- create an async handoff/continuation.

The best path must be globally consistent. A locally best log-site match must be rejected if it cannot participate in one legal path through the remaining logs.

### Step 4 — Reconstruct the beginning of the window

If the first selected log is inside `Service.run()`, search incoming call edges to determine how execution entered it.

- One compatible caller chain: reconstruct it.
- Several caller chains: use later/earlier anchors, source order, receiver binding, and PID/TID.
- Still ambiguous: show the shared/known portion and an explicit ambiguous-entry boundary.
- Never invent all callers as simultaneous calls.

For a field call:

```kotlin
class Controller(private val service: Service) {
    fun start() {
        service.run()
    }
}
```

the index must bind `service` to `Service`, resolve `run()`, and produce `Controller -> Service` without PID/TID evidence.

### Step 5 — Reconstruct returns from control flow and values

For:

```kotlin
val result = service.run()
log("result=$result")
```

the trace must contain:

```text
Caller -> Service: run()
...events inside Service.run()...
Service --> Caller: result
Caller -> Caller: original selected result log
```

The selected log remains visible. The return is derived from the call-stack transition and def-use relationship, not created by changing the log itself into a return.

If the return value is included in the log, it may label the return arrow as well, but the log-entry identity must still be represented by the log event.

### Step 6 — Generate the diagram from the execution trace

The diagram builder must consume `ExecutionTrace`:

- `SOURCE_CALL` becomes a call arrow;
- `SOURCE_RETURN` becomes a return arrow;
- `LOG_EVENT` becomes a visible event on the owning participant;
- `ASYNC_HANDOFF` becomes an async message;
- invocation-tree nodes become activation blocks.

No source-call overlay onto a log message is allowed.

## Ordered Implementation Phases

## Phase 0 — Replace the Acceptance Tests

Before engine implementation, add end-to-end source fixtures that assert the entire semantic sequence.

### Canonical fixture

```kotlin
class Controller(private val service: Service) {
    fun start() {
        log("controller start")
        val value = service.run()
        log("controller result=$value")
    }
}

class Service(private val repository: Repository) {
    fun run(): Value {
        log("service start")
        val value = repository.load()
        log("service value=$value")
        return value
    }
}

class Repository {
    fun load(): Value {
        log("repository load")
        return Value("42")
    }
}
```

### Expected semantic sequence

```text
Controller LOG controller start
Controller -> Service run
Service LOG service start
Service -> Repository load
Repository LOG repository load
Repository --> Service Value(42)
Service LOG service value=42
Service --> Controller Value(42)
Controller LOG controller result=42
```

All selected logs must remain present with their entry IDs.

### Required variants

- PID/TID missing.
- PID/TID present.
- Only inner logs selected.
- Only the caller result log selected.
- Two possible callers with one path supported by later logs.
- Two genuinely indistinguishable callers.
- Nested and recursive calls.
- Exception unwinding.
- Coroutine/callback handoff.
- Disabled tag.
- Actor relay.
- Test class with a similar method/log.

### Gate

Tests must fail against the current one-hop engine for the correct reasons. Do not weaken expected sequences to match current output.

## Phase 1 — Build the Semantic Source Index

### Work

1. Replace regex-only execution discovery with syntax-tree-based Kotlin and Java indexing.
2. Keep regex matching only for log-message templates when appropriate.
3. Index type ownership, fields/properties, methods, calls, returns, throws, and source order.
4. Resolve receiver bindings and candidate callee method IDs.
5. Record control-flow successors needed to walk between log sites.
6. Record async invocation kinds separately from synchronous calls.
7. Record production/test/generated source provenance.
8. bump the source-index version and invalidate old caches.

### Required spike

Before choosing a parser frontend, compare available Kotlin/Java syntax and symbol-resolution approaches for:

- standalone desktop use;
- correctness for Kotlin and Java;
- dependency size/startup cost;
- incremental indexing;
- overload/receiver resolution;
- licensing and maintenance.

The spike must produce a fixture-based accuracy report. Do not select a parser only because it is easiest to add.

### Gate

For the canonical fixture, the index contains exact method nodes, call sites, receiver bindings, log sites, result variables, and return operations.

## Phase 2 — Implement the Interprocedural Path Solver

### Work

1. Build the bounded graph around candidate log anchors.
2. Search all selected anchors as one ordered constraint problem.
3. Maintain a real invocation stack in each search state.
4. Enter callees and return to exact caller continuation points.
5. Track source-order position inside methods.
6. Use PID/TID only as additional lane evidence.
7. Use extracted values to strengthen def-use and return correlation.
8. Preserve tied paths as ambiguity instead of selecting arbitrarily.
9. Bound search by graph size, depth, and beam/path count without reducing it to one-hop lookup.

### Gate

The canonical fixture produces the expected nested call/return path with PID/TID disabled.

## Phase 3 — Make the Source Trace the Diagram Truth

### Work

1. Add a dedicated `ExecutionTrace -> SeqDiagram` projection.
2. Keep log events and structural calls/returns separate.
3. Stop calling `promoteUniqueSourceCalls` for source-trace diagrams.
4. Stop using `runEvidenceFlow` as the semantic base when a source trace succeeds.
5. Use tags only for display mapping/fallback.
6. Add explicit source-trace versus fallback mode to result metadata and UI.
7. Guarantee exact selected-log coverage before rendering.

### Gate

Toggling source inference changes structural arrows but never removes selected log events. A source-success diagram contains no one-hop overlay messages.

## Phase 4 — Derive Activations from the Invocation Tree

### Work

1. Create an activation when the solver enters a synchronous method.
2. close it on return or exception unwind.
3. nest activations using invocation parent/child relationships.
4. handle recursion using distinct invocation IDs.
5. treat async dispatch as a separate activation/lane rule.
6. map invocation boundaries to final rendered operation IDs.
7. never infer activation lifetime from adjacency or pixel minimums.

### Gate

Activation spans exactly cover the operations executed inside each invocation in nested, recursive, incomplete, and exception cases.

## Phase 5 — Apply Tags, Components, Actors, and Presentation

### Work

1. Bind source owner types to diagram participants.
2. Map selected log tags to their actual source owner when resolved.
3. Exclude participants not present in the reconstructed trace.
4. Apply tag filters to log anchors, then rerun path reconstruction.
5. Apply actor mirroring after trace construction as a relay:
   - actor to mirrored component;
   - mirrored component to next participant.
6. Collapse/reformat only after semantic trace and activations are complete.
7. preserve mappings from collapsed messages back to all source operations and log IDs.

### Gate

Disabled tags cannot leak log events or unsupported participants. Actor mirroring cannot change the underlying invocation tree.

## Phase 6 — UI and MCP Parity

### Work

1. Use the same semantic index, path solver, and trace projection for IDEA-run UI and MCP.
2. Expose trace diagnostics:
   - chosen source location for every log;
   - reconstructed caller chain;
   - ambiguous paths;
   - fallback reason;
   - index version/staleness;
   - unsupported language construct.
3. Rename options so their behavior is unambiguous.
4. Make PID/TID explicitly a disambiguation/lane option, not a source-call switch.
5. Show whether the displayed diagram is source trace or fallback evidence flow.

### Gate

The same fixture and settings produce an equivalent semantic trace through UI and MCP.

## Phase 7 — Remove the Old Primary Path

After the new engine passes all acceptance tests:

1. remove `resolveOneHop` from source-enabled diagram generation;
2. remove `promoteUniqueSourceCalls` from the source-trace path;
3. retain one-hop lookup only for non-diagram features that explicitly need it;
4. retain legacy evidence flow only as the source-disabled/failure fallback;
5. remove or rewrite tests that assert the obsolete overlay semantics;
6. update `docs/SAAD.md` to describe source-first reconstruction.

## Test Strategy

### Index tests

- exact method/type ownership;
- field/property receiver binding;
- overload resolution;
- interface/implementation candidates;
- extension functions;
- constructor calls;
- nested calls;
- return/result-variable relationship;
- callbacks/coroutines;
- production/test source provenance;
- cache invalidation.

### Solver tests

- legal path through all ordered log anchors;
- caller-chain reconstruction before first selected log;
- nested stack push/pop;
- branch selection from later anchors;
- ambiguity preservation;
- exception unwind;
- async lane transitions;
- missing PID/TID;
- interleaved PID/TID lanes;
- bounded-search behavior.

### Diagram tests

- call arrows correspond exactly to `SOURCE_CALL` operations;
- return arrows correspond exactly to `SOURCE_RETURN` operations;
- every selected log entry appears exactly once as `LOG_EVENT`;
- participants equal reconstructed owner types plus explicit actors;
- activations equal invocation-tree lifetimes;
- filtering and collapsing preserve semantic IDs;
- UI/MCP semantic equality.

### Manual acceptance

For each reported real-world log:

1. export the chosen log-to-source mappings;
2. export the reconstructed invocation tree;
3. compare every call arrow with its indexed call-site file and line;
4. compare every return with its invocation;
5. verify every selected log line is visible;
6. verify no test/unrelated class appears;
7. repeat with PID/TID option disabled;
8. repeat through MCP.

## Required Build Order

1. End-to-end failing fixtures.
2. Semantic source index.
3. Log-anchor resolver.
4. Interprocedural path solver.
5. Execution trace model.
6. Source-trace diagram projection.
7. Invocation-tree activations.
8. Filters/components/actors.
9. UI/MCP parity.
10. removal of obsolete one-hop/overlay path.

Do not implement activation or presentation fixes before the solver produces a correct invocation tree.

## Definition of Done

The work is complete only when:

- the canonical source fixture reconstructs the complete nested call and return sequence;
- it produces the same source-derived calls with PID/TID disabled;
- each arrow can be traced to a specific indexed source call site;
- each return belongs to a specific invocation;
- every selected log entry is visible at its source execution position;
- activations are a direct projection of the invocation tree;
- ambiguous source paths are reported rather than guessed;
- irrelevant and test-only classes cannot appear;
- actor relay and tag filtering do not alter the underlying source trace;
- UI and MCP results are semantically equivalent;
- real user-provided logs pass manual source-line validation;
- the one-hop overlay path is no longer used for source-enabled diagrams.

## Explicitly Rejected Approaches

- More thresholds around `resolveOneHop`.
- Treating adjacent tags as calls.
- Treating PID/TID handoffs as the primary source of call direction.
- Replacing a selected log message with a method-call arrow.
- Creating call and return at the same log entry.
- Calculating activations from the rendered message list.
- Adding every indexed caller as a lifeline.
- Suppressing incoming callers to avoid test-class false positives.
- Declaring success because isolated heuristic tests pass.
- Keeping two competing semantic builders for UI and MCP.
