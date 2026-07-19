package com.openlog.ui

import androidx.compose.ui.graphics.Color
import com.openlog.ai.normalizeAiProviderProfiles
import com.openlog.model.*
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.ZipLogCandidateKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

// Extracted from AppState (ARCH-1, sub-batch 6a, mechanical — no behavior change): the
// serialization/token codec — JSON string helpers, per-type token encode/decode pairs, the
// settings JSON codec, the legacy positional settings decoder, and the tab-shell codec used by
// AppState.restoreTabsFromAutosave. None of the orchestration (serializeAutosave/restoreAutosave/
// restoreAutosaveKey/restoreTabsFromAutosave/splitAutosaveLines/draftsToken) moved — those stay on
// AppState as thin callers of the functions below. See AppState.kt's class body for that half.

// (ARCH-2) Named so settingsFromJson()'s logRowWrapLimitChars bound/default aren't fresh
// magic-number findings — settingsFromToken() below keeps its own inline 80/20_000/480 literals
// untouched (it's legacy-read-only, see its doc comment) rather than being migrated onto these too.
internal const val MIN_LOG_ROW_WRAP_LIMIT_CHARS = 80
internal const val MAX_LOG_ROW_WRAP_LIMIT_CHARS = 20_000
internal const val DEFAULT_LOG_ROW_WRAP_LIMIT_CHARS = 480
private const val LEGACY_SETTINGS_OPEN_UNFILTERED_ON_CTRL_F_OFFSET = 17
private const val LEGACY_SETTINGS_MCP_ALLOW_BROWSER_CLIENTS_OFFSET = 18

// ── JSON helpers (small reader for exported filter files) ─────────────
internal fun String.jsonStr(): String = buildString {
    append('"')
    this@jsonStr.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

internal fun Map<String, Any?>.stringField(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.booleanField(key: String): Boolean = this[key] == true

internal fun Map<String, Any?>.stringArrayField(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

internal fun String.jsonObjectArray(): Result<List<Map<String, Any?>>> =
    runCatching { JsonReader(this).readObjectArray() }

private class JsonReader(private val text: String) {
    private var pos = 0

    fun readObjectArray(): List<Map<String, Any?>> {
        skipWs()
        expect('[')
        val objects = mutableListOf<Map<String, Any?>>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return objects
        }
        while (true) {
            objects += readObject()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return objects
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos += 1
            return map
        }
        while (true) {
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                '}' -> {
                    pos += 1
                    return map
                }

                else -> error("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '"' -> readString()
        '[' -> readStringArray()
        't' -> {
            expectText("true")
            true
        }

        'f' -> {
            expectText("false")
            false
        }

        else -> error("Unsupported JSON value at $pos")
    }

    private fun readStringArray(): List<String> {
        expect('[')
        val values = mutableListOf<String>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return values
        }
        while (true) {
            values += readString()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return values
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = text.getOrNull(pos++) ?: error("Dangling escape at $pos")
                    out.append(
                        when (esc) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> readUnicode()
                            else -> error("Unsupported escape \\$esc at $pos")
                        },
                    )
                }

                else -> out.append(ch)
            }
        }
        error("Unterminated string")
    }

    private fun readUnicode(): Char {
        if (pos + 4 > text.length) error("Short unicode escape at $pos")
        val hex = text.substring(pos, pos + 4)
        pos += 4
        return hex.toInt(16).toChar()
    }

    private fun expect(ch: Char) {
        skipWs()
        if (peek() != ch) error("Expected '$ch' at $pos")
        pos += 1
    }

    private fun expectText(value: String) {
        if (!text.startsWith(value, pos)) error("Expected '$value' at $pos")
        pos += value.length
    }

    private fun peek(): Char = text.getOrNull(pos) ?: error("Unexpected end of JSON")

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos += 1
    }
}

internal fun SequenceDef.sequenceToken(): String =
    listOf(
        id,
        matchText,
        isRegex.toString(),
        priority.toString(),
        color.value.toString(),
        enabled.toString(),
        tag.orEmpty(),
        endMatchText.orEmpty(),
        endIsRegex.toString(),
        endTag.orEmpty(),
    ).joinToString("|") { it.b64() }

internal fun String.sequenceFromToken(): SequenceDef? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 10) return@runCatching null
    SequenceDef(
        id = parts[0],
        matchText = parts[1],
        isRegex = parts[2].toBoolean(),
        priority = parts[3].toIntOrNull() ?: 0,
        color = Color(parts[4].toULong()),
        enabled = parts[5].toBoolean(),
        tag = parts[6].takeIf { it.isNotBlank() },
        endMatchText = parts[7].takeIf { it.isNotBlank() },
        endIsRegex = parts[8].toBoolean(),
        endTag = parts[9].takeIf { it.isNotBlank() },
    )
}.getOrNull()

internal fun MessageRule.messageRuleToken(): String =
    listOf(
        id,
        include.toString(),
        pattern,
        regex.toString(),
        tag.orEmpty(),
        packagePrefix.orEmpty(),
        enabled.toString(),
        target.name,
        mode.name,
    ).joinToString("|") { it.b64() }

internal fun String.messageRuleFromToken(): MessageRule? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 7) return@runCatching null
    MessageRule(
        id = parts[0],
        include = parts[1].toBoolean(),
        pattern = parts[2],
        regex = parts[3].toBoolean(),
        tag = parts[4].takeIf { it.isNotBlank() },
        packagePrefix = parts[5].takeIf { it.isNotBlank() },
        enabled = parts[6].toBoolean(),
        target = if (parts.size > 7) runCatching { RuleTarget.valueOf(parts[7]) }.getOrElse { RuleTarget.MESSAGE } else RuleTarget.MESSAGE,
        mode = if (parts.size > 8) runCatching { FilterMode.valueOf(parts[8]) }.getOrElse { FilterMode.TAGS } else FilterMode.TAGS,
    )
}.getOrNull()

private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun tokenFields(vararg values: String): String = values.joinToString("|") { it.fieldToken() }

internal fun String.tokenFields(): List<String> = split("|", limit = Int.MAX_VALUE).map { it.fieldValue() }

// Some issue trackers (certain Jira instances) reject a comment containing the literal word
// "java" outside a code block. Masks a configurable whole word (default "java") when copying a
// note's Markdown — skips the {code:java}/{code} fence marker lines buildMd() emits for
// AnnotationLogBlockStyle.JIRA_JAVA so the block's type token itself is never mangled.
internal fun maskWordForCopy(text: String, settings: AppSettings): String {
    if (!settings.maskWordOnCopy) return text
    val rules = settings.effectiveCopyMaskRules().filter { it.target.isNotBlank() }
    if (rules.isEmpty()) return text
    return text.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed == "{code:java}" || trimmed == "{code}") line
        else rules.fold(line) { masked, rule ->
            Regex("\\b${Regex.escape(rule.target)}\\b").replace(masked, rule.replacement)
        }
    }
}

