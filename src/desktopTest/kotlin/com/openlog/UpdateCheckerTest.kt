package com.openlog

import com.openlog.update.ReleaseAsset
import com.openlog.update.UpdateCheckResult
import com.openlog.update.UpdateChecker
import com.openlog.update.assetForCurrentOs
import com.openlog.update.compareVersions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun compareVersionsOrdersNumericallyAndToleratesVAndSegmentMismatches() {
        assertTrue(compareVersions("1.4.0", "1.5.0") < 0)
        assertEquals(0, compareVersions("1.4.0", "1.4.0"))
        assertEquals(0, compareVersions("v1.4.0", "1.4.0"))
        assertEquals(0, compareVersions("1.4", "1.4.0"))
        assertTrue(compareVersions("1.4.1", "1.4") > 0)
    }

    @Test
    fun fetchLatestReturnsAvailableWithParsedReleaseWhenNewer() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            capturedRequest = request
            respond(RELEASE_JSON, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { expectSuccess = false }

        val result = UpdateChecker(httpClient = client).fetchLatest(currentVersion = "1.4.0")

        val request = requireNotNull(capturedRequest)
        assertEquals("/repos/rarnaut-dev/openLog2/releases/latest", request.url.encodedPath)

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("1.5.0", available.release.version)
        assertEquals("v1.5.0", available.release.tag)
        assertEquals(
            listOf(
                ReleaseAsset("openLog-1.5.0.dmg", "https://example.test/openLog-1.5.0.dmg", 111L),
                ReleaseAsset("openLog_1.5.0.deb", "https://example.test/openLog_1.5.0.deb", 222L),
                ReleaseAsset("openLog-1.5.0.msi", "https://example.test/openLog-1.5.0.msi", 333L),
            ),
            available.release.assets,
        )
    }

    @Test
    fun fetchLatestReturnsUpToDateWhenCurrentVersionIsNotOlder() = runBlocking {
        val client = HttpClient(MockEngine { respond(RELEASE_JSON, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }) {
            expectSuccess = false
        }

        assertEquals(UpdateCheckResult.UpToDate, UpdateChecker(httpClient = client).fetchLatest(currentVersion = "1.5.0"))
        assertEquals(UpdateCheckResult.UpToDate, UpdateChecker(httpClient = client).fetchLatest(currentVersion = "1.6.0"))
    }

    @Test
    fun fetchLatestReturnsUnavailableRatherThanThrowingOnHttpFailure() = runBlocking {
        val client = HttpClient(MockEngine { respond("not found", HttpStatusCode.NotFound) }) { expectSuccess = false }

        val result = UpdateChecker(httpClient = client).fetchLatest(currentVersion = "1.4.0")

        val unavailable = assertIs<UpdateCheckResult.Unavailable>(result)
        assertTrue(unavailable.reason.isNotBlank())
    }

    @Test
    fun assetForCurrentOsPicksThePackagedFormatForEachPlatform() {
        val assets = listOf(
            ReleaseAsset("openLog-1.5.0.dmg", "https://example.test/dmg", 1L),
            ReleaseAsset("openLog_1.5.0.deb", "https://example.test/deb", 2L),
            ReleaseAsset("openLog-1.5.0.msi", "https://example.test/msi", 3L),
        )

        assertEquals(assets[0], assetForCurrentOs(assets, osName = "Mac OS X"))
        assertEquals(assets[2], assetForCurrentOs(assets, osName = "Windows 11"))
        assertEquals(assets[1], assetForCurrentOs(assets, osName = "Linux"))
    }

    @Test
    fun assetForCurrentOsReturnsNullWhenNoAssetMatches() {
        val onlyDeb = listOf(ReleaseAsset("openLog_1.5.0.deb", "https://example.test/deb", 2L))
        assertNull(assetForCurrentOs(onlyDeb, osName = "Mac OS X"))
        assertNull(assetForCurrentOs(emptyList(), osName = "Linux"))
    }

    @Test
    fun downloadAssetWritesTheReleaseAssetToTheGivenDestinationFile() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("package", HttpStatusCode.OK, headersOf("Content-Length", "7"))
        }) { expectSuccess = false }
        val destinationDir = createTempDirectory("openlog-update-download").toFile()
        val destination = File(destinationDir, "openLog.deb")
        val asset = ReleaseAsset("openLog.deb", "https://example.test/openLog.deb", 7L)

        val result = UpdateChecker(httpClient = client).downloadAsset(asset, destination)

        assertEquals(destination, result)
        assertEquals("package", result.readText())
    }

    @Test
    fun downloadAssetHonoursADestinationFileNameDifferentFromTheAsset() = runBlocking {
        // The save dialog lets the user rename the file — the bytes must land wherever they picked,
        // not back under the release asset's own name.
        val client = HttpClient(MockEngine {
            respond("package", HttpStatusCode.OK, headersOf("Content-Length", "7"))
        }) { expectSuccess = false }
        val destinationDir = createTempDirectory("openlog-update-download-rename").toFile()
        val destination = File(destinationDir, "my-custom-name.deb")
        val asset = ReleaseAsset("openLog.deb", "https://example.test/openLog.deb", 7L)

        val result = UpdateChecker(httpClient = client).downloadAsset(asset, destination)

        assertEquals(destination, result)
        assertEquals("package", result.readText())
        assertTrue(!File(destinationDir, asset.name).exists())
    }

    @Test
    fun downloadAssetCreatesTheDestinationParentDirectoryWhenMissing() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("package", HttpStatusCode.OK, headersOf("Content-Length", "7"))
        }) { expectSuccess = false }
        val root = createTempDirectory("openlog-update-download-missing-parent").toFile()
        val destination = File(File(root, "nested/does-not-exist-yet"), "openLog.deb")
        val asset = ReleaseAsset("openLog.deb", "https://example.test/openLog.deb", 7L)

        val result = UpdateChecker(httpClient = client).downloadAsset(asset, destination)

        assertEquals(destination, result)
        assertEquals("package", result.readText())
    }

    @Test
    fun downloadAssetRejectsADestinationWhoseParentIsAFile() = runBlocking {
        val root = createTempDirectory("openlog-update-destination-file").toFile()
        val parentThatIsActuallyAFile = File(root, "not-a-directory").apply { writeText("I'm a file") }
        val destination = File(parentThatIsActuallyAFile, "openLog.deb")
        val asset = ReleaseAsset("openLog.deb", "https://example.test/openLog.deb", 7L)

        val error = assertFailsWith<IOException> {
            UpdateChecker().downloadAsset(asset, destination)
        }

        assertTrue(error.message.orEmpty().contains("not a directory"))
    }

    private companion object {
        val RELEASE_JSON = """
            {
              "tag_name": "v1.5.0",
              "html_url": "https://github.com/rarnaut-dev/openLog2/releases/tag/v1.5.0",
              "body": "## What's new\n- Update checker",
              "assets": [
                {"name": "openLog-1.5.0.dmg", "browser_download_url": "https://example.test/openLog-1.5.0.dmg", "size": 111},
                {"name": "openLog_1.5.0.deb", "browser_download_url": "https://example.test/openLog_1.5.0.deb", "size": 222},
                {"name": "openLog-1.5.0.msi", "browser_download_url": "https://example.test/openLog-1.5.0.msi", "size": 333}
              ]
            }
        """.trimIndent()
    }
}
