package com.indagium.testing

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestVariable
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.newSuiteId
import com.indagium.testing.model.newVariableId
import java.io.File
import kotlin.io.path.createTempDirectory

// Synthetic fixtures only: nothing here is derived from a real log or customer data.

internal fun tempTestingDir(): File = createTempDirectory("indagium-testing-store").toFile()

internal fun plainStep(action: String = "Tap the button") = TestStep(newStepId(), action, expected = "Something happens")

internal fun plainCase(name: String, steps: Int = 0) = TestCase(newCaseId(), name, steps = List(steps) { plainStep("$name step ${it + 1}") })

internal fun suiteWithCases(name: String, caseCount: Int) =
    TestSuite(newSuiteId(), name, cases = List(caseCount) { plainCase("$name case ${it + 1}") })

internal fun sampleScript(toolName: String = "reset_app") = TestScript(
    id = newScriptId(),
    toolName = toolName,
    description = "Clears the app data",
    params = listOf(
        ScriptParam("package_name", ScriptParamType.STRING, "App package", required = true),
        ScriptParam("count", ScriptParamType.INT, required = false, defaultValue = "3"),
        ScriptParam("verbose", ScriptParamType.BOOL, required = false, defaultValue = "false"),
    ),
    commandTemplate = "pm clear \"\$package_name\"",
    target = ScriptTarget.ADB_SHELL,
    timeoutMs = 12_000L,
    outputCapBytes = 2048,
    workingDir = "/tmp/work",
    permission = ScriptPermission.AUTO,
)

internal fun sampleSharedStep() = SharedStep(
    newSharedStepId(),
    "Log in",
    "Signs in with the test account",
    listOf(plainStep("Open login"), plainStep("Submit credentials")),
)

/** A suite that uses every check type, every example type, both hook types, variables and an allow-list. */
internal fun fullSuite(script: TestScript = sampleScript(), shared: SharedStep = sampleSharedStep()): TestSuite {
    val golden = StepExample.GoldenScreenshot(newExampleId(), "golden/home.png", "Home screen")
    val refLog = StepExample.ReferenceLog(newExampleId(), "I/App: ready", "Ready line")
    val step = TestStep(
        id = newStepId(),
        action = "Open settings",
        expected = "The settings screen is shown",
        checks = listOf(
            StepCheck.LogAppears(newCheckId(), tag = "ActivityManager", regex = "Displayed .*Settings", withinMs = 7_000L),
            StepCheck.LogAbsent(newCheckId(), tag = null, regex = "FATAL EXCEPTION", forMs = 3_000L),
            StepCheck.ScreenJudge(newCheckId(), "Settings title is visible", exampleRef = golden.id),
            StepCheck.ScriptResult(newCheckId(), script.id, mapOf("package_name" to "com.example.app"), exitCode = 0, stdoutContains = "Success"),
            StepCheck.ScriptResult(newCheckId(), script.id, emptyMap(), exitCode = null, stdoutContains = null),
            StepCheck.AskJudge(newCheckId(), "Does it look right?"),
        ),
        examples = listOf(golden, refLog),
        timeoutMs = 45_000L,
        retries = 2,
        maxToolCalls = 30,
        onFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE,
    )
    val case = TestCase(
        id = newCaseId(),
        name = "Settings",
        description = "Opens settings",
        instructions = "Be careful",
        preconditions = "Signed in",
        setup = listOf(HookItem.Shared(newHookId(), shared.id)),
        teardown = listOf(HookItem.Script(newHookId(), script.id, mapOf("package_name" to "com.example.app"))),
        steps = listOf(step, plainStep("Go back")),
        allowedTools = setOf("tap", "take_screenshot"),
    )
    return TestSuite(
        id = newSuiteId(),
        name = "Smoke",
        description = "Quick check",
        instructions = "Use the test account",
        targetPackage = "com.example.app",
        deviceProfileHint = "Pixel 8, Android 15",
        tags = listOf("smoke", "settings"),
        setup = listOf(HookItem.Script(newHookId(), script.id, mapOf("package_name" to "com.example.app")), HookItem.Shared(newHookId(), shared.id)),
        teardown = listOf(HookItem.Script(newHookId(), script.id)),
        variables = listOf(TestVariable(newVariableId(), "user", "tester", "Account name")),
        cases = listOf(case, plainCase("Second")),
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )
}