private fun AppSettings.effectiveCopyMaskRules(): List<CopyMaskRule> {
    val defaultRule = CopyMaskRule("java", "j*ava")
    val legacyRule = CopyMaskRule(maskWordTarget, maskWordReplacement)
    // Retain correct behavior for source-compatible callers that still set the previous pair.
    // Autosave restoration always populates copyMaskRules explicitly, so an intentionally empty
    // modern collection remains empty and disables replacements even while the master switch is on.
    return if (copyMaskRules == listOf(defaultRule) && legacyRule != defaultRule) listOf(legacyRule) else copyMaskRules
}

internal fun analysisNoteMarkdownName(filename: String, sourcePath: String? = null): String {
    val safeBase = noteBaseName(filename, sourcePath).replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "analysis" }
    return "${safeBase}_analysis.md"
}

// For archive-sourced tabs (sourcePath "<absZipPath>!<entryPath>"), fold the archive filename
// into the note's base name so two different archives' identically-named entries — every bug
// report's "logcat.log" — don't all collapse to one "logcat_analysis.md" and then get pushed
// apart into opaque "_2".."_10" suffixes (resolveNoteTarget's collision walk) that carry no hint
// of which archive/ticket they belong to. Plain files keep their bare filename base unchanged.
private fun noteBaseName(filename: String, sourcePath: String?): String {
    val entryBase = filename.substringBeforeLast('.', filename)
    val bangIdx = sourcePath?.indexOf('!') ?: -1
    if (bangIdx < 0) return entryBase
    val zipName = sourcePath!!.substring(0, bangIdx).substringAfterLast('/')
    val zipBase = zipName.substringBeforeLast('.', zipName)
    return if (zipBase.isBlank()) entryBase else "${zipBase}_$entryBase"
}

private fun String.tokenList(): List<String> =
    if (isBlank()) emptyList() else unb64().split(",").filter { it.isNotBlank() }

internal fun String.pathTokenList(): List<String> =
    tokenList().map { item -> runCatching { item.unb64() }.getOrElse { item } }

private fun String.copyMaskRulesFromToken(): List<CopyMaskRule> = runCatching {
    if (isBlank()) return@runCatching emptyList()
    unb64().split(',').filter { it.isNotBlank() }.mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 2) return@runCatching null
            CopyMaskRule(target = fields[0], replacement = fields[1])
        }.getOrNull()
    }
}.getOrElse { emptyList() }

private fun String.aiProviderProfilesFromToken(): List<AiProviderProfile> = runCatching {
    if (isBlank()) return@runCatching listOf(defaultAiProviderProfile())
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 6 || fields[0].isBlank()) return@runCatching null
            AiProviderProfile(
                id = fields[0],
                displayName = fields[1].ifBlank { "OpenAI-compatible" },
                baseUrl = fields[2],
                model = fields[3],
                selected = fields[4].toBooleanStrictOrNull() ?: false,
                remoteDisclosureAcknowledged = fields[5].toBooleanStrictOrNull() ?: false,
                kind = fields.getOrNull(6)?.let { raw ->
                    runCatching { AiProviderKind.valueOf(raw) }.getOrNull()
                } ?: AiProviderKind.OPENAI_COMPATIBLE,
                executablePath = fields.getOrNull(7).orEmpty(),
                reasoningEffort = fields.getOrNull(8).orEmpty(),
            )
        }.getOrNull()
    }.let(::normalizeAiProviderProfiles)
}.getOrElse { listOf(defaultAiProviderProfile()) }

private fun String.sourceFolderInfoFromToken(): Map<String, SourceFolderInfo> = runCatching {
    if (isBlank()) return@runCatching emptyMap()
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 3 || fields[0].isBlank()) return@runCatching null
            fields[0] to SourceFolderInfo(description = fields[1], readmePath = fields[2].takeIf { it.isNotBlank() })
        }.getOrNull()
    }.toMap()
}.getOrElse { emptyMap() }

private fun String.wrapperRuleFromToken(): SourceWrapperRule? = runCatching {
    val fields = tokenFields()
    if (fields.size < 4 || fields[0].isBlank() || fields[1].isBlank()) return@runCatching null
    SourceWrapperRule(
        ownerType = fields[0],
        methodName = fields[1],
        tagArgumentIndex = fields[2].toIntOrNull() ?: return@runCatching null,
        messageArgumentIndex = fields[3].toIntOrNull() ?: return@runCatching null,
        throwableArgumentIndex = fields.getOrNull(4)?.toIntOrNull(),
    )
}.getOrNull()

private fun String.configurationFromToken(): SourceLogConfiguration? = runCatching {
    val fields = tokenFields()
    if (fields.size < 2 || fields[0].isBlank()) return@runCatching null
    // v1 configurations included a per-configuration auto-discovery flag in field 2. The
    // setting is global now; preserve the rules while intentionally ignoring that legacy value.
    val legacyAutoDiscovery = fields.getOrNull(2)?.toBooleanStrictOrNull() != null
    SourceLogConfiguration(
        id = fields[0],
        name = fields[1].ifBlank { "Logging configuration" },
        wrapperRules = fields.getOrNull(if (legacyAutoDiscovery) 3 else 2).orEmpty().split(',').filter { it.isNotBlank() }
            .mapNotNull { encoded -> runCatching { encoded.unb64().wrapperRuleFromToken() }.getOrNull() },
    )
}.getOrNull()

private fun String.sourceLogConfigurationsFromToken(): List<SourceLogConfiguration> = runCatching {
    if (isBlank()) return@runCatching emptyList()
    unb64().split(',').filter { it.isNotBlank() }.mapNotNull { encoded ->
        runCatching { encoded.unb64().configurationFromToken() }.getOrNull()
    }.distinctBy { it.id }
}.getOrElse { emptyList() }

private fun String.sourceFolderConfigurationIdsFromToken(): Map<String, List<String>> = runCatching {
    if (isBlank()) return@runCatching emptyMap()
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 2 || fields[0].isBlank()) return@runCatching null
            fields[0] to fields[1].split(',').filter { it.isNotBlank() }.mapNotNull { id ->
                runCatching { id.unb64() }.getOrNull()
            }.distinct()
        }.getOrNull()
    }.filter { it.second.isNotEmpty() }.toMap()
}.getOrElse { emptyMap() }

