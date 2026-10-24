package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.indagium.generated.BuildInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val LICENSE_VERSION: String = BuildInfo.LICENSE_VERSION

internal fun loadLicenseAgreement(): String =
    Thread.currentThread().contextClassLoader
        .getResourceAsStream("licenses/indagium-license-agreement.md")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "The Indagium license agreement could not be loaded. Please reinstall Indagium."

internal data class ThirdPartyLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val scope: String,
    val license: String,
    val licenseExpression: String,
    val platforms: List<String>,
    val summary: String,
    val licenseFiles: List<String>,
    val resourceRoot: String = "licenses/third-party/",
)

private fun readLicenseResource(path: String): String? = Thread.currentThread().contextClassLoader
    .getResourceAsStream(path)
    ?.bufferedReader()
    ?.use { it.readText() }

// Both inventories share one entry shape; the application one keeps its licence paths relative to
// licenses/ (it points into third-party/texts too) and marks coverage-only rows with "viewer": false.
private fun loadLicenseInventory(path: String, resourceRoot: String): List<ThirdPartyLicenseEntry> {
    val text = readLicenseResource(path) ?: error("The licence inventory $path is missing")
    return Json.parseToJsonElement(text).jsonObject.getValue("components").jsonArray
        .map { it.jsonObject }
        .filter { item -> item["viewer"]?.jsonPrimitive?.content != "false" }
        .map { item ->
            ThirdPartyLicenseEntry(
                id = item.getValue("id").jsonPrimitive.content,
                name = item.getValue("name").jsonPrimitive.content,
                version = item.getValue("version").jsonPrimitive.content,
                scope = item.getValue("scope").jsonPrimitive.content,
                license = item.getValue("license").jsonPrimitive.content,
                licenseExpression = item.getValue("licenseExpression").jsonPrimitive.content,
                platforms = item["platforms"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                summary = item.getValue("summary").jsonPrimitive.content,
                licenseFiles = item.getValue("licenseFiles").jsonArray.map { it.jsonPrimitive.content },
                resourceRoot = resourceRoot,
            )
        }
}

internal fun loadThirdPartyLicenseEntries(): List<ThirdPartyLicenseEntry> = runCatching {
    val mediaEntries = loadLicenseInventory("licenses/third-party/media-components.json", "licenses/third-party/")
    val applicationEntries = loadLicenseInventory("licenses/application/application-components.json", "licenses/")
    val existingNotices = listOf(
        ThirdPartyLicenseEntry(
            id = "third-party-notices",
            name = "Application and runtime notices",
            version = "Current application dependencies",
            scope = "Bundled Java dependencies, runtime, and native notices",
            license = "Multiple; see component notices",
            licenseExpression = "LicenseRef-Third-Party-Notice-Index",
            platforms = emptyList(),
            summary = "The preserved notice index for application dependencies and bundled runtimes.",
            licenseFiles = listOf("THIRD_PARTY_NOTICES.md"),
            resourceRoot = "licenses/",
        ),
        ThirdPartyLicenseEntry(
            id = "whisper-cpp",
            name = "whisper.cpp",
            version = "1.9.5",
            scope = "Host-built native speech bridge",
            license = "MIT",
            licenseExpression = "MIT",
            platforms = emptyList(),
            summary = "The MIT licence for the bundled whisper.cpp native speech engine.",
            licenseFiles = listOf("whisper.cpp-LICENSE.txt"),
            resourceRoot = "licenses/",
        ),
    )
    (mediaEntries + applicationEntries + existingNotices).sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

private val LICENSE_TEXT_SEPARATOR = "\n\n${"=".repeat(72)}\n\n"

internal fun loadThirdPartyLicenseText(entry: ThirdPartyLicenseEntry): String = entry.licenseFiles.joinToString(LICENSE_TEXT_SEPARATOR) { relative ->
    val resource = entry.resourceRoot + relative
    val contents = readLicenseResource(resource)
    if (contents == null) "[Missing bundled legal resource: $resource]" else "===== $relative =====\n$contents"
}

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
    var showThirdPartyLicenses by remember { mutableStateOf(false) }

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
            AppText("Indagium License Agreement", color = colors.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AppText("Terms version $LICENSE_VERSION", color = colors.td, fontSize = 10.sp, fontFamily = MONO)
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, colors.br, shape)) {
                SelectionContainer {
                    AppText(
                        agreement,
                        color = colors.ts,
                        fontSize = 10.sp,
                        fontFamily = MONO,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).padding(end = 8.dp),
                        maxLines = Int.MAX_VALUE,
                    )
                }
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
                    AppText("I have read and accept the Indagium license agreement.", color = colors.tx, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    "Third-party licences…",
                    onClick = { showThirdPartyLicenses = true },
                    variant = ButtonVariant.Secondary,
                )
                Spacer(Modifier.weight(1f))
                if (mandatory) {
                    AppButton("Decline & Exit", onClick = onDecline, variant = ButtonVariant.Secondary, isDanger = true)
                    AppButton("Accept", onClick = onAccept, variant = ButtonVariant.Primary, enabled = accepted)
                } else {
                    AppButton("Close", onClick = onDismiss, variant = ButtonVariant.Primary)
                }
            }
        }
    }

    if (showThirdPartyLicenses) {
        ThirdPartyLicenseDialog(onDismiss = { showThirdPartyLicenses = false })
    }
}

