package com.indagium.testing

import com.indagium.testing.device.UiTreeRead
import com.indagium.testing.device.needsUiStructure
import com.indagium.testing.device.parseUiAutomatorDump
import com.indagium.testing.device.uiStructureEvidence
import com.indagium.testing.run.LaneStepBrief
import com.indagium.testing.run.LaneToolContext
import com.indagium.testing.run.buildLaneTools
import com.indagium.testing.run.loadUiStructure
import com.indagium.testing.run.optionalGuidance
import com.indagium.testing.script.ScriptRunContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun overviewUiDump(): String = """
    <hierarchy rotation="0">
    <node package="com.google.android.apps.nexuslauncher" bounds="[0,0][1080,2400]">
      <node package="com.google.android.apps.nexuslauncher" resource-id="com.google.android.apps.nexuslauncher:id/overview_panel" bounds="[0,0][1080,2400]">
        <node package="com.google.android.apps.nexuslauncher" resource-id="com.google.android.apps.nexuslauncher:id/task_view_single" bounds="[540,240][880,950]" clickable="true" />
      </node>
    </node>
    </hierarchy>
""".trimIndent()

class UiStructureEvidenceTest {
    @Test
    fun visibleOverviewAndTaskIdsDistinguishRecentsFromALauncherPackageAlone() {
        val overview = assertNotNull(parseUiAutomatorDump(overviewUiDump()))
        val recents = uiStructureEvidence(UiTreeRead.Available(overview), 123L)
        assertEquals("recent_apps", recents["surface"])
        assertEquals(123L, recents["capturedAtMs"])
        val withoutCards = overview.copy(nodes = overview.nodes.filterNot { "task_view" in it.resourceId })
        assertEquals("unclassified", uiStructureEvidence(UiTreeRead.Available(withoutCards), 124L)["surface"])
    }

    @Test
    fun unavailableHierarchyDoesNotBecomeAnAbsentPlayerOrHomeClaim() {
        val evidence = uiStructureEvidence(UiTreeRead.Unavailable("timed out"), 100L)
        assertEquals("unavailable", evidence["status"])
        assertFalse("surface" in evidence)
        assertFalse("elements" in evidence)
        assertTrue(needsUiStructure("Go to the Home screen", "A floating player is visible"))
        assertFalse(needsUiStructure("Tap Save", "The document is saved"))
    }

    @Test
    fun navigationScreenshotCarriesDeviceStructureInsideTheUntrustedEnvelope() = runBlocking {
        val runner = ScriptedAdbRunner(uiDump = overviewUiDump())
        val dir = kotlin.io.path.createTempDirectory("ui-structure").toFile()
        val session = openFixtureSession(runner, dir)
        try {
            val tools = buildLaneTools(LaneToolContext(
                session = session,
                currentStep = { LaneStepBrief("s", "Case", 1, 1, "Go Home", "The Home screen is visible") },
                stepLogOffset = { 0L }, scripts = emptyList(),
                scriptContext = { ScriptRunContext(packageName = "com.example", runDir = dir, caseId = "c", stepId = "s") },
            ))
            val result = tools.gateway.executeSuspending("take_screenshot", emptyMap()) as Map<*, *>
            assertTrue(result["imageBase64"].toString().isNotEmpty())
            val untrusted = result["untrusted_data"] as Map<*, *>
            val structure = untrusted["uiStructure"] as Map<*, *>
            assertEquals("recent_apps", structure["surface"])
            assertEquals("android_ui_structure", untrusted["source"])
            runner.uiDump = overviewUiDump().replace("[1080,2400]", "[999999999999999999999,2400]")
            val malformed = tools.gateway.executeSuspending("take_screenshot", emptyMap()) as Map<*, *>
            assertTrue(malformed["imageBase64"].toString().isNotEmpty())
            val missing = ((malformed["untrusted_data"] as Map<*, *>)["uiStructure"] as Map<*, *>)
            assertEquals("unavailable", missing["status"])
        } finally {
            session.closeBlocking()
            dir.deleteRecursively()
        }
    }

    @Test
    fun optionalProtocolGuidanceRequiresTheConditionRatherThanTheActionTarget() {
        val brief = LaneStepBrief("s", "Case", 1, 1, "Dismiss the player", "Gone", optional = true, condition = "YouTube already closed")
        val guidance = brief.optionalGuidance()["optionalGuidance"].toString()
        assertTrue("YouTube already closed" in guidance)
        assertTrue("Otherwise finish_step skipped" in guidance)
        assertTrue("do not run merely because the action's target is visible" in guidance)
    }

    @Test
    fun aComparisonReadsOnlyBoundedFrozenSidecarsInsideItsRunFolder() {
        val dir = kotlin.io.path.createTempDirectory("ui-structure-files").toFile()
        try {
            File(dir, "frame.png.ui.json").writeText("""{"status":"available","surface":"recent_apps"}""")
            assertEquals("recent_apps", loadUiStructure(dir, "frame.png")?.get("surface"))
            assertEquals(null, loadUiStructure(dir, "missing.png"))
            File(dir, "large.png.ui.json").writeText("x".repeat(20_000))
            assertEquals(null, loadUiStructure(dir, "large.png"))
            assertEquals(null, loadUiStructure(dir, "../outside.png"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