// (ARCH-2) LEGACY READ-ONLY: decodes the positional pipe-delimited settings blob written before
// this migration. Every field is looked up by an index derived from how many earlier fields
// happen to exist (mcpIndex + N) — exactly the fragile pattern that made a mis-ordered append
// silently shift every field after it. Never extend this positional layout again; new AppSettings
// fields belong only in settingsJson()/settingsFromJson() below, where each field has an explicit
// name and a missing/new key can never shift another. This function (and the *FromToken readers it
// calls) must stay byte-for-byte capable of decoding old caches, including the frozen
// AutosaveGoldenV1Test fixture.
internal fun settingsFromToken(token: String): AppSettings? = runCatching {
    val p = token.tokenFields()
    if (p.size < 5) return@runCatching null
    val hasFilterAutosaveField = p.getOrNull(8)?.toBooleanStrictOrNull() != null
    val tailOffset = if (hasFilterAutosaveField) 1 else 0
    val wrapIndex = 12 + tailOffset
    val hasWrapLimitField = p.getOrNull(wrapIndex)?.toIntOrNull() != null
    val autoWrapIndex = wrapIndex + 1
    val hasAutoWrapField = hasWrapLimitField &&
        p.getOrNull(autoWrapIndex)?.toBooleanStrictOrNull() != null &&
        p.getOrNull(autoWrapIndex + 1)?.toBooleanStrictOrNull() != null
    val mcpIndex = wrapIndex + (if (hasWrapLimitField) 1 else 0) + (if (hasAutoWrapField) 1 else 0)
    val legacyMaskTarget = p.getOrNull(mcpIndex + 3)?.takeIf { it.isNotBlank() } ?: "java"
    val legacyMaskReplacement = p.getOrNull(mcpIndex + 4)?.takeIf { it.isNotBlank() } ?: "j*ava"
    // These are deliberately appended after the pre-existing source settings so every historic
    // token position stays stable. A missing rules field represents the previous single pair.
    val copyMaskRules = p.getOrNull(mcpIndex + 16)?.copyMaskRulesFromToken()
        ?: listOf(CopyMaskRule(legacyMaskTarget, legacyMaskReplacement))
    AppSettings(
        theme = runCatching { ThemePreset.valueOf(p[0]) }.getOrElse { ThemePreset.LIGHT },
        fontSize = p[1].toIntOrNull() ?: 12,
        fontMono = p[2].toBoolean(),
        defaultSaveDir = p[3].takeIf { it.isNotBlank() },
        mostUsedTagLimit = p[4].toIntOrNull() ?: 5,
        filterListRows = p.getOrNull(5)?.toIntOrNull()?.coerceIn(1, 20) ?: 5,
        visibleTabLimit = p.getOrNull(6)?.toIntOrNull()?.coerceIn(2, 20) ?: 8,
        autoExportNotes = p.getOrNull(7)?.toBooleanStrictOrNull() ?: true,
        autoSaveFilters = if (hasFilterAutosaveField) p.getOrNull(8)?.toBooleanStrictOrNull() ?: true else true,
        annotationLogBlockStyle = p.getOrNull(8 + tailOffset)
            ?.let { runCatching { AnnotationLogBlockStyle.valueOf(it) }.getOrNull() }
            ?: AnnotationLogBlockStyle.INDENTED,
        numberAnnotationBlocks = p.getOrNull(9 + tailOffset)?.toBooleanStrictOrNull() ?: false,
        annotationPrefixLabel = p.getOrNull(10 + tailOffset)?.takeIf { it.isNotBlank() } ?: "From",
        navScrollMargin = p.getOrNull(11 + tailOffset)?.toIntOrNull()?.coerceIn(0, 30) ?: 5,
        logRowWrapLimitChars = p.getOrNull(wrapIndex)?.toIntOrNull()?.coerceIn(80, 20_000) ?: 480,
        autoLogRowWrap = if (hasAutoWrapField) {
            p.getOrNull(autoWrapIndex)?.toBooleanStrictOrNull() ?: true
        } else {
            true
        },
        mcpControlEnabled = p.getOrNull(mcpIndex)?.toBooleanStrictOrNull() ?: false,
        mcpControlPort = p.getOrNull(mcpIndex + 1)?.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT) ?: DEFAULT_MCP_PORT,
        maskWordOnCopy = p.getOrNull(mcpIndex + 2)?.toBooleanStrictOrNull() ?: false,
        maskWordTarget = legacyMaskTarget,
        maskWordReplacement = legacyMaskReplacement,
        highlightEntireCrashGroup = p.getOrNull(mcpIndex + 5)?.toBooleanStrictOrNull() ?: false,
        ctrlFTarget = p.getOrNull(mcpIndex + 6)
            ?.let { runCatching { CtrlFTarget.valueOf(it) }.getOrNull() }
            ?: CtrlFTarget.KEYWORD_REGEX,
        openNewFilesWithUnfiltered = p.getOrNull(mcpIndex + 7)?.toBooleanStrictOrNull() ?: false,
        // Missing entirely (old token, predates this field) -> emptyList(); present-but-empty
        // (fieldToken's "~" for an empty list) -> also emptyList() via pathTokenList()'s own blank
        // check. This field is pathTokenList()-shaped (comma-joined b64 paths, b64'd once more)
        // rather than a plain fieldToken() string — a relic of the retired positional writer.
        sourceFolders = p.getOrNull(mcpIndex + 8)?.pathTokenList() ?: emptyList(),
        editorCommand = p.getOrNull(mcpIndex + 9)?.takeIf { it.isNotBlank() } ?: "",
        aiProviderProfiles = p.getOrNull(mcpIndex + 10)?.aiProviderProfilesFromToken()
            ?: listOf(defaultAiProviderProfile()),
        aiMaxToolRounds = p.getOrNull(mcpIndex + 11)?.toIntOrNull()
            ?.coerceIn(MIN_AI_MAX_TOOL_ROUNDS, MAX_AI_MAX_TOOL_ROUNDS)
            ?: DEFAULT_AI_MAX_TOOL_ROUNDS,
        // Missing entirely (old token, predates this field) -> emptyMap(), same backward-compat
        // pattern as sourceFolders above.
        sourceFolderInfo = p.getOrNull(mcpIndex + 12)?.sourceFolderInfoFromToken() ?: emptyMap(),
        sourceLogConfigurations = p.getOrNull(mcpIndex + 13)?.sourceLogConfigurationsFromToken() ?: emptyList(),
        sourceFolderConfigurationIds = p.getOrNull(mcpIndex + 14)?.sourceFolderConfigurationIdsFromToken() ?: emptyMap(),
        sourceAutoDiscoveryEnabled = p.getOrNull(mcpIndex + 15)?.toBooleanStrictOrNull() ?: true,
        copyMaskRules = copyMaskRules,
        openUnfilteredOnCtrlF = p.getOrNull(mcpIndex + LEGACY_SETTINGS_OPEN_UNFILTERED_ON_CTRL_F_OFFSET)
            ?.toBooleanStrictOrNull() ?: false,
        mcpAllowBrowserClients = p.getOrNull(mcpIndex + LEGACY_SETTINGS_MCP_ALLOW_BROWSER_CLIENTS_OFFSET)
            ?.toBooleanStrictOrNull() ?: false,
    )
}.getOrNull()

