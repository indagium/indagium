package com.indagium.source

import com.indagium.utils.writeFileAtomically
import java.io.File
import java.util.Base64

// SOURCE_INDEX_MAGIC_CURRENT is what save() writes; SOURCE_INDEX_MAGIC_ACCEPTED (built from it,
// plus the pre-rename openLog2 marker already sitting in every shipped-1.7.9 user's index file) is
// what load() accepts. Defining the accepted set in terms of the written constant makes "the write
// value is a member of the accepted set" true by construction — see AppState's autosave magic
// constants for the same shape, duplicated here since this store is independent of AppState.
private const val SOURCE_INDEX_MAGIC_CURRENT = "indagium-source-index-v1"
private const val SOURCE_INDEX_MAGIC_LEGACY_OPENLOG2 = "openLog2-source-index-v1"
private val SOURCE_INDEX_MAGIC_ACCEPTED = setOf(SOURCE_INDEX_MAGIC_CURRENT, SOURCE_INDEX_MAGIC_LEGACY_OPENLOG2)

// Base64-url (no padding) round-trip for any field that could otherwise contain a tab or newline
// (file paths, matcher regex patterns, method names) — same scheme as AppState's autosave format
// (String.b64()/unb64()), duplicated here rather than shared since those extensions are file-private.
private fun String.b64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)

// "~" is never a valid non-empty base64-url output (the shortest non-empty encoding is 2 chars),
// so it's a safe empty-string sentinel — mirrors AppState's fieldToken()/fieldValue() pair.
private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun LogCallSite.toLine(): String = listOf(
    filePath.fieldToken(),
    tag.orEmpty().fieldToken(),
    methodName.fieldToken(),
    methodStartLine.toString(),
    methodEndLine.toString(),
    callLine.toString(),
    matcher.fieldToken(),
    literalLen.toString(),
    configurationDependent.toString(),
    owningType.orEmpty().fieldToken(),
    methodSignature.fieldToken(),
    declaredReturnType.orEmpty().fieldToken(),
    directCalls.fieldToken(),
        loggedValueNames.fieldToken(),
        id.fieldToken(),
        methodId.orEmpty().fieldToken(),
        sourceOffset.toString(),
        sourceSet.name,
).joinToString("\t")

// Direct-call fields use base64-url too, so ':' and ';' are safe structural separators.  Keeping
// this nested record inside a site line preserves the store's useful "one malformed line only
// loses one record" recovery property.
private fun List<SourceDirectCall>.fieldToken(): String = if (isEmpty()) "~" else joinToString(";") { call ->
    listOf(
        call.targetFilePath.fieldToken(),
        call.targetOwnerType.fieldToken(),
        call.targetMethodName.fieldToken(),
        call.targetMethodSignature.fieldToken(),
        call.targetDeclaredReturnType.orEmpty().fieldToken(),
        call.callLine.toString(),
        call.resultVariable.orEmpty().fieldToken(),
        call.sourceOwnerType.orEmpty().fieldToken(),
        call.isCallback.toString(),
        call.callSiteId.orEmpty().fieldToken(),
        call.callerMethodId.orEmpty().fieldToken(),
        call.targetMethodId.orEmpty().fieldToken(),
        call.callOffset.toString(),
        call.receiverExpression.orEmpty().fieldToken(),
        call.receiverVariable.orEmpty().fieldToken(),
        call.receiverDeclaredType.orEmpty().fieldToken(),
        call.receiverRole.name,
        call.invocationKind.name,
        call.resolutionConfidence.toString(),
    ).joinToString(":")
}

private fun IndexedSourceMethod.toLine(): String = listOf(
    id.fieldToken(), filePath.fieldToken(), ownerType.orEmpty().fieldToken(), name.fieldToken(),
    signature.fieldToken(), declaredReturnType.orEmpty().fieldToken(), startOffset.toString(), endOffsetExclusive.toString(),
    sourceSet.name,
).joinToString("\t")

private fun IndexedSourceCall.toLine(): String = listOf(
    id.fieldToken(), callerMethodId.fieldToken(),
    candidateCalleeMethodIds.joinToString(";") { it.fieldToken() }.ifEmpty { "~" },
    receiverExpression.orEmpty().fieldToken(), receiverVariable.orEmpty().fieldToken(),
    receiverDeclaredType.orEmpty().fieldToken(), receiverRole.name, callOffset.toString(), callLine.toString(),
    resultVariable.orEmpty().fieldToken(), invocationKind.name, resolutionConfidence.toString(),
).joinToString("\t")