@Composable
private fun ThirdPartyLicenseDialog(onDismiss: () -> Unit) {
    val colors = tc()
    val shape = RoundedCornerShape(8.dp)
    val entries = remember { loadThirdPartyLicenseEntries() }
    var selectedId by remember { mutableStateOf(entries.firstOrNull()?.id.orEmpty()) }
    val selected = entries.firstOrNull { it.id == selectedId } ?: entries.firstOrNull()
    val selectorScroll = rememberScrollState()
    val textScroll = rememberScrollState()
    val selectedText = remember(selected?.id) { selected?.let(::loadThirdPartyLicenseText).orEmpty() }
    LaunchedEffect(selectedId) { textScroll.scrollTo(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.width(960.dp).height(680.dp)
                .background(colors.p, shape)
                .border(1.dp, colors.br, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText("Third-party licences and notices", color = colors.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AppText("Read-only copies of the bundled component licences and notices.", color = colors.td, fontSize = 10.sp)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(260.dp).fillMaxHeight().border(1.dp, colors.br, shape)) {
                    Column(Modifier.fillMaxSize().verticalScroll(selectorScroll).padding(6.dp)) {
                        entries.forEach { entry ->
                            val active = entry.id == selected?.id
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(if (active) colors.ac.copy(alpha = .12f) else colors.p2, shape)
                                    .clickable {
                                        selectedId = entry.id
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                AppText(entry.name, color = colors.tx, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                AppText("${entry.version} · ${entry.license}", color = colors.td, fontSize = 9.sp, maxLines = 2)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(selectorScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                        style = appScrollbarStyle(colors),
                    )
                }
                if (selected != null) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppText(selected.name, color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        AppText("Version ${selected.version} · ${selected.licenseExpression}", color = colors.td, fontSize = 10.sp, fontFamily = MONO)
                        AppText(selected.summary, color = colors.ts, fontSize = 11.sp)
                        // Only the media inventory is per-platform; application rows ship everywhere.
                        val platforms = selected.platforms.takeIf { it.isNotEmpty() }?.let { " · Platforms: ${it.joinToString()}" }.orEmpty()
                        AppText("Scope: ${selected.scope}$platforms", color = colors.td, fontSize = 9.sp)
                        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, colors.br, shape)) {
                            SelectionContainer {
                                AppText(
                                    selectedText,
                                    color = colors.ts,
                                    fontSize = 9.sp,
                                    fontFamily = MONO,
                                    modifier = Modifier.fillMaxSize().verticalScroll(textScroll).padding(14.dp).padding(end = 8.dp),
                                    maxLines = Int.MAX_VALUE,
                                )
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(textScroll),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                                style = appScrollbarStyle(colors),
                            )
                        }
                    }
                } else {
                    AppText("The third-party component inventory could not be loaded.", color = colors.td, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton("Close", onClick = onDismiss, variant = ButtonVariant.Primary)
            }
        }
    }
}
