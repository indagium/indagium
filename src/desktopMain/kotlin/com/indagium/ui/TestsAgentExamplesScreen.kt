package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep

/** Read-only index of the examples already attached to steps; selecting one opens its owning step editor. */
@Composable
internal fun TestsAgentExamplesScreen() {
    val tc = tc()
    val ui = LocalTestsUi.current
    val caseExamples = ui.library.suites.flatMap { suite ->
        suite.cases.flatMap { case ->
            case.steps.flatMap { step -> step.examples.map { example ->
                AgentExampleRow("${suite.name}  /  ${case.name}", step, example, suiteId = suite.id, caseId = case.id)
            } }
        }
    }
    val sharedExamples = ui.library.sharedSteps.flatMap { shared ->
        shared.steps.flatMap { step ->
            step.examples.map { example ->
                AgentExampleRow("Shared steps  /  ${shared.name}", step, example, sharedStepId = shared.id)
            }
        }
    }
    val examples = caseExamples + sharedExamples
    TestsScreenScaffold {
        AppText("Examples for agents", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        TestsHint("Examples are reference material already attached to steps. Select a row to edit that step's examples.")
        Spacer(Modifier.height(10.dp))
        if (examples.isEmpty()) {
            TestsHint("No step examples yet. Add a golden screenshot or reference log in a step editor.")
        } else {
            examples.forEach { item ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).background(tc.p2, RoundedCornerShape(6.dp))
                        .clickable {
                            if (item.sharedStepId != null) {
                                ui.view.nav = TestsNav.SharedSteps
                                ui.view.selectedSharedStepId = item.sharedStepId
                            } else {
                                ui.view.nav = TestsNav.Suites
                                ui.view.selectedSuiteId = item.suiteId
                                ui.view.selectedCaseId = item.caseId
                            }
                            ui.view.expandedStepId = item.step.id
                        }.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppText(item.example.kindName(), color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        AppText(item.example.caption.ifBlank { item.example.defaultCaption() }, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                    AppText("${item.ownerName}  /  ${item.step.action}", color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (val example = item.example) {
                        is StepExample.ReferenceLog -> if (example.text.isNotBlank()) {
                            AppText(example.text.take(220), color = tc.td, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = MONO)
                        }
                        is StepExample.GoldenScreenshot -> AppText("Asset: ${example.assetPath}", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }
        }
    }
}

private data class AgentExampleRow(
    val ownerName: String,
    val step: TestStep,
    val example: StepExample,
    val suiteId: String? = null,
    val caseId: String? = null,
    val sharedStepId: String? = null,
)

private fun StepExample.kindName(): String = when (this) {
    is StepExample.GoldenScreenshot -> "SCREENSHOT"
    is StepExample.ReferenceLog -> "REFERENCE LOG"
}

private fun StepExample.defaultCaption(): String = when (this) {
    is StepExample.GoldenScreenshot -> "Golden screenshot"
    is StepExample.ReferenceLog -> "Reference log"
}
