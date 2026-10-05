package com.indagium.security

import com.indagium.testing.script.HostCommandResult
import com.indagium.testing.script.HostCommandRunner
import com.indagium.testing.script.HostCommandSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET = "tok-123.ABC_xyz~sec"
private const val AWKWARD = "a b\"c\\d #e 'f \$g ~h;i|j&k"

/** What a fake child process answers: one result per call, and every spec is kept for the assertions. */
private class FakeRunner(private val respond: (HostCommandSpec) -> HostCommandResult = { ok() }) : HostCommandRunner {
    val specs = CopyOnWriteArrayList<HostCommandSpec>()

    override suspend fun run(spec: HostCommandSpec): HostCommandResult {
        specs += spec
        return respond(spec)
    }
}

private fun ok(stdout: String = "", exit: Int = 0, stderr: String = "") =
    HostCommandResult(exit, stdout.toByteArray(), stderr.toByteArray(), timedOut = false, truncated = false, durationMs = 1)

private fun HostCommandSpec.argv(): String = command.joinToString(" ")

private fun HostCommandSpec.stdinText(): String = stdin?.toString(Charsets.UTF_8).orEmpty()

private val key = SecretKey("tracker.default")

/** The three backends talk to their tool with the secret on stdin ONLY; fallback keeps it in memory with a reason. */
class SecretStoreCommandTest {
    private fun stores(runner: HostCommandRunner): List<Pair<String, SecretStore>> = listOf(
        "mac" to MacOsKeychainSecretStore(runner),
        "linux" to LinuxSecretToolStore(runner),
        "windows" to WindowsCredentialSecretStore(runner),
    )

    // ── The secret never travels on a command line ───────────────────

    @Test
    fun theSecretIsNeverOnAnyCommandLineOfAnyBackend() = runBlocking<Unit> {
        val runner = FakeRunner { spec -> if (spec.argv().contains("lookup") || spec.argv().contains("find-generic")) ok(SECRET) else ok() }
        for ((name, store) in stores(runner)) {
            store.write(key, SECRET)
            store.read(key)
            store.delete(key)
            val encoded = Base64.getEncoder().encodeToString(SECRET.toByteArray())
            runner.specs.forEach { spec ->
                assertFalse(spec.argv().contains(SECRET), "$name: the secret is on a command line: ${spec.argv()}")
                assertFalse(spec.argv().contains(encoded), "$name: the encoded secret is on a command line: ${spec.argv()}")
            }
            runner.specs.clear()
        }
    }

    // ── macOS ────────────────────────────────────────────────────────

    @Test
    fun macWritesTheSecretOnStdinThroughSecurityInteractiveMode() = runBlocking<Unit> {
        val runner = FakeRunner()
        val outcome = MacOsKeychainSecretStore(runner).write(key, SECRET)

        assertIs<SecretResult.Ok<Unit>>(outcome)
        val spec = runner.specs.single()
        assertEquals(listOf("/usr/bin/security", "-i"), spec.command)
        assertEquals("add-generic-password -U -s \"Indagium\" -a \"tracker.default\" -w \"$SECRET\"\n", spec.stdinText())
    }

    @Test
    fun macQuotesEveryCharacterThatMattersToTheInteractiveParser() {
        assertEquals("\"plain\"", quoteForSecurity("plain"))
        assertEquals("\"a b\\\"c\\\\d #e 'f \$g ~h;i|j&k\"", quoteForSecurity(AWKWARD))
        assertEquals("\"\\\\\\\\\"", quoteForSecurity("\\\\"), "each backslash is doubled")
        assertEquals("\"\\\"\"", quoteForSecurity("\""))
    }

    @Test
    fun macWriteOfAnAwkwardSecretPutsTheQuotedFormOnStdin() = runBlocking<Unit> {
        val runner = FakeRunner()
        MacOsKeychainSecretStore(runner).write(key, AWKWARD)

        assertTrue(runner.specs.single().stdinText().endsWith("-w ${quoteForSecurity(AWKWARD)}\n"))
    }

