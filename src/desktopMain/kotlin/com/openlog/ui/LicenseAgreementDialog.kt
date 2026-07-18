package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openlog.generated.BuildInfo

internal val LICENSE_VERSION: String = BuildInfo.LICENSE_VERSION

internal fun loadLicenseAgreement(): String =
    Thread.currentThread().contextClassLoader
        .getResourceAsStream("licenses/openlog-license-agreement.md")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "The openLog license agreement could not be loaded. Please reinstall openLog."

@Composable
internal fun LicenseAgreementDialog(
    mandatory: Boolean,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val colors = tc()
    val agreement = remember { loadLicenseAgreement() }
    val scroll = rememberScrollState()
    var accepted by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Dialog(
        onDismissRequest = { if (!mandatory) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = !mandatory,
        ),
    ) {
        Column(
            Modifier.width(720.dp).height(600.dp)
                .background(colors.p, shape)
                .border(1.dp, colors.br, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText("openLog License Agreement", color = colors.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AppText("Terms version $LICENSE_VERSION", color = colors.td, fontSize = 10.sp, fontFamily = MONO)
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, colors.br, shape)) {
                AppText(
                    agreement,
                    color = colors.ts,
                    fontSize = 10.sp,
                    fontFamily = MONO,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).padding(end = 8.dp),
                    maxLines = Int.MAX_VALUE,
                )
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                    style = appScrollbarStyle(colors),
                )
            }
            if (mandatory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = accepted,
                        onCheckedChange = { accepted = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.ac,
                            uncheckedColor = colors.td,
                            checkmarkColor = colors.p,
                        ),
                    )
                    AppText("I have read and accept the openLog license agreement.", color = colors.tx, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (mandatory) {
                    AppButton("Decline & Exit", onClick = onDecline, variant = ButtonVariant.Secondary, isDanger = true)
                    AppButton("Accept", onClick = onAccept, variant = ButtonVariant.Primary, enabled = accepted)
                } else {
                    AppButton("Close", onClick = onDismiss, variant = ButtonVariant.Primary)
                }
            }
        }
    }
}
