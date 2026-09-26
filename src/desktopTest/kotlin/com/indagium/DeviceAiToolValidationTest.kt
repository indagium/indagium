package com.indagium

import com.indagium.debug.DEVICE_KEY_CODES
import com.indagium.debug.DeviceScreenCoordinateSpace
import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.debug.isLikelySystemAndroidPackage
import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.debug.parsePackageListOutput
import com.indagium.debug.parseQueryActivitiesOutput
import com.indagium.debug.requireDeviceSwipe
import com.indagium.debug.requireDeviceTapCoordinates
import com.indagium.debug.requireSafeAndroidInputText
import com.indagium.debug.requireSafeDeviceUrl
import com.indagium.debug.requireValidAndroidPackageName
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
    fun packageNameValidationRequiresAReverseDomainId() {
        requireValidAndroidPackageName("com.example.app")
        requireValidAndroidPackageName("com.example.app_2")
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("com") }
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("") }
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("com.example.app; rm -rf") }
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("com.example.$(id)") }
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("1com.example") }
        assertFailsWith<IllegalArgumentException> { requireValidAndroidPackageName("com.example.".repeat(50)) }
    }

    @Test
    fun deviceUrlValidationOnlyAllowsPlainHttpAndHttpsUrls() {
        requireSafeDeviceUrl("https://indagium.com")
        requireSafeDeviceUrl("http://example.com/path?x=1&y=2#frag")
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("ftp://example.com") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("javascript:alert(1)") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("https://example.com/ path") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("https://example.com/'; rm -rf") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("https://example.com/\"quoted\"") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("https://example.com/\\escaped") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("https://example.com/\ttab") }
        assertFailsWith<IllegalArgumentException> { requireSafeDeviceUrl("") }
    }

    @Test
    fun deviceKeyAllowlistExcludesPowerAndSleepButCoversNavigationAndVolume() {
        assertEquals("KEYCODE_BACK", DEVICE_KEY_CODES["BACK"])
        assertEquals("KEYCODE_DEL", DEVICE_KEY_CODES["DEL"])
        assertEquals("KEYCODE_DPAD_CENTER", DEVICE_KEY_CODES["DPAD_CENTER"])
        assertEquals("KEYCODE_VOLUME_UP", DEVICE_KEY_CODES["VOLUME_UP"])
        assertEquals("KEYCODE_WAKEUP", DEVICE_KEY_CODES["WAKEUP"])
        assertTrue("POWER" !in DEVICE_KEY_CODES)
        assertTrue("SLEEP" !in DEVICE_KEY_CODES)
    }

    @Test
    fun likelySystemPackageDetectionOnlyExcludesThePlatformNamespace() {
        assertTrue(isLikelySystemAndroidPackage("android"))
        assertTrue(isLikelySystemAndroidPackage("com.android.settings"))
        assertTrue(!isLikelySystemAndroidPackage("com.example.app"))
        assertTrue(!isLikelySystemAndroidPackage("com.google.android.apps.maps"))
    }

    @Test
    fun queryActivitiesOutputParsesPackageAndActivityPairsAndDeduplicates() {
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true,
              com.example.app/com.example.app.MainActivity filter ...
              com.example.app/com.example.app.MainActivity filter ...
              com.other.app/.LauncherActivity filter ...
        """.trimIndent()
        val apps = parseQueryActivitiesOutput(output)
        assertEquals(2, apps.size)
        assertEquals("com.example.app.MainActivity", apps.first { it.packageName == "com.example.app" }.activity)
        assertEquals(".LauncherActivity", apps.first { it.packageName == "com.other.app" }.activity)
    }

    @Test
    fun packageListOutputFallbackParsesPlainPackageNames() {
        val output = "package:com.example.app\npackage:com.other.app\n\n"
        val apps = parsePackageListOutput(output)
        assertEquals(listOf("com.example.app", "com.other.app"), apps.map { it.packageName })
        assertTrue(apps.all { it.activity == null })
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
