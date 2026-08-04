package com.indagium.source

import java.security.MessageDigest
import java.util.Base64

/** A lightweight, source-preserving declaration scanner for Kotlin and Java files.
 *
 * It deliberately does not try to understand either language semantically.  It shares the
 * source indexer's important safety property instead: comments and string/char literals are
 * opaque, so text that merely looks like a declaration cannot become browseable structure.
 */
data class SourceDeclaration(
    val id: String,
    val parentId: String?,
    val kind: String,
    val name: String,
    val signature: String,
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val hasChildren: Boolean,
)

data class ParsedSourceStructure(val declarations: List<SourceDeclaration>) {
    fun directChildren(parentId: String?): List<SourceDeclaration> = declarations.filter { it.parentId == parentId }
}

/** A live, authorized source file snapshot used by the read-only navigation tools. */
data class SourceFileSnapshot(
    val canonicalPath: String,
    val text: String,
    val revision: String,
    val lineCount: Int,
    val isJavaFile: Boolean,
)

object SourceStructureParser {
    fun parse(text: String, isJavaFile: Boolean): ParsedSourceStructure {
        if (text.isEmpty()) return ParsedSourceStructure(emptyList())
        val scanner = StructureScanner(text, isJavaFile)
        return ParsedSourceStructure(scanner.parse())
    }

    fun revision(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private class StructureMask(text: String) {
    val isCode = BooleanArray(text.length)

    init {
        var i = 0
        while (i < text.length) {
            i = when {
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '/' -> skipToLineEnd(text, i)
                text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> skipBlock(text, i)
                text[i] == '"' && i + 2 < text.length && text.startsWith("\"\"\"", i) -> skipTripleQuote(text, i)
                text[i] == '"' -> skipQuote(text, i, '"')
                text[i] == '\'' -> skipQuote(text, i, '\'')
                else -> {
                    isCode[i] = true
                    i + 1
                }
            }
        }
    }

    private fun skipToLineEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun skipBlock(text: String, from: Int): Int {
        var i = from + 2
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
        return minOf(i + 2, text.length)
    }

    private fun skipTripleQuote(text: String, from: Int): Int {
        var i = from + 3
        while (i + 2 < text.length && !text.startsWith("\"\"\"", i)) i++
        return minOf(i + 3, text.length)
    }

    private fun skipQuote(text: String, from: Int, quote: Char): Int {
        var i = from + 1
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\' && i + 1 < text.length) 2 else 1
        return minOf(i + 1, text.length)
    }
}

private class StructureLineIndex(text: String) {
    private val starts = buildList {
        add(0)
        text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }.toIntArray()

    fun lineOf(offset: Int): Int {
        var low = 0
        var high = starts.lastIndex
        val target = offset.coerceIn(0, starts.last())
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= target) low = mid else high = mid - 1
        }
        return low + 1
    }
}

private class StructureScanner(private val text: String, private val isJavaFile: Boolean) {
    private val mask = StructureMask(text)
    private val lines = StructureLineIndex(text)
    private val declarations = mutableListOf<SourceDeclaration>()

    fun parse(): List<SourceDeclaration> {
        scanScope(0, text.length, parentId = null, ownerTypeName = null)
        val parentsWithChildren = declarations.mapNotNull { it.parentId }.toSet()
        return declarations.map { declaration -> declaration.copy(hasChildren = declaration.id in parentsWithChildren) }
    }