// (ARCH-2) Current settings format: a single keyed JSON object (the "settings" autosave line
// carries this text b64'd once, see serializeAutosave()). Every field is looked up by name, so
// adding, removing, or reordering fields here never shifts any other field's value — the exact
// failure mode settingsFromToken()/settingsToken() above were retired to fix. Kept as plain
// kotlinx.serialization.json runtime calls (buildJsonObject/Json.parseToJsonElement), the same
// pattern already used in debug/ControlServer.kt and ai/AnthropicMessagesProvider.kt, rather than
// @Serializable data classes, so this migration doesn't need a new Gradle plugin.
internal fun AppSettings.settingsJson(): String = buildJsonObject {
    put("formatVersion", 1)
    put("theme", theme.name)
    put("fontSize", fontSize)
    put("fontMono", fontMono)
    defaultSaveDir?.let { put("defaultSaveDir", it) }
    put("mostUsedTagLimit", mostUsedTagLimit)
    put("filterListRows", filterListRows)
    put("visibleTabLimit", visibleTabLimit)
    put("autoExportNotes", autoExportNotes)
    put("autoSaveFilters", autoSaveFilters)
    put("annotationLogBlockStyle", annotationLogBlockStyle.name)
    put("numberAnnotationBlocks", numberAnnotationBlocks)
    put("annotationPrefixLabel", annotationPrefixLabel)
    put("navScrollMargin", navScrollMargin)
    put("logRowWrapLimitChars", logRowWrapLimitChars)
    put("autoLogRowWrap", autoLogRowWrap)
    put("mcpControlEnabled", mcpControlEnabled)
    put("mcpControlPort", mcpControlPort)
    put("maskWordOnCopy", maskWordOnCopy)
    put("maskWordTarget", maskWordTarget)
    put("maskWordReplacement", maskWordReplacement)
    put("highlightEntireCrashGroup", highlightEntireCrashGroup)
    put("ctrlFTarget", ctrlFTarget.name)
    put("openNewFilesWithUnfiltered", openNewFilesWithUnfiltered)
    put("openUnfilteredOnCtrlF", openUnfilteredOnCtrlF)
    put("sourceFolders", buildJsonArray { sourceFolders.forEach { add(it) } })
    put("editorCommand", editorCommand)
    put("editorChoice", editorChoice)
    put("aiMaxToolRounds", aiMaxToolRounds)
    put("sourceAutoDiscoveryEnabled", sourceAutoDiscoveryEnabled)
    put("sourceFolderInfo", sourceFolderInfoJson(sourceFolderInfo))
    put("sourceLogConfigurations", sourceLogConfigurationsJson(sourceLogConfigurations))
    put("sourceFolderConfigurationIds", sourceFolderConfigurationIdsJson(sourceFolderConfigurationIds))
    // normalizeAiProviderProfiles() is what serializeAutosave() previously relied on
    // aiProviderProfilesToken() to apply — reused here so the persisted list is always
    // exactly-one-selected/id-deduplicated the same way the writer always guaranteed before.
    put("aiProviderProfiles", aiProviderProfilesJson(normalizeAiProviderProfiles(aiProviderProfiles)))
    put("copyMaskRules", copyMaskRulesJson(copyMaskRules))
    put("mcpAllowBrowserClients", mcpAllowBrowserClients)
    put("showRowNumbers", showRowNumbers)
    put("toolbarIconOnlyButtons", toolbarIconOnlyButtons)
    acceptedLicenseVersion?.let { put("acceptedLicenseVersion", it) }
    put("autoCheckUpdates", autoCheckUpdates)
    skippedUpdateVersion?.let { put("skippedUpdateVersion", it) }
    updateDownloadDir?.let { put("updateDownloadDir", it) }
    put("debugLoggingEnabled", debugLoggingEnabled)
    debugLogFilePath?.let { put("debugLogFilePath", it) }
}.toString()

private fun sourceFolderInfoJson(info: Map<String, SourceFolderInfo>) = buildJsonObject {
    info.forEach { (path, value) ->
        put(
            path,
            buildJsonObject {
                put("description", value.description)
                value.readmePath?.let { put("readmePath", it) }
            },
        )
    }
}

private fun sourceLogConfigurationsJson(configs: List<SourceLogConfiguration>) = buildJsonArray {
    configs.forEach { cfg ->
        add(
            buildJsonObject {
                put("id", cfg.id)
                put("name", cfg.name)
                put("wrapperRules", wrapperRulesJson(cfg.wrapperRules))
            },
        )
    }
}

private fun wrapperRulesJson(rules: List<SourceWrapperRule>) = buildJsonArray {
    rules.forEach { rule ->
        add(
            buildJsonObject {
                put("ownerType", rule.ownerType)
                put("methodName", rule.methodName)
                put("tagArgumentIndex", rule.tagArgumentIndex)
                put("messageArgumentIndex", rule.messageArgumentIndex)
                rule.throwableArgumentIndex?.let { put("throwableArgumentIndex", it) }
            },
        )
    }
}

private fun sourceFolderConfigurationIdsJson(ids: Map<String, List<String>>) = buildJsonObject {
    ids.forEach { (path, configIds) -> put(path, buildJsonArray { configIds.forEach { add(it) } }) }
}

private fun aiProviderProfilesJson(profiles: List<AiProviderProfile>) = buildJsonArray {
    profiles.forEach { profile ->
        add(
            buildJsonObject {
                put("id", profile.id)
                put("displayName", profile.displayName)
                put("baseUrl", profile.baseUrl)
                put("model", profile.model)
                put("selected", profile.selected)
                put("remoteDisclosureAcknowledged", profile.remoteDisclosureAcknowledged)
                put("kind", profile.kind.name)
                put("executablePath", profile.executablePath)
                put("reasoningEffort", profile.reasoningEffort)
            },
        )
    }
}

private fun copyMaskRulesJson(rules: List<CopyMaskRule>) = buildJsonArray {
    rules.forEach { rule -> add(buildJsonObject { put("target", rule.target); put("replacement", rule.replacement) }) }
}

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.intOrDefault(key: String, default: Int): Int = this[key]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

