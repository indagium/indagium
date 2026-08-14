package com.indagium.source

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Arbitrary-but-large-enough end offset for the fixture methods below — not a meaningful specific
// value, just wide enough to cover the fixture bodies.
private const val FIXTURE_METHOD_END_OFFSET = 200

// This file used to also assert on `diagram.buildSequenceDiagram`'s output (participants/messages/
// activationSpans built FROM a resolved trace) — that pipeline was `diagram/SeqDiagramBuilder.kt`,
// deleted in the sequence-diagram v3 cutover (docs/plans/use-the-claude-design-mcp-compiled-
// lighthouse.md, phase 6): v3 (`com.indagium.diagram3`) deliberately does no source-trace
// enrichment (see `Seq3Generator.kt`'s own header), so there is no successor pipeline to assert
// those diagram-level properties against. Every test below now asserts directly against
// [SourceTraceInferenceEngine.resolve]'s [DiagramResolvedTrace] output instead — the actual unit
// under test in this package — which is exactly what each test's diagram-level assertions were
// themselves downstream of.
class SourceTraceInferenceTest {
    @Test
    fun canonicalNestedKotlinFixtureProducesSourceOrderedCallsReturnsAndEveryLog() {
        val dir = createTempDirectory("indagium-source-trace").toFile()
        File(dir, "Fixture.kt").toPath().writeText(
            """
            package fixture
            class Controller(private val service: Service) {
                fun start() {
                    Log.d("Controller", "controller start")
                    val value = service.run()
                    Log.d("Controller", "controller result=${'$'}value")
                }
            }
            class Service(private val repository: Repository) {
                fun run(): String {
                    Log.d("Service", "service start")
                    val value = repository.load()
                    Log.d("Service", "service value=${'$'}value")
                    return value
                }
            }
            class Repository {
                fun load(): String {
                    Log.d("Repository", "repository load")
                    return "42"
                }
            }
            """.trimIndent(),
        )
        val index = SourceIndexer.build(listOf(dir))
        assertTrue(index.operations.isNotEmpty())
        val entries = listOf(
            LogEntry(1, "10:00:00.001", LogLevel.I, "Controller", "controller start"),
            LogEntry(2, "10:00:00.002", LogLevel.I, "Service", "service start"),
            LogEntry(3, "10:00:00.003", LogLevel.I, "Repository", "repository load"),
            LogEntry(4, "10:00:00.004", LogLevel.I, "Service", "service value=42"),
            LogEntry(5, "10:00:00.005", LogLevel.I, "Controller", "controller result=42"),
        )
        val trace = SourceTraceInferenceEngine(index).resolve(entries)
        assertEquals(listOf(1, 2, 3, 4, 5), trace.events.map { it.entryId })
        assertEquals(
            listOf("fixture.Controller" to "fixture.Service", "fixture.Service" to "fixture.Repository"),
            trace.calls.map { it.callerOwnerType to it.calleeOwnerType },
        )
        assertEquals(listOf(5, 4), trace.calls.map { it.returnEntryId })
        assertTrue(trace.calls.all { (it.sourceLine ?: 0) > 0 })
        assertEquals(trace.calls[0].invocationId, trace.calls[1].parentInvocationId)
        assertEquals(9, trace.operations.count { it.kind.name in setOf("SOURCE_CALL", "SOURCE_RETURN", "LOG_EVENT") })
        assertEquals(listOf(1, 2, 3, 4, 5), trace.operations.filter { it.kind.name == "LOG_EVENT" }.map { it.entryId })
    }