private fun IndexedSourceOperation.toLine(): String = listOf(
    id.fieldToken(), methodId.fieldToken(), kind.name, sourceOrder.toString(), sourceLine.toString(),
    callSiteId.orEmpty().fieldToken(), logSiteId.orEmpty().fieldToken(),
    successorIds.joinToString(";") { it.fieldToken() }.ifEmpty { "~" },
).joinToString("\t")

private fun Set<String>.fieldToken(): String =
    if (isEmpty()) "~" else joinToString(";") { it.fieldToken() }

private fun String.loggedValueNamesFieldValue(): Set<String> =
    if (this == "~" || isBlank()) emptySet() else split(';').map { it.fieldValue() }.toSet()

private fun String.directCallsFieldValue(): List<SourceDirectCall> {
    if (this == "~" || isBlank()) return emptyList()
    return split(';').mapNotNull { token ->
        val fields = token.split(':')
        if (fields.size < 6) return@mapNotNull null
        runCatching {
            SourceDirectCall(
                targetFilePath = fields[0].fieldValue(),
                targetOwnerType = fields[1].fieldValue(),
                targetMethodName = fields[2].fieldValue(),
                targetMethodSignature = fields[3].fieldValue(),
                targetDeclaredReturnType = fields[4].fieldValue().takeIf { it.isNotBlank() },
                callLine = fields[5].toInt(),
                resultVariable = fields.getOrNull(6)?.fieldValue()?.takeIf { it.isNotBlank() },
                sourceOwnerType = fields.getOrNull(7)?.fieldValue()?.takeIf { it.isNotBlank() },
                isCallback = fields.getOrNull(8)?.toBooleanStrictOrNull() ?: false,
                callSiteId = fields.getOrNull(9)?.fieldValue()?.takeIf { it.isNotBlank() },
                callerMethodId = fields.getOrNull(10)?.fieldValue()?.takeIf { it.isNotBlank() },
                targetMethodId = fields.getOrNull(11)?.fieldValue()?.takeIf { it.isNotBlank() },
                callOffset = fields.getOrNull(12)?.toIntOrNull() ?: 0,
                receiverExpression = fields.getOrNull(13)?.fieldValue()?.takeIf { it.isNotBlank() },
                receiverVariable = fields.getOrNull(14)?.fieldValue()?.takeIf { it.isNotBlank() },
                receiverDeclaredType = fields.getOrNull(15)?.fieldValue()?.takeIf { it.isNotBlank() },
                receiverRole = fields.getOrNull(16)?.let { runCatching { ReceiverRole.valueOf(it) }.getOrNull() } ?: ReceiverRole.UNKNOWN,
                invocationKind = fields.getOrNull(17)?.let { runCatching { InvocationKind.valueOf(it) }.getOrNull() } ?: InvocationKind.UNKNOWN,
                resolutionConfidence = fields.getOrNull(18)?.toDoubleOrNull() ?: 1.0,
            )
        }.getOrNull()
    }
}

private fun parseSiteLine(rest: String): LogCallSite? {
    val parts = rest.split("\t")
    if (parts.size < 13) return null
    return LogCallSite(
        filePath = parts[0].fieldValue(),
        tag = parts[1].fieldValue().takeIf { it.isNotBlank() },
        methodName = parts[2].fieldValue(),
        methodStartLine = parts[3].toInt(),
        methodEndLine = parts[4].toInt(),
        callLine = parts[5].toInt(),
        matcher = parts[6].fieldValue(),
        literalLen = parts[7].toInt(),
        configurationDependent = parts[8].toBooleanStrict(),
        owningType = parts[9].fieldValue().takeIf { it.isNotBlank() },
        methodSignature = parts[10].fieldValue(),
        declaredReturnType = parts[11].fieldValue().takeIf { it.isNotBlank() },
        directCalls = parts[12].directCallsFieldValue(),
        loggedValueNames = parts.getOrNull(13)?.loggedValueNamesFieldValue().orEmpty(),
        id = parts.getOrNull(14)?.fieldValue().orEmpty(),
        methodId = parts.getOrNull(15)?.fieldValue()?.takeIf { it.isNotBlank() },
        sourceOffset = parts.getOrNull(16)?.toIntOrNull() ?: 0,
        sourceSet = parts.getOrNull(17)?.let { runCatching { SourceSetKind.valueOf(it) }.getOrNull() }
            ?: SourceSetKind.PRODUCTION,
    )
}

