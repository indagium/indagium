package com.indagium.ai

import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.AiProviderProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** How long the host waits for Codex to take an interrupt and to end the interrupted turn before it gives the process up. */
internal const val CODEX_JUDGE_INTERRUPT_SETTLE_MS = 3_000L

private const val WORKSPACE_PREFIX = "indagium-judge-host-"
private const val JUDGE_DIRECTORY_PREFIX = "judge-"

/** One judge's request to the host: what an [AccountAgentRunner] run gets, plus the [gateway] of the judge's tools. */
@Suppress("LongParameterList") // One run request; each field is something the Codex turn reads when it starts.
internal class CodexJudgeTurn(
    val session: AiSession,
    val prompt: String,
    val systemPrompt: String,
    val context: AiInvestigationContext,
    val gateway: IndagiumToolGateway,
    val toolCallLimit: Int,
    val freeTools: Set<String>,
    val confirmationTimeoutMs: Long?,
    val promptPreamble: (AiRun) -> String,
    val images: List<LlmImage> = emptyList(),
    val promptWithoutImages: String? = null,
)

/**
 * One Codex app-server process, one managed MCP server and one workspace, kept for the judges of ONE lane of a test run, so that
 * each judge does not pay for launching Codex again. Judges are separate runs in every other respect:
 *
 * - Each judge is a new ephemeral thread and turn on the shared client, so no context carries from one step to the next.
 * - The MCP lease is a [SwitchableMcpRoute]: while a judge works it points at that judge's own [AiRun] and gateway (its evidence,
 *   its tool budget, its confirmations); when the judge ends it points at nothing, and a call that still arrives is refused. A
 *   judge starts only after the previous one has fully ended (its turn interrupted and reported over), so a call the previous
 *   judge's model still made cannot reach the next one's evidence.
 * - Judges of one host run one after another (a lock inside the host's coroutines); lanes that run in parallel use their own hosts.
 * - The process is built when the first judge needs it (that judge pays the startup) and dropped, to be rebuilt by the next judge,
 *   when it exited, reported a transport fault, a request was left unanswered, an interrupt did not settle within
 *   [interruptSettleMs], the tool list of the next judge differs, or its MCP server could not start. A judge that is cancelled (as
 *   one is as soon as its verdict is in) interrupts its turn and leaves the process running.
 *
 * Threading: everything runs on the host's own IO scope, never on Ktor, Default or the UI thread. [turnLock] is a coroutine lock held
 * for a judge's whole run; no other lock is taken while it is held and nothing waits for the UI thread under it. [closed] is the only
 * other shared state besides the volatile fields of the engine, and [close] is safe to call from any thread at any time.
 */
