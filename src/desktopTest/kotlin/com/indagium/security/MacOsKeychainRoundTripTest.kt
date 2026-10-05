package com.indagium.security

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The real macOS keychain, through the real /usr/bin/security: writes, reads and deletes a THROWAWAY item (its own service name,
 * removed at the end). It checks the interactive-mode quoting against the actual parser. Opt-in, so a plain `./gradlew build`
 * never touches a developer's or CI machine's keychain: run it with `INDAGIUM_KEYCHAIN_TEST=1 ./gradlew desktopTest --tests
 * "com.indagium.security.MacOsKeychainRoundTripTest"`. Also skipped on other systems, when `security` is missing, and when the
 * keychain cannot be written (a locked or absent keychain).
 */
class MacOsKeychainRoundTripTest {
    private val awkward = "p\"a\\ss w0rd #1 'q' \$HOME ~tilde;semi|pipe&amp (paren) {brace} [bracket] <angle> *star? %pct @at!"

    @Test
    fun aSecretWithEveryAwkwardCharacterSurvivesAWriteReadDeleteRoundTrip() = runBlocking<Unit> {
        assumeTrue("opt-in: set INDAGIUM_KEYCHAIN_TEST=1", System.getenv("INDAGIUM_KEYCHAIN_TEST") == "1")
        assumeTrue("macOS only", System.getProperty("os.name").lowercase().contains("mac"))
        assumeTrue("/usr/bin/security is missing", File(MACOS_SECURITY_EXECUTABLE).canExecute())
        val key = SecretKey(account = "roundtrip", service = "Indagium-test-${UUID.randomUUID()}")
        val store = MacOsKeychainSecretStore()
        try {
            val written = store.write(key, awkward)
            assumeTrue("the keychain cannot be written here: $written", written is SecretResult.Ok)

            assertEquals(SecretResult.Ok<String?>(awkward), store.read(key))
            assertIs<SecretResult.Ok<Unit>>(store.write(key, "second-value"), "writing again updates the item")
            assertEquals(SecretResult.Ok<String?>("second-value"), store.read(key))
        } finally {
            assertIs<SecretResult.Ok<Unit>>(store.delete(key))
        }
        assertEquals(SecretResult.Ok<String?>(null), store.read(key), "the item is gone")
        assertIs<SecretResult.Ok<Unit>>(store.delete(key), "deleting a missing item is not an error")
    }
}