private fun JsonObject.sourceFolderInfoFromJson(key: String): Map<String, SourceFolderInfo> =
    (this[key] as? JsonObject)?.entries?.mapNotNull { (path, value) ->
        val obj = value as? JsonObject ?: return@mapNotNull null
        path to SourceFolderInfo(
            description = obj.stringOrNull("description").orEmpty(),
            readmePath = obj.stringOrNull("readmePath"),
        )
    }?.toMap() ?: emptyMap()

private fun JsonObject.wrapperRuleFromJson(): SourceWrapperRule? {
    val ownerType = stringOrNull("ownerType") ?: return null
    val methodName = stringOrNull("methodName") ?: return null
    return SourceWrapperRule(
        ownerType = ownerType,
        methodName = methodName,
        tagArgumentIndex = intOrDefault("tagArgumentIndex", 0),
        messageArgumentIndex = intOrDefault("messageArgumentIndex", 1),
        throwableArgumentIndex = this["throwableArgumentIndex"]?.jsonPrimitive?.intOrNull,
    )
}

private fun JsonObject.sourceLogConfigurationsFromJson(key: String): List<SourceLogConfiguration> =
    (this[key] as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.stringOrNull("id") ?: return@mapNotNull null
        val wrapperRules = (obj["wrapperRules"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.wrapperRuleFromJson() }
            ?: emptyList()
        SourceLogConfiguration(id = id, name = obj.stringOrNull("name") ?: "Logging configuration", wrapperRules = wrapperRules)
    }?.distinctBy { it.id } ?: emptyList()

private fun JsonObject.sourceFolderConfigurationIdsFromJson(key: String): Map<String, List<String>> =
    (this[key] as? JsonObject)?.entries?.mapNotNull { (path, value) ->
        val ids = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        if (ids.isEmpty()) null else path to ids
    }?.toMap() ?: emptyMap()

private fun JsonObject.aiProviderProfilesFromJson(key: String): List<AiProviderProfile> {
    val profiles = (this[key] as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AiProviderProfile(
            id = id,
            displayName = obj.stringOrNull("displayName")?.ifBlank { "OpenAI-compatible" } ?: "OpenAI-compatible",
            baseUrl = obj.stringOrNull("baseUrl").orEmpty(),
            model = obj.stringOrNull("model").orEmpty(),
            selected = obj.boolOrDefault("selected", false),
            remoteDisclosureAcknowledged = obj.boolOrDefault("remoteDisclosureAcknowledged", false),
            kind = obj.stringOrNull("kind")?.let { raw -> runCatching { AiProviderKind.valueOf(raw) }.getOrNull() }
                ?: AiProviderKind.OPENAI_COMPATIBLE,
            executablePath = obj.stringOrNull("executablePath").orEmpty(),
            reasoningEffort = obj.stringOrNull("reasoningEffort").orEmpty(),
        )
    } ?: return listOf(defaultAiProviderProfile())
    return normalizeAiProviderProfiles(profiles)
}

private fun JsonObject.copyMaskRulesFromJson(key: String): List<CopyMaskRule> =
    (this[key] as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        CopyMaskRule(target = obj.stringOrNull("target").orEmpty(), replacement = obj.stringOrNull("replacement").orEmpty())
    } ?: listOf(CopyMaskRule("java", "j*ava"))

// (ARCH-2) Reads the current keyed-JSON settings format written by settingsJson() above. Every
// lookup is by name with an explicit default matching AppSettings' own default — a missing key
// (old cache predating a field, or a hand-edited file) falls back to that field's default rather
// than misreading a neighboring field's value, which is exactly what positional decoding couldn't
// guarantee. Never throws: a malformed document (or an unexpected element type via jsonPrimitive's
// cast) is caught by the outer runCatching and reported as a hard failure (null), same contract as
// settingsFromToken() above.
internal fun settingsFromJson(raw: String): AppSettings? = runCatching {
    val o = Json.parseToJsonElement(raw).jsonObject
    val editorCommandValue = o.stringOrNull("editorCommand").orEmpty()
    AppSettings(
        theme = o.stringOrNull("theme")?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() } ?: ThemePreset.LIGHT,
        fontSize = o.intOrDefault("fontSize", 12),
        fontMono = o.boolOrDefault("fontMono", true),
        defaultSaveDir = o.stringOrNull("defaultSaveDir"),
        mostUsedTagLimit = o.intOrDefault("mostUsedTagLimit", 5),
        filterListRows = o.intOrDefault("filterListRows", 5).coerceIn(1, 20),
        visibleTabLimit = o.intOrDefault("visibleTabLimit", 8).coerceIn(2, 20),
        autoExportNotes = o.boolOrDefault("autoExportNotes", true),
        autoSaveFilters = o.boolOrDefault("autoSaveFilters", true),
        annotationLogBlockStyle = o.stringOrNull("annotationLogBlockStyle")
            ?.let { runCatching { AnnotationLogBlockStyle.valueOf(it) }.getOrNull() }
            ?: AnnotationLogBlockStyle.JIRA_JAVA,
        numberAnnotationBlocks = o.boolOrDefault("numberAnnotationBlocks", false),
        annotationPrefixLabel = o.stringOrNull("annotationPrefixLabel")?.takeIf { it.isNotBlank() } ?: "From",
        navScrollMargin = o.intOrDefault("navScrollMargin", 5).coerceIn(0, 30),
        logRowWrapLimitChars = o.intOrDefault("logRowWrapLimitChars", DEFAULT_LOG_ROW_WRAP_LIMIT_CHARS)
            .coerceIn(MIN_LOG_ROW_WRAP_LIMIT_CHARS, MAX_LOG_ROW_WRAP_LIMIT_CHARS),
        autoLogRowWrap = o.boolOrDefault("autoLogRowWrap", true),
        mcpControlEnabled = o.boolOrDefault("mcpControlEnabled", false),
        mcpControlPort = o.intOrDefault("mcpControlPort", DEFAULT_MCP_PORT).coerceIn(MIN_PORT, MAX_PORT),
        maskWordOnCopy = o.boolOrDefault("maskWordOnCopy", false),
        maskWordTarget = o.stringOrNull("maskWordTarget")?.takeIf { it.isNotBlank() } ?: "java",
        maskWordReplacement = o.stringOrNull("maskWordReplacement")?.takeIf { it.isNotBlank() } ?: "j*ava",
        highlightEntireCrashGroup = o.boolOrDefault("highlightEntireCrashGroup", false),
        ctrlFTarget = o.stringOrNull("ctrlFTarget")?.let { runCatching { CtrlFTarget.valueOf(it) }.getOrNull() }
            ?: CtrlFTarget.KEYWORD_REGEX,
        openNewFilesWithUnfiltered = o.boolOrDefault("openNewFilesWithUnfiltered", false),
        openUnfilteredOnCtrlF = o.boolOrDefault("openUnfilteredOnCtrlF", false),
        sourceFolders = o.stringArray("sourceFolders"),
        editorCommand = editorCommandValue,
        // Migration default: a legacy blob (predates editorChoice) with a typed editorCommand keeps
        // behaving exactly as before by reading back as "custom"; a legacy blank command reads back
        // as "auto" — see AppSettings.editorChoice doc.
        editorChoice = o.stringOrNull("editorChoice")
            ?: if (editorCommandValue.isNotBlank()) "custom" else "auto",
        aiProviderProfiles = o.aiProviderProfilesFromJson("aiProviderProfiles"),
        aiMaxToolRounds = o.intOrDefault("aiMaxToolRounds", DEFAULT_AI_MAX_TOOL_ROUNDS)
            .coerceIn(MIN_AI_MAX_TOOL_ROUNDS, MAX_AI_MAX_TOOL_ROUNDS),
        sourceFolderInfo = o.sourceFolderInfoFromJson("sourceFolderInfo"),
        sourceLogConfigurations = o.sourceLogConfigurationsFromJson("sourceLogConfigurations"),
        sourceFolderConfigurationIds = o.sourceFolderConfigurationIdsFromJson("sourceFolderConfigurationIds"),
        sourceAutoDiscoveryEnabled = o.boolOrDefault("sourceAutoDiscoveryEnabled", true),
        copyMaskRules = o.copyMaskRulesFromJson("copyMaskRules"),
        mcpAllowBrowserClients = o.boolOrDefault("mcpAllowBrowserClients", false),
        showRowNumbers = o.boolOrDefault("showRowNumbers", false),
        toolbarIconOnlyButtons = o.boolOrDefault("toolbarIconOnlyButtons", true),
        acceptedLicenseVersion = o.stringOrNull("acceptedLicenseVersion"),
        autoCheckUpdates = o.boolOrDefault("autoCheckUpdates", true),
        skippedUpdateVersion = o.stringOrNull("skippedUpdateVersion"),
        updateDownloadDir = o.stringOrNull("updateDownloadDir"),
        debugLoggingEnabled = o.boolOrDefault("debugLoggingEnabled", false),
        debugLogFilePath = o.stringOrNull("debugLogFilePath"),
    )
}.getOrNull()