private fun parseMethodLine(rest: String): IndexedSourceMethod? {
    val parts = rest.split("\t")
    if (parts.size < 8) return null
    return runCatching {
        IndexedSourceMethod(
            id = parts[0].fieldValue(), filePath = parts[1].fieldValue(),
            ownerType = parts[2].fieldValue().takeIf { it.isNotBlank() }, name = parts[3].fieldValue(),
            signature = parts[4].fieldValue(), declaredReturnType = parts[5].fieldValue().takeIf { it.isNotBlank() },
            startOffset = parts[6].toInt(), endOffsetExclusive = parts[7].toInt(),
            sourceSet = parts.getOrNull(8)?.let { runCatching { SourceSetKind.valueOf(it) }.getOrNull() }
                ?: SourceSetKind.PRODUCTION,
        )
    }.getOrNull()
}

private fun parseCallLine(rest: String): IndexedSourceCall? {
    val parts = rest.split("\t")
    if (parts.size < 12) return null
    return runCatching {
        IndexedSourceCall(
            id = parts[0].fieldValue(), callerMethodId = parts[1].fieldValue(),
            candidateCalleeMethodIds = if (parts[2] == "~" || parts[2].isBlank()) emptyList() else parts[2].split(';').map { it.fieldValue() },
            receiverExpression = parts[3].fieldValue().takeIf { it.isNotBlank() },
            receiverVariable = parts[4].fieldValue().takeIf { it.isNotBlank() },
            receiverDeclaredType = parts[5].fieldValue().takeIf { it.isNotBlank() },
            receiverRole = runCatching { ReceiverRole.valueOf(parts[6]) }.getOrDefault(ReceiverRole.UNKNOWN),
            callOffset = parts[7].toInt(), callLine = parts[8].toInt(),
            resultVariable = parts[9].fieldValue().takeIf { it.isNotBlank() },
            invocationKind = runCatching { InvocationKind.valueOf(parts[10]) }.getOrDefault(InvocationKind.UNKNOWN),
            resolutionConfidence = parts[11].toDouble(),
        )
    }.getOrNull()
}

private fun parseOperationLine(rest: String): IndexedSourceOperation? {
    val parts = rest.split("\t")
    if (parts.size < 8) return null
    return runCatching {
        IndexedSourceOperation(
            id = parts[0].fieldValue(),
            methodId = parts[1].fieldValue(),
            kind = SourceOperationKind.valueOf(parts[2]),
            sourceOrder = parts[3].toInt(),
            sourceLine = parts[4].toInt(),
            callSiteId = parts[5].fieldValue().takeIf { it.isNotBlank() },
            logSiteId = parts[6].fieldValue().takeIf { it.isNotBlank() },
            successorIds = if (parts[7] == "~" || parts[7].isBlank()) emptyList() else parts[7].split(';').map { it.fieldValue() },
        )
    }.getOrNull()
}

private fun parseMetaLine(rest: String): Pair<String, FileMeta>? {
    val parts = rest.split("\t")
    if (parts.size < 3) return null
    return parts[0].fieldValue() to FileMeta(
        mtime = parts[1].toLong(),
        size = parts[2].toLong(),
        sha256 = parts.getOrNull(3)?.fieldValue()?.takeIf { it.isNotBlank() },
    )
}

// Accumulates the sections while scanning line-by-line — every line is parsed independently under
// its own runCatching (see parseSourceIndexLines) so one truncated/garbled line never takes the
// rest of the file down with it.
private class ParseState {
    var version: Int? = null
    var builtAt: Long = 0L
    val roots = mutableListOf<String>()
    val fileMeta = mutableMapOf<String, FileMeta>()
    val sites = mutableListOf<LogCallSite>()
    val methods = mutableListOf<IndexedSourceMethod>()
    val calls = mutableListOf<IndexedSourceCall>()
    val operations = mutableListOf<IndexedSourceOperation>()
    var revision: String = ""
    val rootBuiltAt = mutableMapOf<String, Long>()
    val rootConfigFingerprints = mutableMapOf<String, String>()
}

private fun parseRootBuiltAtLine(rest: String): Pair<String, Long>? {
    val parts = rest.split("\t")
    if (parts.size < 2) return null
    return parts[0].fieldValue() to parts[1].toLong()
}

