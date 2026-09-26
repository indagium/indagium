package com.indagium

import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.debug.DeviceScreenCoordinateSpace
import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.debug.requireDeviceSwipe
import com.indagium.debug.requireDeviceTapCoordinates
import com.indagium.debug.requireSafeAndroidInputText
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceAiToolValidationTest {
    @Test
    fun gesturesRejectCoordinatesOutsideTheCurrentScreenAndOutOfRangeDuration() {
        requireDeviceTapCoordinates(0, 0, 1080, 2400)
        requireDeviceTapCoordinates(1079, 2399, 1080, 2400)
        assertFailsWith<IllegalArgumentException> { requireDeviceTapCoordinates(1080, 10, 1080, 2400) }
        assertFailsWith<IllegalArgumentException> { requireDeviceTapCoordinates(-1, 0, 1080, 2400) }
        requireDeviceSwipe(0, 0, 1079, 2399, 2_000, 1080, 2400)
        assertFailsWith<IllegalArgumentException> { requireDeviceSwipe(0, 0, 1, 1, 49, 1080, 2400) }
        assertFailsWith<IllegalArgumentException> { requireDeviceSwipe(0, 0, 1080, 1, 350, 1080, 2400) }
    }

    @Test
    fun gestureCoordinatesUseReturnedDownsampledImageAndMapToPhysicalPixels() {
        val source = BufferedImage(1080, 2400, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
        val image = encodeBoundedDeviceScreen(bytes)
        val space = DeviceScreenCoordinateSpace(image.width, image.height, image.sourceWidth, image.sourceHeight)

        assertEquals(540, image.width)
        assertEquals(1200, image.height)
        assertEquals(1080, image.sourceWidth)
        assertEquals(2400, image.sourceHeight)
        assertEquals(0 to 0, mapDisplayedScreenCoordinatesToDevice(0, 0, space))
        assertEquals(1079 to 2399, mapDisplayedScreenCoordinatesToDevice(539, 1199, space))
        assertEquals(541 to 1201, mapDisplayedScreenCoordinatesToDevice(270, 600, space))
        assertFailsWith<IllegalArgumentException> { mapDisplayedScreenCoordinatesToDevice(540, 10, space) }

        // Also exercise the reviewed 648×1440 model-visible space on a 1080×2400 physical screen.
        val reviewedSpace = DeviceScreenCoordinateSpace(648, 1440, 1080, 2400)
        assertEquals(540 to 1200, mapDisplayedScreenCoordinatesToDevice(324, 720, reviewedSpace))
    }

    @Test
    fun textInputAllowsUrlAndSpaceButRejectsShellMetacharacters() {
        requireSafeAndroidInputText("https://indagium.com")
        requireSafeAndroidInputText("hello world")
        listOf("url?x=1", "a&b", "value%20here", "a;b", "$(id)", "`id`", "'quoted'").forEach { unsafe ->
            assertFailsWith<IllegalArgumentException>(unsafe) { requireSafeAndroidInputText(unsafe) }
        }
    }

    @Test
    fun deviceScreenImageIsDownsampledAndCappedBeforeProviderForwarding() {
        val source = BufferedImage(2400, 3200, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()

        val bounded = encodeBoundedDeviceScreen(bytes)

        assertTrue(bounded.width <= 1440 && bounded.height <= 1440)
        assertTrue(bounded.bytes.size <= 2 * 1024 * 1024)
        assertTrue(bounded.bytes.size < bytes.size)
        assertTrue(bounded.bytes[0] == 0xFF.toByte() && bounded.bytes[1] == 0xD8.toByte(), "the provider payload is JPEG")
        assertEquals(bounded.width, ImageIO.read(bounded.bytes.inputStream()).width)
    }
}
