package com.indagium

import com.indagium.update.SponsorChecker
import com.indagium.update.parseGithubSponsor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SponsorFundingParseTest {
    @Test
    fun bareGithubNameBecomesASponsorsUrl() {
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: foo"))
    }

    @Test
    fun flowListTakesTheFirstEntry() {
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: [foo, bar]"))
    }

    @Test
    fun quotedValueIsUnquoted() {
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: \"foo\""))
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: 'foo'"))
    }

    @Test
    fun emptyFlowListReturnsNull() {
        assertNull(parseGithubSponsor("github: []"))
    }

    @Test
    fun trailingInlineCommentIsStrippedFromABareName() {
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: foo # my account"))
    }

    @Test
    fun trailingInlineCommentIsStrippedFromAFlowList() {
        assertEquals("https://github.com/sponsors/foo", parseGithubSponsor("github: [foo, bar] # x"))
    }

    @Test
    fun hashNotPrecededByWhitespaceIsNotTreatedAsAComment() {
        // Only whitespace + '#' starts a comment — a '#' glued directly onto the value is part of it.
        assertEquals("https://github.com/sponsors/foo#bar", parseGithubSponsor("github: foo#bar"))
    }

    @Test
    fun commentedOutLineReturnsNull() {
        assertNull(parseGithubSponsor("# github: foo"))
    }

    @Test
    fun onlyOtherFundingPlatformsReturnsNull() {
        assertNull(parseGithubSponsor("patreon: foo\nopen_collective: bar"))
    }

    @Test
    fun emptyStringReturnsNull() {
        assertNull(parseGithubSponsor(""))
    }

    @Test
    fun realisticFundingYamlPicksTheGithubKeyAmongOthers() {
        val yaml = """
            # These are supported funding model platforms
            github: indagium
            patreon: someone
            custom: ["https://example.com/donate"]
        """.trimIndent()
        assertEquals("https://github.com/sponsors/indagium", parseGithubSponsor(yaml))
    }

    @Test
    fun fetchSponsorUrlParsesA200Response() = runBlocking {
        val client = HttpClient(MockEngine { respond("github: indagium", HttpStatusCode.OK) }) { expectSuccess = false }

        assertEquals("https://github.com/sponsors/indagium", SponsorChecker(client).fetchSponsorUrl())
    }

    @Test
    fun fetchSponsorUrlReturnsNullRatherThanThrowingOn404() = runBlocking {
        val client = HttpClient(MockEngine { respond("Not Found", HttpStatusCode.NotFound) }) { expectSuccess = false }

        assertNull(SponsorChecker(client).fetchSponsorUrl())
    }
}