    @Test
    fun canonicalNestedJavaFixtureUsesTheSameSourceFirstCallStack() {
        val dir = createTempDirectory("indagium-java-source-trace").toFile()
        File(dir, "Fixture.java").toPath().writeText(
            """
            package fixture;
            class JavaController {
                private final JavaService service = new JavaService();
                void start() {
                    Log.d("JavaController", "controller start");
                    String value = service.run();
                    Log.d("JavaController", "controller result=" + value);
                }
            }
            class JavaService {
                private final JavaRepository repository = new JavaRepository();
                String run() {
                    Log.d("JavaService", "service start");
                    String value = repository.load();
                    Log.d("JavaService", "service value=" + value);
                    return value;
                }
            }
            class JavaRepository {
                String load() { Log.d("JavaRepository", "repository load"); return "42"; }
            }
            """.trimIndent(),
        )
        val entries = listOf(
            LogEntry(1, "10:00:00.001", LogLevel.I, "JavaController", "controller start"),
            LogEntry(2, "10:00:00.002", LogLevel.I, "JavaService", "service start"),
            LogEntry(3, "10:00:00.003", LogLevel.I, "JavaRepository", "repository load"),
            LogEntry(4, "10:00:00.004", LogLevel.I, "JavaService", "service value=42"),
            LogEntry(5, "10:00:00.005", LogLevel.I, "JavaController", "controller result=42"),
        )
        val trace = SourceTraceInferenceEngine(SourceIndexer.build(listOf(dir))).resolve(entries)
        assertEquals(listOf(1, 2, 3, 4, 5), trace.events.map { it.entryId })
        assertEquals(
            listOf("fixture.JavaController" to "fixture.JavaService", "fixture.JavaService" to "fixture.JavaRepository"),
            trace.calls.map { it.callerOwnerType to it.calleeOwnerType },
        )
        assertEquals(listOf(5, 4), trace.calls.map { it.returnEntryId })
    }

    @Test
    fun compatibleCrossRowPathDisambiguatesMatchingSourceSites() {
        val first = site(
            id = "first",
            call = directCall(resultVariable = null),
            matcher = Regex.escape("start event"),
        )
        val base = first.sites.single()
        val reachable = base.copy(
            id = "reachable",
            matcher = Regex.escape("done event"),
            methodName = "fetch",
            owningType = "com.example.Service",
            methodSignature = "fetch()",
            methodId = "method-service",
            directCalls = emptyList(),
            loggedValueNames = emptySet(),
        )
        val unreachable = base.copy(
            id = "unreachable",
            matcher = Regex.escape("done event"),
            methodName = "fetch",
            owningType = "com.example.Unrelated",
            methodSignature = "fetch()",
            methodId = "method-unrelated",
            directCalls = emptyList(),
            loggedValueNames = emptySet(),
        )
        val index = first.copy(
            sites = listOf(base, reachable, unreachable),
            methods = first.methods + IndexedSourceMethod(
                "method-unrelated", "/fixture/Other.kt", "com.example.Unrelated", "fetch", "fetch()", "String", 0, 200,
            ),
        )
        val trace = SourceTraceInferenceEngine(index).resolve(
            listOf(entry(1, "start event"), entry(2, "done event")),
        )
        assertEquals(listOf("first", "reachable"), trace.events.map { it.sourceLogSiteId })
        assertTrue(trace.calls.isNotEmpty())
        assertTrue(TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE !in trace.diagnostics.droppedByReason)
    }

    @Test
    fun operationBranchBetweenObservedLogAndCallRejectsTheStaticPath() {
        val client = site(id = "client-start", call = directCall(resultVariable = null), matcher = Regex.escape("start"))
        val service = client.sites.single().copy(
            id = "service-log",
            filePath = "/fixture/Service.kt",
            tag = "Service",
            methodName = "fetch",
            methodId = "method-service",
            owningType = "com.example.Service",
            matcher = Regex.escape("service"),
            directCalls = emptyList(),
        )
        val index = client.copy(
            sites = listOf(client.sites.single(), service),
            operations = listOf(
                IndexedSourceOperation(
                    "client-log", "method-client", SourceOperationKind.LOG, 10, 10,
                    logSiteId = "client-start", successorIds = listOf("branch"),
                ),
                IndexedSourceOperation("branch", "method-client", SourceOperationKind.BRANCH, 11, 11, successorIds = listOf("call")),
                IndexedSourceOperation("call", "method-client", SourceOperationKind.CALL, 12, 12, callSiteId = "call-1", successorIds = emptyList()),
                IndexedSourceOperation("service-log", "method-service", SourceOperationKind.LOG, 10, 10, logSiteId = "service-log", successorIds = emptyList()),
            ),
        )

        val trace = SourceTraceInferenceEngine(index).resolve(
            listOf(entry(1, "start"), entry(2, "service").copy(tag = "Service")),
        )

        assertTrue(trace.calls.isEmpty())
        assertTrue(TraceDiagnosticReason.BRANCH_INCOMPATIBLE in trace.diagnostics.droppedByReason)
    }

