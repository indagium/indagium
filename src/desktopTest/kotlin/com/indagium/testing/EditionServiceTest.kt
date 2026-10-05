package com.indagium.testing

import com.indagium.edition.Edition
import com.indagium.edition.EditionLimits
import com.indagium.edition.EditionService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditionServiceTest {
    private fun lookup(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    private fun service(props: (String) -> String? = lookup(), env: (String) -> String? = lookup()) =
        EditionService(props = props, env = env)

    @Test
    fun editionLimitsMatchThePlan() {
        assertEquals(EditionLimits(maxSuites = 1, maxCasesPerSuite = 5), Edition.FREE.limits)
        listOf(Edition.PREMIUM, Edition.FRIENDS_FAMILY, Edition.UNLIMITED).forEach {
            assertEquals(EditionLimits(maxSuites = null, maxCasesPerSuite = null), it.limits)
        }
    }

    @Test
    fun aNewServiceDefaultsToUnlimited() {
        val s = service()
        assertEquals(Edition.UNLIMITED, s.current.value)
        assertNull(s.limits().maxSuites)
    }

    @Test
    fun startupWithNothingConfiguredResolvesToUnlimited() {
        assertEquals(Edition.UNLIMITED, service().resolveStartup())
    }

    @Test
    fun startupReadsTheSystemProperty() {
        val s = service(props = lookup("indagium.edition" to "free"))

        assertEquals(Edition.FREE, s.resolveStartup())
        assertEquals(Edition.FREE, s.current.value)
        assertEquals(EditionLimits.FREE, s.limits())
    }

    @Test
    fun startupReadsTheEnvironmentVariableWhenNoPropertyIsSet() {
        assertEquals(Edition.FREE, service(env = lookup("INDAGIUM_EDITION" to "free")).resolveStartup())
    }

    @Test
    fun thePropertyWinsOverTheEnvironmentVariable() {
        val s = service(props = lookup("indagium.edition" to "premium"), env = lookup("INDAGIUM_EDITION" to "free"))
        assertEquals(Edition.PREMIUM, s.resolveStartup())
    }

    @Test
    fun aBlankPropertyFallsThroughToTheEnvironmentVariable() {
        val s = service(props = lookup("indagium.edition" to "  "), env = lookup("INDAGIUM_EDITION" to "free"))
        assertEquals(Edition.FREE, s.resolveStartup())
    }

    @Test
    fun valuesAreCaseInsensitiveAndFriendsFamilyAcceptsSeveralSpellings() {
        assertEquals(Edition.FREE, service(props = lookup("indagium.edition" to "FrEe")).resolveStartup())
        assertEquals(Edition.PREMIUM, service(props = lookup("indagium.edition" to " PREMIUM ")).resolveStartup())
        listOf("friends_family", "friends-family", "FRIENDS_FAMILY", "Friends Family").forEach { raw ->
            assertEquals(Edition.FRIENDS_FAMILY, service(props = lookup("indagium.edition" to raw)).resolveStartup(), raw)
        }
    }

    @Test
    fun anInvalidValueFallsBackToUnlimitedInsteadOfFailing() {
        val s = service(props = lookup("indagium.edition" to "platinum"), env = lookup("INDAGIUM_EDITION" to "free"))

        assertEquals(Edition.UNLIMITED, s.resolveStartup())
    }

    @Test
    fun resolveStartupAcceptsExplicitLookupsForTesting() {
        val s = service()
        assertEquals(Edition.FREE, s.resolveStartup(propLookup = lookup("indagium.edition" to "free")))
    }

    @Test
    fun theDevSwitchIsAllowedInAnUnpackagedRun() {
        val s = service()

        assertTrue(s.devSwitchAllowed())
        assertTrue(s.setForDev(Edition.FREE))
        assertEquals(Edition.FREE, s.current.value)
    }

    @Test
    fun theDevSwitchIsRefusedInAPackagedRunAndChangesNothing() {
        val s = service(props = lookup("jpackage.app-path" to "/Applications/Indagium.app/Contents/MacOS/Indagium"))

        assertFalse(s.devSwitchAllowed())
        assertFalse(s.setForDev(Edition.FREE))
        assertEquals(Edition.UNLIMITED, s.current.value)
    }

    @Test
    fun theDevSwitchIsAllowedInAPackagedRunWhenTheDevPropertyIsTrue() {
        val s = service(props = lookup("jpackage.app-path" to "/x/Indagium", "indagium.dev" to "true"))

        assertTrue(s.setForDev(Edition.FREE))
        assertEquals(Edition.FREE, s.current.value)
    }

    @Test
    fun aDevPropertyOtherThanTrueDoesNotUnlockAPackagedRun() {
        val s = service(props = lookup("jpackage.app-path" to "/x/Indagium", "indagium.dev" to "yes"))

        assertFalse(s.setForDev(Edition.FREE))
    }

    @Test
    fun twoServicesNeverShareState() {
        val a = service()
        val b = service()

        a.setForDev(Edition.FREE)

        assertEquals(Edition.UNLIMITED, b.current.value)
    }

    @Test
    fun parseReturnsNullForUnknownOrMissingText() {
        assertNull(Edition.parse(null))
        assertNull(Edition.parse(""))
        assertNull(Edition.parse("gold"))
        assertEquals(Edition.UNLIMITED, Edition.parse("unlimited"))
    }
}