internal fun AppState.compareStateToken(): String = tokenFields(
    compareTabId,
    compareMode.toString(),
    compareFilterRight.toString(),
    filterVisible.toString(),
    annotationVisible.toString(),
    filterPanelWidth.toString(),
    annotationPanelWidth.toString(),
    compareSplit.toString(),
    aiPanelVisible.toString(),
    rightSidebarSplit.toString(),
)

internal fun AppState.restoreCompareState(token: String) {
    val p = token.tokenFields()
    if (p.size < 8) return
    compareTabId = p[0]
    compareMode = p[1].toBoolean()
    compareFilterRight = p[2].toBoolean()
    filterVisible = p[3].toBoolean()
    annotationVisible = p[4].toBoolean()
    filterPanelWidth = p[5].toFloatOrNull() ?: filterPanelWidth
    annotationPanelWidth = (p[6].toFloatOrNull() ?: annotationPanelWidth).coerceIn(ANNOTATION_PANEL_MIN_WIDTH, ANNOTATION_PANEL_MAX_WIDTH)
    compareSplit = p[7].toFloatOrNull() ?: compareSplit
    // Trailing fields: absent on tokens from before the AI panel became independently toggleable.
    aiPanelVisible = p.getOrNull(8)?.toBooleanStrictOrNull() ?: false
    rightSidebarSplit = (p.getOrNull(9)?.toFloatOrNull() ?: rightSidebarSplit)
        .coerceIn(RIGHT_SIDEBAR_SPLIT_MIN, RIGHT_SIDEBAR_SPLIT_MAX)
}

internal fun FilterPanelUiState.filterPanelToken(): String = tokenFields(
    hlListExpanded.toString(),
    lvlExpanded.toString(),
    seqExpanded.toString(),
    sfExpanded.toString(),
    incPillsExpanded.toString(),
    incMsgPillsExpanded.toString(),
    excMsgPillsExpanded.toString(),
    crashExpanded.toString(),
    crashCategory.name,
)

internal fun FilterPanelUiState.restoreFilterPanelToken(token: String) {
    val p = token.tokenFields()
    if (p.size < 7) return
    hlListExpanded = p[0].toBoolean()
    lvlExpanded = p[1].toBoolean()
    seqExpanded = p[2].toBoolean()
    sfExpanded = p[3].toBoolean()
    incPillsExpanded = p[4].toBoolean()
    incMsgPillsExpanded = p[5].toBoolean()
    excMsgPillsExpanded = p[6].toBoolean()
    crashExpanded = p.getOrNull(7)?.toBooleanStrictOrNull() ?: crashExpanded
    crashCategory = p.getOrNull(8)?.let { runCatching { CrashCategory.valueOf(it) }.getOrNull() } ?: crashCategory
}

internal fun AppState.activeFilterMapToken(): String =
    activeSavedFilterIds.entries.joinToString(",") { tokenFields(it.key, it.value) }

internal fun activeFilterMapFromToken(token: String): Map<String, String> =
    if (token.isBlank()) emptyMap()
    else token.split(",").mapNotNull { item ->
        val p = item.tokenFields()
        if (p.size >= 2) p[0] to p[1] else null
    }.toMap()

internal fun Highlighter.highlighterToken(): String = tokenFields(
    id,
    pattern,
    regex.toString(),
    color.value.toString(),
    on.toString(),
)

internal fun String.highlighterFromToken(): Highlighter? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    Highlighter(
        id = p[0],
        pattern = p[1],
        regex = p[2].toBoolean(),
        color = Color(p[3].toULong()),
        on = p[4].toBoolean(),
    )
}.getOrNull()

private fun SavedFilter.savedFilterToken(): String = tokenFields(
    id,
    name,
    levels.joinToString("") { it.key.toString() },
    activeTags.joinToString(",") { it.b64() },
    kwText,
    kwRegex.toString(),
    mode.name,
    excludeTags.joinToString(",") { it.b64() },
    excludeKw,
    excludeKwRegex.toString(),
    highlighters.joinToString(",") { it.highlighterToken().b64() },
    seqOn.toString(),
    kwInTag,
    kwInTagRegex.toString(),
    pkgPrefixes.joinToString(",") { it.b64() },
    pidTidFilter,
    sequences.joinToString(",") { it.sequenceToken().b64() },
    messageRules.joinToString(",") { it.messageRuleToken().b64() },
    excludePkgPrefixes.joinToString(",") { it.b64() },
    kwHighlightEnabled.toString(),
    kwHighlightColor.value.toString(),
    folderId.orEmpty(),
    favorite.toString(),
)

