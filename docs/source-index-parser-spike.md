# Source-index parser spike

This spike records the frontend choice for the source-first sequence trace index.

| Frontend | Kotlin/Java coverage | Desktop cost | Symbol resolution | Decision |
|---|---|---:|---|---|
| Kotlin compiler analysis API | Strong Kotlin coverage; Java requires a second frontend | High startup/dependency cost; compiler versions must track the app | Strong when configured with a project classpath | Rejected for the standalone desktop indexer because it makes indexing fragile and expensive |
| JavaParser / Java compiler tree | Strong Java coverage; no Kotlin execution model | Moderate dependency and classpath setup | Good for Java, incomplete for Kotlin/Android source | Rejected as a single frontend |
| IntelliJ PSI/UAST | Strong mixed-language analysis inside the IDE | Not suitable for the shipped desktop runtime | Strong only when an IDE project/classpath is present | Kept as an IDEA inspection aid, not the runtime indexer |
| Dependency-free masked structural scanner | Conservative Kotlin/Java declarations, calls, source order, and log templates | Small, deterministic, incremental, no runtime dependency | Deliberately bounded; unresolved overloads remain candidates/diagnostics | Selected for the shipped desktop runtime |

The selected frontend is `SourceStructureParser` plus the source-aware call and operation passes in
`SourceIndexer`. It is intentionally conservative: an unresolved or tied call is retained as
ambiguity rather than promoted to a diagram edge. The index schema records source offsets, receiver
bindings, candidate method IDs, operation successors, return/throw locations, and source-set
provenance so a future compiler-backed frontend can replace the scanner without changing the trace
solver contract.

Fixture accuracy is gated by `SourceIndexerTest`, `SourceIndexerGoldenTest`, and
`SourceTraceInferenceTest`. The canonical Kotlin and Java fixtures cover method ownership, field
receiver binding, nested calls, ordered log anchors, return/result correlation, cache persistence,
and PID/TID-independent reconstruction.
