package com.indagium.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Repo-qualified project homepage, shared by [openProjectRepository]-style links and the "Star on GitHub" action. */
const val PROJECT_REPO_URL = "https://github.com/$UPDATE_REPO"

private const val FUNDING_YAML_URL = "https://raw.githubusercontent.com/$UPDATE_REPO/HEAD/.github/FUNDING.yml"

/**
 * Pure line-based parse of a GitHub Sponsors `FUNDING.yml`'s `github:` key — no YAML library, since
 * this is the only field ever read from it. Handles a bare name (`github: name`), a flow-style list
 * (`github: [a, b]`, where the first entry wins), single/double-quoted values, and a trailing YAML
 * inline comment (`github: foo # my account`). A missing key, a blank value, an empty list, or a
 * commented-out line (leading `#`) all return null.
 */
fun parseGithubSponsor(fundingYaml: String): String? {
    val line = fundingYaml.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("github:") }
        ?: return null
    val rawValue = stripYamlInlineComment(line.removePrefix("github:").trim())
    val name = if (rawValue.startsWith("[")) {
        rawValue.removePrefix("[").substringBefore(']').split(",").firstOrNull()?.trim()
    } else {
        rawValue
    }?.trim('"', '\'')?.trim()
    return name?.takeIf { it.isNotBlank() }?.let { "https://github.com/sponsors/$it" }
}

/**
 * Drops a trailing `# ...` YAML inline comment. Only a `#` preceded by whitespace starts one — an
 * unquoted `#` glued to the value (unusual, but not our call to reject) is left alone rather than
 * silently truncating real content.
 */
private fun stripYamlInlineComment(value: String): String {
    val hashIndex = value.indices.firstOrNull { i -> value[i] == '#' && i > 0 && value[i - 1].isWhitespace() }
    return if (hashIndex != null) value.substring(0, hashIndex).trim() else value
}

/**
 * Detects GitHub Sponsors support at runtime rather than build time, so the "Sponsor" button in
 * [com.indagium.ui.SupportDialog] appears for existing installs as soon as `FUNDING.yml` is pushed
 * to the repo, with no new release needed. Mirrors [UpdateChecker]'s injectable-[HttpClient] shape
 * so tests can substitute a MockEngine; any failure — network, non-2xx, missing key — returns null
 * rather than throwing (cancellation is rethrown, same contract as [UpdateChecker.fetchLatest]).
 */
class SponsorChecker(
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 0
        }
    },
) {
    suspend fun fetchSponsorUrl(): String? = try {
        val response = httpClient.get(FUNDING_YAML_URL)
        if (!response.status.isSuccess()) null else parseGithubSponsor(response.bodyAsText())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
