package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureMarkerCodecTest {
    private val marker = CaptureMarker(
        id = "m3",
        elapsedMs = 32_412L,
        firstOrdinal = 1841,
        lastOrdinal = 2043,
        videoMs = 26_100L,
        label = "Issue detected here",
        preMs = 5_000L,
        postMs = 5_000L,
        screenshotPath = "screenshots/marker-3.png",
    )

    @Test
    fun headerRoundTrips() {
        val noteText = markerHeader(marker) + "\n" + markerHeadingLine(3, marker.label) + "\n"
        val parsed = parseMarkerHeader(noteText)
        assertEquals(marker, parsed)
    }

    @Test
    fun pendingTrailingWindowRoundTripsWithNullRows() {
        val pending = marker.copy(firstOrdinal = null, lastOrdinal = null)
        val parsed = parseMarkerHeader(markerHeader(pending))
        assertEquals(pending, parsed)
    }

    @Test
    fun noVideoRoundTripsWithNullVideoMs() {
        val noVideo = marker.copy(videoMs = null)
        val parsed = parseMarkerHeader(markerHeader(noVideo))
        assertEquals(noVideo, parsed)
    }

    @Test
    fun noScreenshotRoundTripsWithNullScreenshotPath() {
        val noShot = marker.copy(screenshotPath = null)
        val parsed = parseMarkerHeader(markerHeader(noShot))
        assertEquals(noShot, parsed)
    }

    @Test
    fun unknownAndFutureKeysAreIgnored() {
        val header = markerHeader(marker)
        // Splice an extra, unrecognized key into the header's JSON object — a build newer than
        // this one might add a field here; this build must still parse everything it understands.
        val withFutureKey = header.replaceFirst("\"id\":\"m3\"", "\"id\":\"m3\",\"future\":{\"nested\":true}")
        val parsed = parseMarkerHeader(withFutureKey)
        assertEquals(marker, parsed)
    }

    @Test
    fun plainNoteWithoutAHeaderParsesToNull() {
        assertNull(parseMarkerHeader("Just a regular note the user typed."))
        assertNull(parseMarkerHeader(""))
    }

    @Test
    fun wrongVersionParsesToNull() {
        val header = markerHeader(marker).replaceFirst("indagium:marker v1", "indagium:marker v2")
        assertNull(parseMarkerHeader(header))
    }

    @Test
    fun truncatedHeaderParsesToNull() {
        val header = markerHeader(marker)
        assertNull(parseMarkerHeader(header.dropLast(10)))
    }

    @Test
    fun headingLineFormatsTheMarkerOrdinalAndLabel() {
        assertEquals("## ▲ Marker 3 — Issue detected here", markerHeadingLine(3, "Issue detected here"))
    }
}