private fun ParseState.applyLine(line: String) {
    val field = line.substringBefore('\t')
    val rest = line.substringAfter('\t', "")
    when (field) {
        "version" -> version = rest.toInt()
        "builtAt" -> builtAt = rest.toLong()
        "revision" -> revision = rest.fieldValue()
        "root" -> roots += rest.fieldValue()
        "meta" -> parseMetaLine(rest)?.let { (path, meta) -> fileMeta[path] = meta }
        "site" -> parseSiteLine(rest)?.let { sites += it }
        "method" -> parseMethodLine(rest)?.let { methods += it }
        "call" -> parseCallLine(rest)?.let { calls += it }
        "operation" -> parseOperationLine(rest)?.let { operations += it }
        "rootBuiltAt" -> parseRootBuiltAtLine(rest)?.let { (root, at) -> rootBuiltAt[root] = at }
        "rootConfig" -> {
            val parts = rest.split("\t")
            if (parts.size >= 2) rootConfigFingerprints[parts[0].fieldValue()] = parts[1].fieldValue()
        }
    }
}

private fun parseSourceIndexLines(lines: List<String>): SourceIndex? {
    if (lines.isEmpty() || lines.first() !in SOURCE_INDEX_MAGIC_ACCEPTED) return null
    val state = ParseState()
    lines.drop(1).forEach { line -> runCatching { state.applyLine(line) } }
    val version = state.version ?: return null
    if (version != SOURCE_INDEX_VERSION) return null
    return SourceIndex(
        version = version,
        roots = state.roots,
        sites = state.sites,
        fileMeta = state.fileMeta,
        builtAt = state.builtAt,
        rootBuiltAt = state.rootBuiltAt,
        rootConfigFingerprints = state.rootConfigFingerprints,
        methods = state.methods,
        calls = state.calls,
        operations = state.operations,
        revision = state.revision,
    )
}

/** Disk persistence for a [SourceIndex] — a line-oriented, tab-separated text format mirroring the
 *  style of the app's autosave format: a magic header line, then typed `record\tfield...` lines
 *  (`root`, `meta`, `site`) making up the roots/fileMeta/sites sections. The header line is
 *  written as [SOURCE_INDEX_MAGIC_CURRENT] but a load also accepts the pre-rename
 *  `openLog2-source-index-v1` marker ([SOURCE_INDEX_MAGIC_ACCEPTED]) so an index built by a
 *  shipped 1.7.9 build still loads instead of forcing a full re-index. Free-text fields that could
 *  contain a tab or newline (file paths, matcher patterns, method names) are base64-url-encoded
 *  via [String.fieldToken] so they can never corrupt the line structure. */
object SourceIndexStore {
    fun save(index: SourceIndex, file: File) {
        writeFileAtomically(file) { writer ->
            writer.appendLine(SOURCE_INDEX_MAGIC_CURRENT)
            writer.appendLine("version\t${index.version}")
            writer.appendLine("builtAt\t${index.builtAt}")
            writer.appendLine("revision\t${index.revision.fieldToken()}")
            index.roots.forEach { root -> writer.appendLine("root\t${root.fieldToken()}") }
            index.rootBuiltAt.forEach { (root, at) -> writer.appendLine("rootBuiltAt\t${root.fieldToken()}\t$at") }
            index.rootConfigFingerprints.forEach { (root, fingerprint) ->
                writer.appendLine("rootConfig\t${root.fieldToken()}\t${fingerprint.fieldToken()}")
            }
            index.fileMeta.forEach { (path, meta) ->
                writer.appendLine("meta\t${path.fieldToken()}\t${meta.mtime}\t${meta.size}\t${meta.sha256.orEmpty().fieldToken()}")
            }
            index.methods.forEach { method -> writer.appendLine("method\t${method.toLine()}") }
            index.calls.forEach { call -> writer.appendLine("call\t${call.toLine()}") }
            index.operations.forEach { operation -> writer.appendLine("operation\t${operation.toLine()}") }
            index.sites.forEach { site -> writer.appendLine("site\t${site.toLine()}") }
        }
    }

    // Missing file, empty file, wrong/missing magic header, or a stored version that doesn't match
    // SOURCE_INDEX_VERSION all mean "no usable index" to the caller — every one of those collapses
    // to null rather than a distinct error, since the only correct response in every case is the
    // same: rebuild via SourceIndexer.build. Any hard read/parse failure is caught here too.
    fun load(file: File): SourceIndex? {
        if (!file.exists()) return null
        return runCatching { parseSourceIndexLines(file.readLines()) }.getOrNull()
    }
}