internal class CodexJudgeHost(
    private val profile: AiProviderProfile,
    /** Starts the managed MCP lease for [route]; the production one is [ManagedMcpServerLease.startSwitchable]. */
    private val routeLeaseFactory: (SwitchableMcpRoute) -> ManagedMcpServerLease,
    // Seams so the host can be exercised without a real CLI; the defaults are the production behaviour.
    private val cliCheck: (AiProviderProfile) -> AccountCliCheck = { checkAccountCli(it) },
    private val codexLauncher: (List<String>, Map<String, String>, CoroutineScope) -> CodexAppServerClient =
        { command, environment, launchScope -> CodexAppServerClient.launch(command, environment, launchScope) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val interruptSettleMs: Long = CODEX_JUDGE_INTERRUPT_SETTLE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val turnLock = Mutex()

    @Volatile
    private var engine: HostEngine? = null

    /** How many Codex processes this host has started so far (a test reads it; the app logs nothing from it). */
    @Volatile
    var processStarts: Int = 0
        private set

    /** Starts one judge; like [AccountAgentRunner.start] it returns the run at once and the run ends by itself (Done, Error or Cancelled). */
    fun start(turn: CodexJudgeTurn): AiRun {
        require(turn.prompt.isNotBlank()) { "AI prompt must not be blank" }
        check(!closed.get()) { "The Codex judge host is closed." }
        turn.session.activeRun?.cancel()
        val run = AiRun(
            tabId = turn.session.tabId,
            userPrompt = turn.prompt,
            context = turn.context,
            maxToolCalls = turn.toolCallLimit,
            freeTools = turn.freeTools,
            confirmationTimeoutMs = turn.confirmationTimeoutMs,
            clock = clock,
        )
        turn.session.lastPrompt = turn.prompt
        turn.session.lastContext = turn.context
        turn.session.activeRun = run
        turn.session.retain(run)
        run.job = scope.launch {
            try {
                turnLock.withLock { runJudge(run, turn) }
            } catch (_: CancellationException) {
                // Cancelled while waiting for the previous judge to end: the judge never began.
                withContext(NonCancellable) { run.emit(AiRunEvent.Cancelled) }
            } finally {
                if (turn.session.activeRun === run) turn.session.activeRun = null
            }
        }
        return run
    }

    /** Closes the process, the MCP lease and the workspace. Safe to call more than once and while a judge is running. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        engine?.let(::teardown)
        engine = null
    }

    // ── One judge ────────────────────────────────────────────────────

    /** What one judge has done on the engine, so that its end knows what to undo. */
    private class JudgeSlot {
        var engine: HostEngine? = null
        var listener: CodexTurnEvents? = null
        var threadId: String? = null
        var turnId: String? = null
        var imageDirectory: Path? = null

        /** A request was sent and its answer has not been seen: the process may still answer it later. */
        var requestOutstanding = false
    }

    @Suppress("TooGenericExceptionCaught") // Whatever stops the judge means "no verdict", reported as an Error event.
    private suspend fun runJudge(run: AiRun, turn: CodexJudgeTurn) {
        val slot = JudgeSlot()
        try {
            run.emit(AiRunEvent.Status("Starting ${profile.kind.label}…"))
            val engine = ensureEngine(run, turn.gateway)
            slot.engine = engine
            run.agentReused = engine.judges > 0
            engine.judges++
            engine.route.activate(run, turn.gateway)
            val listener = CodexTurnEvents(engine.client, run)
            engine.listener = listener
            slot.listener = listener
            val thread = slot.request {
                engine.client.startThread(
                    CodexThreadOptions(
                        cwd = engine.workspace.toString(),
                        model = profile.model.takeIf(String::isNotBlank),
                        approvalPolicy = codexManagedMcpApprovalPolicy(),
                        sandbox = "read-only",
                        ephemeral = true,
                    ),
                )
            }
            listener.bind(thread.id)
            slot.threadId = thread.id
            val imageDirectory = withContext(Dispatchers.IO) { Files.createDirectories(engine.workspace.resolve("$JUDGE_DIRECTORY_PREFIX${engine.judges}")) }
            slot.imageDirectory = imageDirectory
            val request = AccountRequest(turn.prompt, turn.images, turn.promptWithoutImages)
            val input = codexTurnInput(run, request, turn.systemPrompt, turn.promptPreamble(run), imageDirectory)
            val started = slot.request {
                engine.client.startTurn(
                    thread.id, input.text, profile.model.takeIf(String::isNotBlank), engine.workspace.toString(),
                    profile.reasoningEffort.takeIf(String::isNotBlank), input.imagePaths,
                )
            }
            slot.turnId = started.id
            listener.terminal.await()
            run.emit(AiRunEvent.Done)
        } catch (_: CancellationException) {
            withContext(NonCancellable) { run.emit(AiRunEvent.Cancelled) }
        } catch (fault: CodexProcessFault) {
            slot.engine?.broken = true
            run.emit(AiRunEvent.Error(fault.message ?: "The Codex process failed."))
        } catch (error: Exception) {
            run.emit(AiRunEvent.Error(error.message ?: "Unable to start the account agent."))
        } finally {
            withContext(NonCancellable) { endJudge(run, slot) }
        }
    }

    /** Runs [block], remembering that its answer is awaited until it comes (a cancelled request leaves it set). */
    @Suppress("TooGenericExceptionCaught") // Rethrown at once; only the bookkeeping depends on what it was.
    private suspend fun <T> JudgeSlot.request(block: suspend () -> T): T {
        requestOutstanding = true
        return try {
            block().also { requestOutstanding = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            requestOutstanding = false
            throw error
        }
    }

    /**
     * The judge is over, however it ended: its tools stop answering, its turn is interrupted when it still runs, and the engine is
     * kept for the next judge only when it is certain to be in a state the next one can use.
     */
    private suspend fun endJudge(run: AiRun, slot: JudgeSlot) {
        val engine = slot.engine
        engine?.route?.clear()
        run.confirmations.values.forEach { it.cancel() }
        run.confirmations.clear()
        run.deviceBoundSerial = null
        if (engine == null || closed.get()) {
            slot.imageDirectory?.let(::deleteTree)
            return
        }
        val listener = slot.listener
        val threadId = slot.threadId
        val turnId = slot.turnId
        when {
            // A request was sent and not answered: its answer would reach whoever uses the process next.
            slot.requestOutstanding -> engine.broken = true
            engine.broken -> Unit
            listener != null && threadId != null && turnId != null && !listener.terminal.isCompleted -> {
                interruptTurn(engine, listener, threadId, turnId, slot.imageDirectory)
                return
            }
        }
        slot.imageDirectory?.let(::deleteTree)
    }

    /**
     * Asks Codex to stop the turn. The interrupt must be taken within [interruptSettleMs] or the process is dropped; the turn's end
     * is awaited in the background ([HostEngine.settling]) and the next judge waits for it before it starts.
     */
    @Suppress("TooGenericExceptionCaught") // A failed interrupt means the process is dropped.
    private suspend fun interruptTurn(engine: HostEngine, listener: CodexTurnEvents, threadId: String, turnId: String, imageDirectory: Path?) {
        val accepted = try {
            withTimeoutOrNull(interruptSettleMs) {
                engine.client.interruptTurn(threadId, turnId)
                true
            } == true
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            engine.broken = true
            imageDirectory?.let(::deleteTree)
            return
        }
        engine.settling = scope.async {
            val ended = withTimeoutOrNull(interruptSettleMs) {
                listener.terminal.join()
                true
            } == true
            if (!ended) engine.broken = true
            imageDirectory?.let(::deleteTree)
            ended
        }
    }

    // ── The process ──────────────────────────────────────────────────

    /** Everything one kept Codex process is made of. The judge that holds [turnLock] is the only one that touches the plain fields. */
    private class HostEngine(
        val client: CodexAppServerClient,
        val lease: ManagedMcpServerLease,
        val route: SwitchableMcpRoute,
        val workspace: Path,
        val toolNames: List<String>,
    ) {
        /** The process cannot be used for another judge. Set from the event collector and from the judge's own end. */
        @Volatile
        var broken = false

        /** Where the events of the running judge go; null between judges. */
        @Volatile
        var listener: CodexTurnEvents? = null

        var collector: Job? = null
        var judges = 0
        var settling: Deferred<Boolean>? = null
        val torndown = AtomicBoolean(false)
    }

    /**
     * The engine for the next judge: the kept one, once the previous judge's interrupted turn has ended and it is still healthy and
     * has the tools [gateway] has; otherwise a new one.
     */
    private suspend fun ensureEngine(run: AiRun, gateway: IndagiumToolGateway): HostEngine {
        val toolNames = gateway.tools.map { it.name }
        engine?.let { kept ->
            val settled = kept.settling?.await() ?: true
            kept.settling = null
            kept.listener = null
            if (settled && !kept.broken && kept.toolNames == toolNames) return kept
            engine = null
            teardown(kept)
        }
        return buildEngine(run, gateway, toolNames)
    }

    private suspend fun buildEngine(run: AiRun, gateway: IndagiumToolGateway, toolNames: List<String>): HostEngine {
        run.emit(AiRunEvent.Status("Checking ${profile.kind.label}…"))
        cliCheck(profile).takeIf { !it.isReady }?.let { check -> error(check.message) }
        val route = SwitchableMcpRoute(gateway.tools)
        var lease: ManagedMcpServerLease? = null
        var client: CodexAppServerClient? = null
        var workspace: Path? = null
        var built = false
        try {
            lease = routeLeaseFactory(route)
            workspace = withContext(Dispatchers.IO) { Files.createTempDirectory(WORKSPACE_PREFIX) }
            val command = CodexAppServerClient.command(profile.executablePath) +
                codexDisableUserServersConfig() +
                codexManagedToolRestrictionConfig() +
                codexManagedMcpConfig(lease.url)
            processStarts++
            client = codexLauncher(command, codexManagedMcpEnvironment(lease.token), scope)
            client.initialize(capabilities = codexManagedMcpCapabilities())
            val created = HostEngine(client, lease, route, workspace, toolNames)
            created.collector = scope.launch { client.events.collect { event -> dispatch(created, event) } }
            if (closed.get()) throw CancellationException("The Codex judge host was closed.")
            engine = created
            built = true
            return created
        } finally {
            if (!built) {
                runCatching { client?.close() }
                runCatching { lease?.close() }
                workspace?.let(::deleteTree)
            }
        }
    }

    /** Hands an event of the process to the judge that is running; notes what says the process cannot be used again. */
    private suspend fun dispatch(engine: HostEngine, event: CodexAppServerEvent) {
        val listener = engine.listener
        when {
            event is CodexAppServerEvent.ProcessExited -> engine.broken = true
            event is CodexAppServerEvent.ProtocolError && event.transportFault -> {
                engine.broken = true
                listener?.fail(CodexProcessFault("Codex app-server connection failed: ${event.message}"))
            }
        }
        if (listener != null) {
            listener.handle(event)
        } else if (event is CodexAppServerEvent.ServerRequest) {
            // No judge is running, so nobody may approve anything.
            runCatching { engine.client.rejectServerRequest(event.id, "Indagium has no judge running on this Codex process.") }
        }
    }

    private fun teardown(engine: HostEngine) {
        if (!engine.torndown.compareAndSet(false, true)) return
        engine.route.clear()
        engine.collector?.cancel()
        runCatching { engine.client.close() }
        runCatching { engine.lease.close() }
        deleteTree(engine.workspace)
    }

    private fun deleteTree(path: Path) {
        runCatching {
            Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