private fun String.savedFilterFromToken(): SavedFilter? = runCatching {
    val p = tokenFields()
    if (p.size < 18) return@runCatching null
    SavedFilter(
        id = p[0],
        name = p[1],
        levels = p[2].mapNotNull { key -> LogLevel.entries.find { it.key == key } }.toSet()
            .ifEmpty { LogLevel.entries.toSet() },
        activeTags = p[3].encodedSet(),
        kwText = p[4],
        kwRegex = p[5].toBoolean(),
        mode = runCatching { FilterMode.valueOf(p[6]) }.getOrElse { FilterMode.TAGS },
        excludeTags = p[7].encodedSet(),
        excludeKw = p[8],
        excludeKwRegex = p[9].toBoolean(),
        highlighters = p[10].encodedList().mapNotNull { it.highlighterFromToken() },
        seqOn = p[11].toBoolean(),
        kwInTag = p[12],
        kwInTagRegex = p[13].toBoolean(),
        pkgPrefixes = p[14].encodedSet(),
        pidTidFilter = p[15],
        sequences = p[16].encodedList().mapNotNull { it.sequenceFromToken() },
        messageRules = p[17].encodedList().mapNotNull { it.messageRuleFromToken() },
        excludePkgPrefixes = p.getOrNull(18)?.encodedSet() ?: emptySet(),
        kwHighlightEnabled = p.getOrNull(19)?.toBooleanStrictOrNull() ?: true,
        kwHighlightColor = p.getOrNull(20)?.toULongOrNull()?.let(::Color) ?: DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
        folderId = p.getOrNull(21)?.takeIf { it.isNotBlank() },
        favorite = p.getOrNull(22)?.toBooleanStrictOrNull() ?: false,
    )
}.getOrNull()

internal fun SavedFilter.toFilter(): Filter = Filter(
    levels = levels,
    activeTags = activeTags,
    kwText = kwText,
    kwRegex = kwRegex,
    mode = mode,
    excludeTags = excludeTags,
    excludeKw = excludeKw,
    excludeKwRegex = excludeKwRegex,
    highlighters = highlighters,
    messageRules = messageRules,
    kwHighlightEnabled = kwHighlightEnabled,
    kwHighlightColor = kwHighlightColor,
    seqOn = seqOn,
    kwInTag = kwInTag,
    kwInTagRegex = kwInTagRegex,
    pkgPrefixes = pkgPrefixes,
    excludePkgPrefixes = excludePkgPrefixes,
    pidTidFilter = pidTidFilter,
    sequences = sequences,
)

private fun String.encodedList(): List<String> =
    if (isBlank()) emptyList() else split(",").filter { it.isNotBlank() }.map { it.unb64() }

private fun String.encodedSet(): Set<String> = encodedList().toSet()

private fun LogEntry.toAnnToken(): String =
    listOf(id.toString(), ts, level.key.toString(), tag, msg, pid.toString(), tid.toString())
        .joinToString("|") { it.b64() }

private fun String.toLogEntryFromAnnToken(): LogEntry? = runCatching {
    val p = split("|").map { it.unb64() }
    if (p.size < 5) return@runCatching null
    LogEntry(
        id = p[0].toIntOrNull() ?: 0,
        ts = p[1],
        level = LogLevel.from(p[2].firstOrNull() ?: 'I'),
        tag = p[3],
        msg = p[4],
        pid = p.getOrNull(5)?.toIntOrNull() ?: 0,
        tid = p.getOrNull(6)?.toIntOrNull() ?: 0,
    )
}.getOrNull()

private fun AnnBlock.annBlockToken(): String = when (this) {
    is AnnBlock.Note -> tokenFields("N", id, text)
    is AnnBlock.LogRef -> tokenFields(
        "R", id,
        logIds.joinToString(","),
        caption,
        sourceTabId.orEmpty(),
        sourceFilename.orEmpty(),
        sourceEntries?.joinToString(";") { it.toAnnToken() }.orEmpty(),
    )
}

private fun String.annBlockFromToken(): AnnBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    when (p[0]) {
        "N" -> AnnBlock.Note(p[1], p[2])
        "R" -> AnnBlock.LogRef(
            id = p[1],
            logIds = p[2].split(",").mapNotNull { it.toIntOrNull() },
            caption = p.getOrElse(3) { "" },
            sourceTabId = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            sourceFilename = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            sourceEntries = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() }
                ?.split(";")?.mapNotNull { it.toLogEntryFromAnnToken() },
        )

        else -> null
    }
}.getOrNull()

internal fun Annotations.annotationsToken(sourcePath: String? = null): String = tokenFields(
    prefix,
    suffix,
    blocks.joinToString(",") { it.annBlockToken().b64() },
    issueDescription,
    sourcePath.orEmpty(),
    // Appended — indices 5/6. Old readers (getOrNull(0..4)) never see these; never reorder.
    appVersion,
    decisiveTags.joinToString(","),
)

internal fun String.annotationsFromToken(): Annotations? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    Annotations(
        prefix = p[0],
        suffix = p[1],
        blocks = p[2].encodedList().mapNotNull { it.annBlockFromToken() },
        issueDescription = p.getOrNull(3) ?: "",
        // Fields 5/6 (index 4 is sourcePath, read separately by readSourceFingerprint —
        // AppState.kt — which must stay unaffected by this addition). Absent on legacy
        // (5-field) tokens -> default to empty, exactly like a note that never set them.
        appVersion = p.getOrNull(5) ?: "",
        decisiveTags = p.getOrNull(6)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    )
}.getOrNull()

private fun ManualCollapseBlock.manualBlockToken(): String = tokenFields(
    id,
    anchorId.toString(),
    direction.name,
    color.value.toString(),
    enabled.toString(),
    endId?.toString().orEmpty(),
)

private fun String.manualBlockFromToken(): ManualCollapseBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    ManualCollapseBlock(
        id = p[0],
        anchorId = p[1].toIntOrNull() ?: return@runCatching null,
        direction = runCatching { ManualCollapseDirection.valueOf(p[2]) }.getOrElse { ManualCollapseDirection.TO_END },
        color = Color(p[3].toULong()),
        enabled = p[4].toBoolean(),
        endId = p.getOrNull(5)?.toIntOrNull(),
    )
}.getOrNull()