    private fun scanScope(from: Int, until: Int, parentId: String?, ownerTypeName: String?) {
        var pos = from
        while (pos < until) {
            pos = skipTrivia(pos, until)
            if (pos >= until) return
            val terminal = findStatementTerminal(pos, until)
            if (terminal == null) {
                declarationForStatement(text.substring(pos, until), pos, until, parentId, ownerTypeName)?.let(declarations::add)
                return
            }
            val (terminalIndex, terminalChar) = terminal
            val header = text.substring(pos, terminalIndex)
            when (terminalChar) {
                '{' -> {
                    val close = findMatchingBrace(terminalIndex, until)
                    val declaration = declarationForBlock(header, pos, close, parentId, ownerTypeName)
                    if (declaration != null) {
                        declarations += declaration
                        if (declaration.kind in TYPE_KINDS) {
                            scanScope(terminalIndex + 1, close, declaration.id, declaration.name)
                        }
                    }
                    pos = close + 1
                }
                ';' -> {
                    declarationForStatement(header, pos, terminalIndex + 1, parentId, ownerTypeName)?.let(declarations::add)
                    pos = terminalIndex + 1
                }
                '\n' -> {
                    declarationForStatement(header, pos, terminalIndex, parentId, ownerTypeName)?.let(declarations::add)
                    pos = terminalIndex + 1
                }
                '}' -> {
                    declarationForStatement(header, pos, terminalIndex, parentId, ownerTypeName)?.let(declarations::add)
                    return
                }
            }
        }
    }

    private fun skipTrivia(from: Int, until: Int): Int {
        var i = from
        while (i < until && (!mask.isCode[i] || text[i].isWhitespace())) i++
        return i
    }