    @Test
    fun incompatibleMiddleAnchorKeepsVerifiedPrefixAndSuffixAsPartialTrace() {
        val start = site(id = "client-start", call = directCall(resultVariable = null), matcher = Regex.escape("start"))
        val service = start.sites.single().copy(
            id = "service-log",
            filePath = "/fixture/Service.kt",
            tag = "Service",
            methodName = "fetch",
            methodId = "method-service",
            owningType = "com.example.Service",
            matcher = Regex.escape("service"),
            directCalls = emptyList(),
        )
        val end = start.sites.single().copy(
            id = "client-end",
            matcher = Regex.escape("end"),
            directCalls = emptyList(),
            loggedValueNames = emptySet(),
        )
        val index = start.copy(
            sites = listOf(start.sites.single(), service, end),
            operations = listOf(
                IndexedSourceOperation(
                    "client-start-op", "method-client", SourceOperationKind.LOG, 10, 10,
                    logSiteId = "client-start", successorIds = listOf("branch"),
                ),
                IndexedSourceOperation("branch", "method-client", SourceOperationKind.BRANCH, 11, 11, successorIds = listOf("call")),
                IndexedSourceOperation("call", "method-client", SourceOperationKind.CALL, 12, 12, callSiteId = "call-1", successorIds = emptyList()),
                IndexedSourceOperation("service-log", "method-service", SourceOperationKind.LOG, 10, 10, logSiteId = "service-log", successorIds = emptyList()),
                IndexedSourceOperation("client-end-op", "method-client", SourceOperationKind.LOG, 20, 20, logSiteId = "client-end", successorIds = emptyList()),
            ),
        )
        val entries = listOf(entry(1, "start"), entry(2, "service").copy(tag = "Service"), entry(3, "end"))

        val trace = SourceTraceInferenceEngine(index).resolve(entries)

        assertEquals(listOf(1, 3), trace.events.map { it.entryId })
        assertTrue(TraceDiagnosticReason.BRANCH_INCOMPATIBLE in trace.diagnostics.droppedByReason)
        assertTrue(trace.events.none { it.entryId == 2 })
        assertTrue(trace.calls.isEmpty())
    }

    @Test
    fun ambiguousMiddleAnchorIsOmittedWhileVerifiedPrefixAndSuffixRemain() {
        val base = site(call = directCall(resultVariable = null), matcher = Regex.escape("start"))
        val start = base.sites.single()
        val middleA = start.copy(id = "middle-a", matcher = Regex.escape("middle"))
        val middleB = start.copy(
            id = "middle-b",
            filePath = "/fixture/Other.kt",
            matcher = Regex.escape("middle"),
        )
        val end = start.copy(id = "end", matcher = Regex.escape("end"))
        val trace = SourceTraceInferenceEngine(
            base.copy(sites = listOf(start, middleA, middleB, end)),
        ).resolve(listOf(entry(1, "start"), entry(2, "middle"), entry(3, "end")))

        assertEquals(listOf(1, 3), trace.events.map { it.entryId })
        assertTrue(trace.events.none { it.entryId == 2 })
        assertTrue(TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE in trace.diagnostics.droppedByReason)
    }

    @Test
    fun separateLogicalLanesNeverShareASynchronousSourceStack() {
        val client = site(id = "client-start", call = directCall(resultVariable = null), matcher = Regex.escape("start"))
        val service = client.sites.single().copy(
            id = "service-log",
            filePath = "/fixture/Service.kt",
            tag = "Service",
            methodName = "fetch",
            methodId = "method-service",
            owningType = "com.example.Service",
            matcher = Regex.escape("service"),
            directCalls = emptyList(),
        )
        val trace = SourceTraceInferenceEngine(client.copy(sites = listOf(client.sites.single(), service))).resolve(
            listOf(
                entry(1, "start"),
                entry(2, "service").copy(tag = "Service", tid = 99),
            ),
        )

        assertEquals(listOf(1, 2), trace.events.map { it.entryId })
        assertTrue(trace.calls.isEmpty())
    }