    @Test
    fun macReadAsksForTheItemByServiceAndAccountOnly() = runBlocking<Unit> {
        val runner = FakeRunner { ok("$SECRET\n") }
        val outcome = MacOsKeychainSecretStore(runner).read(key)

        assertEquals(SecretResult.Ok<String?>(SECRET), outcome)
        assertEquals(
            listOf("/usr/bin/security", "find-generic-password", "-s", "Indagium", "-a", "tracker.default", "-w"),
            runner.specs.single().command,
        )
        assertNull(runner.specs.single().stdin)
    }

    @Test
    fun macReportsAMissingItemAsNullAndOtherFailuresWithTheirReason() = runBlocking<Unit> {
        assertEquals(SecretResult.Ok<String?>(null), MacOsKeychainSecretStore(FakeRunner { ok(exit = 44, stderr = "could not be found") }).read(key))
        val failure = MacOsKeychainSecretStore(FakeRunner { ok(exit = 36, stderr = "User interaction is not allowed.") }).read(key)
        assertEquals("User interaction is not allowed.", assertIs<SecretResult.Failed>(failure).reason)
        assertIs<SecretResult.Failed>(MacOsKeychainSecretStore(FakeRunner { ok(exit = 45, stderr = "already exists") }).write(key, SECRET))
    }

    @Test
    fun macDeleteTreatsAMissingItemAsDeleted() = runBlocking<Unit> {
        assertIs<SecretResult.Ok<Unit>>(MacOsKeychainSecretStore(FakeRunner { ok(exit = 44) }).delete(key))
        assertIs<SecretResult.Ok<Unit>>(MacOsKeychainSecretStore(FakeRunner { ok() }).delete(key))
        assertIs<SecretResult.Failed>(MacOsKeychainSecretStore(FakeRunner { ok(exit = 1, stderr = "boom") }).delete(key))
    }

    // ── Linux ────────────────────────────────────────────────────────

    @Test
    fun linuxStoresThroughSecretToolWithTheSecretAsExactlyTheStdin() = runBlocking<Unit> {
        val runner = FakeRunner()
        LinuxSecretToolStore(runner).write(key, SECRET)

        val spec = runner.specs.single()
        assertEquals(listOf("secret-tool", "store", "--label=Indagium (tracker.default)", "service", "Indagium", "account", "tracker.default"), spec.command)
        assertEquals(SECRET, spec.stdinText(), "no trailing newline: the secret is the whole input")
    }

    @Test
    fun linuxLooksUpAndClearsByServiceAndAccount() = runBlocking<Unit> {
        val runner = FakeRunner { spec -> if ("lookup" in spec.command) ok(SECRET) else ok() }
        val store = LinuxSecretToolStore(runner)

        assertEquals(SecretResult.Ok<String?>(SECRET), store.read(key))
        store.delete(key)

        assertEquals(listOf("secret-tool", "lookup", "service", "Indagium", "account", "tracker.default"), runner.specs[0].command)
        assertEquals(listOf("secret-tool", "clear", "service", "Indagium", "account", "tracker.default"), runner.specs[1].command)
    }

    @Test
    fun linuxTreatsNothingFoundAsNullAndAMissingToolAsAFailureWithAHint() = runBlocking<Unit> {
        assertEquals(SecretResult.Ok<String?>(null), LinuxSecretToolStore(FakeRunner { ok(exit = 1) }).read(key))
        assertIs<SecretResult.Ok<Unit>>(LinuxSecretToolStore(FakeRunner { ok(exit = 1) }).delete(key))
        val missing = LinuxSecretToolStore(FakeRunner { throw IOException("Cannot run program \"secret-tool\"") }).write(key, SECRET)
        val reason = assertIs<SecretResult.Failed>(missing).reason
        assertTrue(reason.contains("secret-tool could not be started") && reason.contains("libsecret-tools"), reason)
        assertFalse(reason.contains(SECRET))
    }

