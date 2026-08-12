package com.indagium.source

import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramTraceEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.TraceCallStatus
import com.indagium.diagram.TraceDiagnosticReason
import com.indagium.diagram.TraceInvocationKind
import com.indagium.diagram.SourceTraceMode
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        val diagram = buildSequenceDiagram(
            mkTab("canonical", "fixture.log", entries),
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("controller", "Controller", setOf("Controller"), sourceOwnerTypes = setOf("fixture.Controller")),
                    DiagramComponent("service", "Service", setOf("Service"), sourceOwnerTypes = setOf("fixture.Service")),
                    DiagramComponent("repository", "Repository", setOf("Repository"), sourceOwnerTypes = setOf("fixture.Repository")),
                ),
                options = DiagramOptions(collapseRepeats = false),
            ),
            resolveTrace = { SourceTraceInferenceEngine(index).resolve(it) },
        )
        assertEquals(setOf(1, 2, 3, 4, 5), diagram.primaryEntryIds)
        assertEquals(SourceTraceMode.SOURCE_TRACE, diagram.traceMode)
        assertEquals(
            listOf(MessageKind.SELF, MessageKind.CALL, MessageKind.SELF, MessageKind.CALL, MessageKind.SELF,
                MessageKind.RETURN, MessageKind.SELF, MessageKind.RETURN, MessageKind.SELF),
            diagram.messages.map { it.kind },
        )
        assertTrue(diagram.messages.filter { !it.primary }.all { it.sourceOperationId != null })
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
                IndexedSourceOperation("client-log", "method-client", SourceOperationKind.LOG, 10, 10, logSiteId = "client-start", successorIds = listOf("branch")),
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
                "method-client", path, "com.example.Client", "run", "run()", "Unit", 0, 200,
            ),
            IndexedSourceMethod(
                "method-service", "/fixture/Service.kt", "com.example.Service", "fetch", "fetch()", "String", 0, 200,
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

        val diagram = buildSequenceDiagram(
            mkTab("trace", "trace.log", listOf(entry(1, "started"), entry(2))),
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("client", "Client", setOf("Client"), sourceOwnerTypes = setOf("com.example.Client")),
                    DiagramComponent("service", "Service", setOf("Service"), sourceOwnerTypes = setOf("com.example.Service")),
                ),
                options = DiagramOptions(collapseRepeats = false),
            ),
            resolveTrace = { entries -> engine.resolve(entries) },
        )
        assertEquals(listOf(MessageKind.CALL, MessageKind.SELF, MessageKind.RETURN, MessageKind.SELF), diagram.messages.map { it.kind })
        assertEquals(setOf(1, 2), diagram.primaryEntryIds)
        assertTrue(diagram.messages.filter { it.primary }.all { it.kind == MessageKind.SELF })
        assertEquals(TraceCallStatus.RETURNED, diagram.activationSpans.single().status)
    }

    @Test
    fun returnOnlySelectionNeverFabricatesAnEarlierCallOrReturn() {
        val engine = SourceTraceInferenceEngine(site())
        val diagram = buildSequenceDiagram(
            mkTab("return-only", "trace.log", listOf(entry(1))),
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("client", "Client", setOf("Client"), sourceOwnerTypes = setOf("com.example.Client")),
                    DiagramComponent("service", "Service", setOf("Service"), sourceOwnerTypes = setOf("com.example.Service")),
                ),
                options = DiagramOptions(collapseRepeats = false),
            ),
            resolveTrace = { entries -> engine.resolve(entries) },
        )
        assertEquals(listOf(MessageKind.SELF), diagram.messages.map { it.kind })
        assertTrue(diagram.messages.single().primary)
        assertEquals(setOf(1), diagram.primaryEntryIds)
        assertTrue(diagram.activationSpans.isEmpty())
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

        val asyncCall = directCall(kind = InvocationKind.EXECUTOR_DISPATCH, resultVariable = null)
        val asyncEngine = SourceTraceInferenceEngine(
            site(call = asyncCall, matcher = Regex.escape("dispatch")),
        )
        val diagram = buildSequenceDiagram(
            mkTab("async", "trace.log", listOf(entry(1, "dispatch"))),
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("client", "Client", setOf("Client"), sourceOwnerTypes = setOf("com.example.Client")),
                    DiagramComponent("service", "Service", setOf("Service"), sourceOwnerTypes = setOf("com.example.Service")),
                ),
                options = DiagramOptions(collapseRepeats = false),
            ),
            resolveTrace = { entries -> asyncEngine.resolve(entries) },
        )
        assertFalse(diagram.activationSpans.any { it.invocationKind == TraceInvocationKind.EXECUTOR_DISPATCH })
    }

    @Test
    fun unobservedCallDoesNotCreateTransientOwnerLifelines() {
        val engine = SourceTraceInferenceEngine(
            site(call = directCall(resultVariable = null), matcher = Regex.escape("started")),
        )
        val diagram = buildSequenceDiagram(
            mkTab("owners", "trace.log", listOf(entry(1, "started"))),
            SeqDiagramSpec(
                components = listOf(
                    DiagramComponent("client", "Client", setOf("Client")),
                    DiagramComponent("service", "Service", setOf("Service")),
                ),
                options = DiagramOptions(collapseRepeats = false),
            ),
            resolveTrace = { entries -> engine.resolve(entries) },
        )
        assertTrue(diagram.participants.none { it.sourceOwnerType == "com.example.Service" })
        assertEquals(MessageKind.SELF, diagram.messages.single().kind)
    }
}