    @Test
    fun explicitAsyncDispatchAcrossLanesIsNonBlockingButRetained() {
        val client = site(
            id = "client-dispatch",
            call = directCall(kind = InvocationKind.EXECUTOR_DISPATCH, resultVariable = null),
            matcher = Regex.escape("dispatch"),
        )
        val worker = client.sites.single().copy(
            id = "worker-log",
            filePath = "/fixture/Service.kt",
            tag = "Worker",
            methodName = "fetch",
            methodId = "method-service",
            owningType = "com.example.Service",
            matcher = Regex.escape("worker"),
            directCalls = emptyList(),
        )
        val trace = SourceTraceInferenceEngine(client.copy(sites = listOf(client.sites.single(), worker))).resolve(
            listOf(
                entry(1, "dispatch"),
                entry(2, "worker").copy(tag = "Worker", tid = 99),
            ),
        )

        val call = trace.calls.single()
        assertEquals(TraceInvocationKind.EXECUTOR_DISPATCH, call.invocationKind)
        assertEquals(null, call.returnEntryId)
        assertEquals(TraceCallStatus.UNKNOWN, call.status)
    }

    private fun directCall(kind: InvocationKind = InvocationKind.SYNCHRONOUS, resultVariable: String? = "result") =
        SourceDirectCall(
            targetFilePath = "/fixture/Service.kt",
            targetOwnerType = "com.example.Service",
            targetMethodName = "fetch",
            targetMethodSignature = "fetch()",
            targetDeclaredReturnType = "String",
            callLine = 10,
            resultVariable = resultVariable,
            sourceOwnerType = "com.example.Client",
            callSiteId = "call-1",
            callerMethodId = "method-client",
            targetMethodId = "method-service",
            callOffset = 100,
            receiverExpression = "service",
            receiverVariable = "service",
            receiverDeclaredType = "com.example.Service",
            receiverRole = ReceiverRole.FIELD,
            invocationKind = kind,
            resolutionConfidence = 1.0,
        )

    private fun site(
        path: String = "/fixture/Client.kt",
        id: String = "log-1",
        call: SourceDirectCall = directCall(),
        matcher: String = Regex.escape("result=ok"),
        fileMeta: Map<String, FileMeta> = emptyMap(),
    ): SourceIndex = SourceIndex(
        version = SOURCE_INDEX_VERSION,
        roots = listOf("/fixture"),
        sites = listOf(
            LogCallSite(
                filePath = path,
                tag = "Client",
                methodName = "run",
                methodStartLine = 1,
                methodEndLine = 20,
                callLine = 10,
                matcher = matcher,
                literalLen = 9,
                owningType = "com.example.Client",
                methodSignature = "run()",
                declaredReturnType = "Unit",
                directCalls = listOf(call),
                loggedValueNames = if (matcher.contains("result")) setOf("result") else emptySet(),
                id = id,
                methodId = "method-client",
            ),
        ),
        fileMeta = fileMeta,
        builtAt = 1L,
        methods = listOf(
            IndexedSourceMethod(
                "method-client", path, "com.example.Client", "run", "run()", "Unit", 0, FIXTURE_METHOD_END_OFFSET,
            ),
            IndexedSourceMethod(
                "method-service", "/fixture/Service.kt", "com.example.Service", "fetch", "fetch()", "String", 0, FIXTURE_METHOD_END_OFFSET,
            ),
        ),
        calls = listOf(
            IndexedSourceCall(
                "call-1", "method-client", listOf("method-service"), "service", "service",
                "com.example.Service", ReceiverRole.FIELD, 100, 10, call.resultVariable, call.invocationKind, 1.0,
            ),
        ),
        revision = "fixture",
    )

    private fun entry(id: Int, message: String = "result=ok") =
        LogEntry(id, "10:00:00.00$id", LogLevel.I, "Client", message, pid = 7, tid = 11)