    // ── Windows ──────────────────────────────────────────────────────

    @Test
    fun windowsRunsPowerShellOnStdinAndCarriesTheSecretBase64EncodedInsideTheScript() = runBlocking<Unit> {
        val runner = FakeRunner()
        WindowsCredentialSecretStore(runner).write(key, SECRET)

        val spec = runner.specs.single()
        assertEquals(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "-"), spec.command)
        val script = spec.stdinText()
        assertTrue(script.contains("CredWriteW") && script.contains("[IndagiumCred]::Write('Indagium/tracker.default', 'tracker.default'"), script)
        assertTrue(script.contains(Base64.getEncoder().encodeToString(SECRET.toByteArray())))
        assertFalse(script.contains(SECRET), "only the encoded form is in the script")
        assertTrue(script.lines().all { it.length < 8_000 }, "every statement is on its own line")
    }

    @Test
    fun windowsReadDecodesTheBase64AnswerAndMapsTheNotFoundExitToNull() = runBlocking<Unit> {
        val encoded = Base64.getEncoder().encodeToString(SECRET.toByteArray())
        assertEquals(SecretResult.Ok<String?>(SECRET), WindowsCredentialSecretStore(FakeRunner { ok(encoded) }).read(key))
        assertEquals(SecretResult.Ok<String?>(null), WindowsCredentialSecretStore(FakeRunner { ok(exit = 3) }).read(key))
        val script = FakeRunner { ok(exit = 3) }.also { WindowsCredentialSecretStore(it).read(key) }.specs.single().stdinText()
        assertTrue(script.contains("CredReadW") && script.contains("ToBase64String"))
        val deleted = FakeRunner().also { WindowsCredentialSecretStore(it).delete(key) }.specs.single().stdinText()
        assertTrue(deleted.contains("CredDeleteW") && deleted.contains("[IndagiumCred]::Delete('Indagium/tracker.default')"))
    }

    // ── Validation and redaction ─────────────────────────────────────

    @Test
    fun aSecretThatCannotBeStoredIsRefusedBeforeAnyProcessStarts() = runBlocking<Unit> {
        val runner = FakeRunner()
        for ((name, store) in stores(runner)) {
            for (bad in listOf("", "line\nbreak", "tab\there", "naïve", "x".repeat(MAX_SECRET_CHARS + 1))) {
                assertIs<SecretResult.Failed>(store.write(key, bad), "$name accepted a bad secret")
            }
        }
        assertTrue(runner.specs.isEmpty(), "no process was started")
        assertNull(secretProblem(AWKWARD))
    }

    @Test
    fun aFailureTextNeverRepeatsTheSecretOrABearerToken() = runBlocking<Unit> {
        val runner = FakeRunner { ok(exit = 1, stderr = "security: bad password \"$SECRET\" Bearer $SECRET token=$SECRET") }
        for ((name, store) in stores(runner)) {
            val reason = assertIs<SecretResult.Failed>(store.write(key, SECRET), name).reason
            assertFalse(reason.contains(SECRET), "$name: $reason")
        }
    }

    @Test
    fun aKeyOnlyAcceptsSafeIdentifiers() {
        assertEquals("tracker.default", SecretKey("tracker.default").account)
        for (bad in listOf("", "a b", "a\"b", "a;b", "a/../b")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { SecretKey(bad) }
        }
    }

    // ── Fallback ─────────────────────────────────────────────────────

    private class BrokenStore(private val reason: String) : SecretStore {
        override val backendName = "broken"
        override val persistent = true
        override val degradedReason: String? = null

        override suspend fun read(key: SecretKey): SecretResult<String?> = SecretResult.Failed(reason)

        override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> = SecretResult.Failed(reason)

        override suspend fun delete(key: SecretKey): SecretResult<Unit> = SecretResult.Failed(reason)
    }

    @Test
    fun aFailedWriteIsKeptInMemoryAndTheStoreSaysItIsNoLongerPersistent() = runBlocking<Unit> {
        val store = FallbackSecretStore(BrokenStore("keyring is locked"))
        assertTrue(store.persistent)

        assertIs<SecretResult.Ok<Unit>>(store.write(key, SECRET), "the session still has the token")

        assertFalse(store.persistent)
        assertEquals("keyring is locked", store.degradedReason)
        assertEquals("memory", store.backendName)
        assertEquals(SecretResult.Ok<String?>(SECRET), store.read(key))
        assertEquals("Kept for this session only: keyring is locked", store.statusLine())
    }

    /** A primary that works: an in-memory map that claims to be a persistent system store. */
    private class WorkingStore : SecretStore {
        private val inner = MemorySecretStore()
        override val backendName = "macOS Keychain"
        override val persistent = true
        override val degradedReason: String? = null

        override suspend fun read(key: SecretKey) = inner.read(key)

        override suspend fun write(key: SecretKey, value: String) = inner.write(key, value)

        override suspend fun delete(key: SecretKey) = inner.delete(key)
    }

    @Test
    fun aWorkingPrimaryIsPersistentAndLeavesNothingInMemory() = runBlocking<Unit> {
        val memory = MemorySecretStore()
        val store = FallbackSecretStore(WorkingStore(), memory)
        assertIs<SecretResult.Ok<Unit>>(store.write(key, SECRET))

        assertTrue(store.persistent)
        assertEquals("Stored in macOS Keychain", store.statusLine())
        assertEquals(SecretResult.Ok<String?>(null), memory.read(key), "nothing is kept in memory when the primary took it")
        assertEquals(SecretResult.Ok<String?>(SECRET), store.read(key))
        assertFalse(FallbackSecretStore(MemorySecretStore()).persistent, "a fallback over a non-persistent primary is not persistent")
    }

    @Test
    fun aReadFailureIsReportedWithItsReasonAndMarksTheStoreDegraded() = runBlocking<Unit> {
        val store = FallbackSecretStore(BrokenStore("no keyring"))

        assertEquals("no keyring", assertIs<SecretResult.Failed>(store.read(key)).reason)
        assertFalse(store.persistent)
    }

    @Test
    fun theDeleteRemovesTheMemoryCopyEvenWhenThePrimaryFails() = runBlocking<Unit> {
        val memory = MemorySecretStore()
        val store = FallbackSecretStore(BrokenStore("locked"), memory)
        store.write(key, SECRET)
        assertEquals(SecretResult.Ok<String?>(SECRET), memory.read(key))

        assertIs<SecretResult.Failed>(store.delete(key))

        assertEquals(SecretResult.Ok<String?>(null), memory.read(key))
    }

    // ── Platform selection ───────────────────────────────────────────

    @Test
    fun thePlatformStoreFollowsTheOperatingSystemName() {
        assertIs<MacOsKeychainSecretStore>(platformSecretStore("Mac OS X"))
        assertIs<WindowsCredentialSecretStore>(platformSecretStore("Windows 11"))
        assertIs<LinuxSecretToolStore>(platformSecretStore("Linux"))
        assertIs<UnsupportedSecretStore>(platformSecretStore("Plan 9"))
        assertIs<FallbackSecretStore>(defaultSecretStore("Linux"))
    }

    @Test
    fun anUnsupportedPlatformFallsBackToMemoryWithTheReason() = runBlocking<Unit> {
        val store = defaultSecretStore("Plan 9")

        assertFalse(store.persistent)
        assertIs<SecretResult.Ok<Unit>>(store.write(key, SECRET))
        assertEquals(SecretResult.Ok<String?>(SECRET), store.read(key))
        assertTrue(store.statusLine().contains("Plan 9"), store.statusLine())
    }
}