    /** Finds the next direct-scope statement terminator, ignoring delimiters inside parameter lists. */
    @Suppress("CyclomaticComplexMethod") // Each delimiter updates independent lexical nesting state.
    private fun findStatementTerminal(from: Int, until: Int): Pair<Int, Char>? {
        var parenDepth = 0
        var bracketDepth = 0
        var i = from
        while (i < until) {
            if (mask.isCode[i]) {
                when (text[i]) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '{' -> if (parenDepth == 0 && bracketDepth == 0) return i to '{'
                    ';' -> if (parenDepth == 0 && bracketDepth == 0) return i to ';'
                    '\n' -> if (parenDepth == 0 && bracketDepth == 0) return i to '\n'
                    '}' -> if (parenDepth == 0 && bracketDepth == 0) return i to '}'
                }
            }
            i++
        }
        return null
    }

    private fun findMatchingBrace(open: Int, until: Int): Int {
        var depth = 1
        var i = open + 1
        while (i < until) {
            if (mask.isCode[i]) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
            i++
        }
        return until - 1
    }

    @Suppress("ReturnCount") // Each declaration form maps directly to its concise source range.
    private fun declarationForBlock(
        header: String,
        start: Int,
        closeBrace: Int,
        parentId: String?,
        ownerTypeName: String?,
    ): SourceDeclaration? {
        val normalized = normalizeHeader(header, start)
        val type = TYPE_RE.find(normalized)
        if (type != null) {
            val rawKind = type.groupValues[1].ifBlank { type.groupValues[2] }
            return declaration(
                kind = typeKind(rawKind), name = type.groupValues[3], signature = normalized,
                start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = true,
            )
        }
        val kotlinFunction = KOTLIN_FUNCTION_RE.find(normalized)
        if (kotlinFunction != null) return declaration(
            kind = "function", name = kotlinFunction.groupValues[1], signature = normalized,
            start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = false,
        )
        if (!isJavaFile && KOTLIN_CONSTRUCTOR_RE.containsMatchIn(normalized)) return declaration(
            kind = "constructor", name = ownerTypeName ?: "constructor", signature = normalized,
            start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = false,
        )
        if (!isJavaFile && KOTLIN_INIT_RE.containsMatchIn(normalized)) return declaration(
            kind = "initializer", name = "init", signature = normalized,
            start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = false,
        )
        val javaCall = javaCallable(normalized)
        if (isJavaFile && javaCall != null) return declaration(
            kind = if (javaCall == ownerTypeName) "constructor" else "method", name = javaCall, signature = normalized,
            start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = false,
        )
        val property = KOTLIN_PROPERTY_RE.find(normalized)
        if (!isJavaFile && property != null) return declaration(
            kind = "property", name = property.groupValues[1], signature = normalized,
            start = start, endExclusive = closeBrace + 1, parentId = parentId, hasChildren = false,
        )
        return null
    }

    @Suppress("ReturnCount") // Each bodyless declaration form maps directly to its concise source range.
    private fun declarationForStatement(
        header: String,
        start: Int,
        endExclusive: Int,
        parentId: String?,
        ownerTypeName: String?,
    ): SourceDeclaration? {
        val normalized = normalizeHeader(header, start)
        if (normalized.isBlank() || normalized.startsWith("package ") || normalized.startsWith("import ")) return null
        if (!isJavaFile && KOTLIN_CONSTRUCTOR_RE.containsMatchIn(normalized)) return declaration(
            kind = "constructor", name = ownerTypeName ?: "constructor", signature = normalized,
            start = start, endExclusive = endExclusive, parentId = parentId, hasChildren = false,
        )
        val kotlinFunction = KOTLIN_FUNCTION_RE.find(normalized)
        if (kotlinFunction != null) return declaration(
            kind = "function", name = kotlinFunction.groupValues[1], signature = normalized,
            start = start, endExclusive = endExclusive, parentId = parentId, hasChildren = false,
        )
        val property = KOTLIN_PROPERTY_RE.find(normalized)
        if (!isJavaFile && property != null) return declaration(
            kind = "property", name = property.groupValues[1], signature = normalized,
            start = start, endExclusive = endExclusive, parentId = parentId, hasChildren = false,
        )
        if (isJavaFile) {
            val call = javaCallable(normalized)
            if (call != null) return declaration(
                kind = if (call == ownerTypeName) "constructor" else "method", name = call, signature = normalized,
                start = start, endExclusive = endExclusive, parentId = parentId, hasChildren = false,
            )
            JAVA_FIELD_RE.find(normalized)?.let { field ->
                return declaration(
                    kind = "field", name = field.groupValues[1], signature = normalized,
                    start = start, endExclusive = endExclusive, parentId = parentId, hasChildren = false,
                )
            }
        }
        return null
    }

    private fun declaration(
        kind: String,
        name: String,
        signature: String,
        start: Int,
        endExclusive: Int,
        parentId: String?,
        hasChildren: Boolean,
    ): SourceDeclaration {
        val startLine = lines.lineOf(start)
        val endLine = lines.lineOf((endExclusive - 1).coerceAtLeast(start))
        val raw = listOf(kind, name, startLine.toString(), endLine.toString(), parentId.orEmpty()).joinToString("\u0000")
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return SourceDeclaration(id, parentId, kind, name, signature, startLine, endLine, start, endExclusive, hasChildren)
    }

    private fun normalizeHeader(header: String, startOffset: Int): String = buildString(header.length) {
        header.forEachIndexed { index, char -> append(if (mask.isCode[startOffset + index]) char else ' ') }
    }.replace(Regex("\\s+"), " ").trim()

    private fun javaCallable(header: String): String? {
        if (header.contains("->")) return null
        val match = JAVA_CALLABLE_RE.find(header) ?: return null
        return match.groupValues[1].takeUnless { it in JAVA_CONTROL_WORDS }
    }

    private fun typeKind(rawKind: String): String = when (rawKind) {
        "@interface" -> "annotation"
        else -> rawKind.replace(Regex("\\s+"), "_")
    }
}

private val TYPE_KINDS = setOf("class", "interface", "object", "enum", "enum_class", "annotation_class", "value_class", "record", "annotation")
private val TYPE_RE = Regex("""(?:\b(enum\s+class|annotation\s+class|value\s+class|class|interface|object|enum|record)|(@interface))\s+([A-Za-z_]\w*)""")
private val KOTLIN_FUNCTION_RE = Regex("""\bfun\s*(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(""")
private val KOTLIN_CONSTRUCTOR_RE = Regex("""\bconstructor\s*\(""")
private val KOTLIN_INIT_RE = Regex("""(?:^|\W)init\s*$""")
private val KOTLIN_PROPERTY_RE = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)""")
private val JAVA_CALLABLE_RE = Regex("""([A-Za-z_]\w*)\s*\([^()]*\)\s*(?:throws\s+[\w.,\s<>\[\]]+)?$""")
private val JAVA_FIELD_RE = Regex("""(?:^|\s)([A-Za-z_]\w*)\s*(?:=.*)?$""")
private val JAVA_CONTROL_WORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "try", "do")