    @Test
    fun runtimeValueClosesAnEarlierObservedInvocationOnTheSameLane() {
        val started = site(id = "start", call = directCall(resultVariable = null), matcher = Regex.escape("started"))
        val completed = site(id = "result", call = directCall(), matcher = Regex.escape("result=ok"))
        val index = started.copy(sites = listOf(started.sites.single(), completed.sites.single()))
        val engine = SourceTraceInferenceEngine(index)
        val trace = engine.resolve(listOf(entry(1, "started"), entry(2)))

        val call = trace.calls.single()
        assertEquals(TraceCallStatus.RETURNED, call.status)
        assertEquals(1, call.callEntryId)
        assertEquals(2, call.returnEntryId)
        assertTrue(DiagramTraceEvidence.RUNTIME_RETURN_VALUE in call.evidence)
    }

    @Test
    fun returnOnlySelectionNeverFabricatesAnEarlierCallOrReturn() {
        // Only the return-side log line is in range — the source describes a call INTO this site,
        // but nothing upstream was ever observed, so no call may be synthesized from source
        // structure alone.
        val trace = SourceTraceInferenceEngine(site()).resolve(listOf(entry(1)))
        assertEquals(listOf(1), trace.events.map { it.entryId })
        assertTrue(trace.calls.isEmpty())
    }

    @Test
    fun staleAndAmbiguousCandidatesRemainSelfEventsWithDiagnostics() {
        val stale = SourceTraceInferenceEngine(
            site(fileMeta = mapOf("/fixture/Client.kt" to FileMeta(1L, 1L))),
        ).resolve(listOf(entry(1)))
        assertTrue(stale.calls.isEmpty())
        assertTrue(TraceDiagnosticReason.STALE_SOURCE_SITE in stale.diagnostics.droppedByReason.keys)

        val first = site(id = "log-a")
        val secondSite = first.sites.single().copy(filePath = "/fixture/Other.kt", id = "log-b")
        val ambiguous = SourceTraceInferenceEngine(first.copy(sites = listOf(first.sites.single(), secondSite)))
            .resolve(listOf(entry(1)))
        assertTrue(ambiguous.calls.isEmpty())
        assertEquals(1, ambiguous.diagnostics.droppedByReason[TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE])
    }

    @Test
    fun incompleteWindowAndAsyncDispatchDoNotFabricateCalls() {
        val incompleteEngine = SourceTraceInferenceEngine(
            site(call = directCall(resultVariable = null), matcher = Regex.escape("started")),
        )
        val incomplete = incompleteEngine.resolve(listOf(entry(1, "started")))
        assertTrue(incomplete.calls.isEmpty())

        // Same shape, but the direct call is an async dispatch and only the dispatching side is in
        // range: with no observed callee-side log line, no call — blocking or non-blocking — may be
        // fabricated from source structure alone (see explicitAsyncDispatchAcrossLanesIsNonBlocking
        // ButRetained for the case where BOTH sides are observed).
        val asyncCall = directCall(kind = InvocationKind.EXECUTOR_DISPATCH, resultVariable = null)
        val asyncEngine = SourceTraceInferenceEngine(
            site(call = asyncCall, matcher = Regex.escape("dispatch")),
        )
        val asyncTrace = asyncEngine.resolve(listOf(entry(1, "dispatch")))
        assertTrue(asyncTrace.calls.isEmpty())
    }

    @Test
    fun unobservedCallDoesNotCreateTransientOwnerLifelines() {
        // The source describes a call from Client into Service, but only the caller-side log line
        // is in range — Service was never observed, so its owner type must never appear anywhere in
        // the resolved trace (there is no lifeline concept at this layer, but a fabricated call
        // would be the trace-level equivalent of the phantom participant this test's name refers to).
        val engine = SourceTraceInferenceEngine(
            site(call = directCall(resultVariable = null), matcher = Regex.escape("started")),
        )
        val trace = engine.resolve(listOf(entry(1, "started")))
        assertEquals(listOf(1), trace.events.map { it.entryId })
        assertTrue(trace.calls.isEmpty())
        assertTrue(trace.calls.none { it.calleeOwnerType == "com.example.Service" })
    }
}