// Field-for-field mirror of exactly what tabToken() below serializes. Used to key the
// debounced-autosave LaunchedEffect in App.kt (PERF-4): `selected`/`analysis`/`tailing`/`logData`/
// `rmap`/`largeFileMode` are NOT here (and never end up in tabToken() either), so a row click —
// which only flips `selected` — no longer identity-changes the effect's key and no longer triggers
// a serialize+write. Keep this in sync if tabToken()'s field list changes.
internal fun LogTab.persistedSnapshot(): List<Any?> = listOf(
    id, filename, sourcePath, filter, annotations, showAnnMd, showUnfiltered, expanded, manualBlocks, archiveCandidate,
)

private fun ZipLogCandidate.archiveCandidateToken(): String = tokenFields(
    entryPath,
    displayName,
    sizeBytes.toString(),
    kind.name,
)

private fun String.archiveCandidateFromToken(): ZipLogCandidate? = runCatching {
    val p = tokenFields()
    if (p.size < 4) return@runCatching null
    ZipLogCandidate(
        entryPath = p[0],
        displayName = p[1],
        sizeBytes = p[2].toLongOrNull() ?: return@runCatching null,
        kind = runCatching { ZipLogCandidateKind.valueOf(p[3]) }.getOrElse { ZipLogCandidateKind.LOGCAT },
    )
}.getOrNull()

internal fun LogTab.tabToken(): String {
    val filter = SavedFilter(
        "tab", "tab", filter.levels, filter.activeTags, filter.kwText, filter.kwRegex,
        filter.mode, filter.excludeTags, filter.excludeKw, filter.excludeKwRegex, filter.highlighters, filter.seqOn,
        filter.kwInTag, filter.kwInTagRegex, filter.pkgPrefixes, filter.pidTidFilter, filter.sequences,
        filter.messageRules, filter.excludePkgPrefixes, filter.kwHighlightEnabled, filter.kwHighlightColor,
    )
    return tokenFields(
        id,
        filename,
        sourcePath.orEmpty(),
        filter.savedFilterToken(),
        annotations.annotationsToken(),
        showAnnMd.toString(),
        showUnfiltered.toString(),
        expanded.joinToString(",") { it.b64() },
        manualBlocks.joinToString(",") { it.manualBlockToken().b64() },
        // Trailing field (position 9, PERF-3b): lets restore rebuild an archive tab's
        // RestoredTabSource.ArchiveSource straight from the persisted candidate instead of calling
        // listArchiveLogCandidates() (which opens and scans the whole archive) synchronously during
        // AppState init. Empty for non-archive tabs and for tokens written before this field
        // existed — those legacy tokens resolve current metadata on the background restore path.
        archiveCandidate?.archiveCandidateToken().orEmpty(),
    )
}

// Metadata-only restore: log content is parsed afterwards on ioScope (scheduleRestoredTabLoad),
// so this must stay cheap — it runs synchronously during AppState init. Source-exists checks
// stay here so tabs whose backing file/archive vanished are dropped before they ever appear.
internal class RestoredTabShell(val tab: LogTab, val source: RestoredTabSource)

internal sealed interface RestoredTabLoadResult {
    data class Loaded(
        val logData: List<LogEntry>,
        val archiveCandidate: ZipLogCandidate?,
        val largeFileMode: Boolean,
    ) : RestoredTabLoadResult

    data class MissingArchiveEntry(val archiveFile: File, val entryPath: String) : RestoredTabLoadResult
}

internal sealed class RestoredTabSource {
    abstract val largeFileMode: Boolean

    data class FileSource(val file: File) : RestoredTabSource() {
        override val largeFileMode: Boolean = file.length() >= LARGE_FILE_MODE_BYTES
    }

    data class ArchiveSource(
        val archiveFile: File,
        val entryPath: String,
        val persistedCandidate: ZipLogCandidate?,
    ) : RestoredTabSource() {
        override val largeFileMode: Boolean = persistedCandidate?.sizeBytes?.let { it >= LARGE_FILE_MODE_BYTES } ?: false
    }
}

internal fun String.tabShellFromToken(): RestoredTabShell? = runCatching {
    val p = tokenFields()
    if (p.size < 9) return@runCatching null
    val sourcePath = p[2].takeIf { it.isNotBlank() }
    val persistedCandidate = p.getOrNull(9)?.takeIf { it.isNotBlank() }?.archiveCandidateFromToken()
    val source = sourcePath?.restoredTabSource(persistedCandidate) ?: return@runCatching null
    RestoredTabShell(
        LogTab(
            id = p[0],
            filename = p[1],
            logData = emptyList(),
            rmap = emptyMap(),
            filter = p[3].savedFilterFromToken()?.toFilter() ?: Filter(),
            showUnfiltered = p[6].toBoolean(),
            expanded = p[7].encodedSet(),
            annotations = p[4].annotationsFromToken() ?: Annotations(),
            showAnnMd = p[5].toBoolean(),
            manualBlocks = p[8].encodedList().mapNotNull { it.manualBlockFromToken() },
            sourcePath = sourcePath,
            largeFileMode = source.largeFileMode,
            archiveCandidate = (source as? RestoredTabSource.ArchiveSource)?.persistedCandidate,
        ),
        source,
    )
}.getOrNull()

// [persistedCandidate] comes from the tab token's trailing field (PERF-3b) — when present for an
// archive-backed sourcePath, it supplies the initial shell metadata without opening the archive.
// Legacy archive tokens have no candidate, so their shell starts with unknown size metadata and
// resolves it together with content on the queued IO restore path. Neither form scans an archive
// synchronously during AppState construction.
private fun String.restoredTabSource(persistedCandidate: ZipLogCandidate? = null): RestoredTabSource? {
    val bangIndex = indexOf('!')
    if (bangIndex > 0) {
        val archiveFile = File(substring(0, bangIndex)).takeIf { it.exists() } ?: return null
        val entryPath = substring(bangIndex + 1).takeIf { it.isNotBlank() } ?: return null
        val matchingPersistedCandidate = persistedCandidate?.takeIf { it.entryPath == entryPath }
        return RestoredTabSource.ArchiveSource(archiveFile, entryPath, matchingPersistedCandidate)
    }
    return File(this).takeIf { it.exists() }?.let { RestoredTabSource.FileSource(it) }
}

internal fun File.totalFileSize(): Long =
    if (!exists()) 0L
    else if (isFile) length()
    else listFiles().orEmpty().sumOf { it.totalFileSize() }

internal fun String.b64(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

internal fun String.unb64(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
