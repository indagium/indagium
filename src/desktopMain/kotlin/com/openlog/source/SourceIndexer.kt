package com.openlog.source

import com.openlog.model.SourceWrapperRule
import java.io.File

// ── Public entry point ───────────────────────────────────────────────────

/** Builds a [SourceIndex] by scanning `.kt`/`.java` files under [roots] for android.util.Log
 *  and Timber call sites — pure text/regex/brace-matching, no compiler or parser dependency
 *  (see class-level notes in each private helper below for the specific heuristics used). */
object SourceIndexer {
    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val SKIP_DIR_NAMES = setOf("build", ".git", ".gradle", ".idea", "node_modules", "out")

    fun build(
        roots: List<File>,
        progress: ((scanned: Int, total: Int) -> Unit)? = null,
        options: SourceIndexBuildOptions = SourceIndexBuildOptions(),
    ): SourceIndex {
        val files = roots.flatMap { collectSourceFiles(it) }.distinct()
        val sites = mutableListOf<LogCallSite>()
        val fileMeta = mutableMapOf<String, FileMeta>()
        val texts = mutableMapOf<String, String>()

        files.forEach { file -> runCatching { texts[file.absolutePath] = file.readText() } }
        val globalConstants = buildGlobalConstants(texts)
        val discoveredRules = if (options.autoDiscover) discoverWrapperRules(texts) else emptyList()
        val wrapperRules = (options.wrapperRules + discoveredRules).distinct()

        files.forEachIndexed { idx, file ->
            runCatching {
                val text = texts[file.absolutePath] ?: return@runCatching
                fileMeta[file.absolutePath] = FileMeta(mtime = file.lastModified(), size = file.length())
                sites += extractCallSites(
                    file.absolutePath,
                    text,
                    isJavaFile = file.extension.equals("java", true),
                    globalConstants = globalConstants,
                )
                sites += extractWrapperCallSites(
                    file.absolutePath,
                    text,
                    isJavaFile = file.extension.equals("java", true),
                    wrapperRules = wrapperRules,
                    globalConstants = globalConstants,
                )
            }
            progress?.invoke(idx + 1, files.size)
        }

        return SourceIndex(
            version = SOURCE_INDEX_VERSION,
            roots = roots.map { it.absolutePath },
            sites = sites.distinctBy { site ->
                listOf(
                    site.filePath,
                    site.callLine,
                    site.tag,
                    site.matcher,
                    site.methodStartLine,
                    site.configurationDependent,
                )
            },
            fileMeta = fileMeta,
            builtAt = System.currentTimeMillis(),
            rootConfigFingerprints = roots.associate { root ->
                root.absolutePath to (options.configurationFingerprint
                    ?: sourceConfigurationFingerprint(emptyList(), options.autoDiscover))
            },
        )
    }

    private fun collectSourceFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { dir -> dir == root || (dir.name !in SKIP_DIR_NAMES && !dir.name.startsWith(".")) }
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .toList()
    }
}

data class SourceIndexBuildOptions(
    val wrapperRules: List<SourceWrapperRule> = emptyList(),
    val autoDiscover: Boolean = false,
    val configurationFingerprint: String? = null,
)

// ── Character classification (opaque = string/char literal or comment) ────

// Marks, for every offset in a file's text, whether that character is real code (false = inside
// a string/char literal or a comment, and therefore opaque to structural scanning like brace or
// paren matching). Interpolation holes (${...} / $x) inside a Kotlin string are deliberately
// treated as part of the opaque string span too — a portable brace-matcher can't safely peek
// inside them, so any stray `{`/`}` there is skipped along with the rest of the literal.
private class CodeMask(text: String) {
    val isCode = BooleanArray(text.length)

    init {
        var i = 0
        val n = text.length
        while (i < n) {
            i = when {
                text[i] == '/' && i + 1 < n && text[i + 1] == '/' -> skipLineComment(text, i, n)
                text[i] == '/' && i + 1 < n && text[i + 1] == '*' -> skipBlockComment(text, i, n)
                text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> skipTripleQuoted(text, i, n)
                text[i] == '"' -> skipQuoted(text, i, n, '"')
                text[i] == '\'' -> skipQuoted(text, i, n, '\'')
                else -> { isCode[i] = true; i + 1 }
            }
        }
    }

    private fun skipLineComment(text: String, from: Int, n: Int): Int {
        var i = from
        while (i < n && text[i] != '\n') i++
        return i
    }

    private fun skipBlockComment(text: String, from: Int, n: Int): Int {
        var i = from + 2
        while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
        return minOf(i + 2, n)
    }

    private fun skipTripleQuoted(text: String, from: Int, n: Int): Int {
        var i = from + 3
        while (i + 2 < n && !(text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"')) i++
        return minOf(i + 3, n)
    }

    private fun skipQuoted(text: String, from: Int, n: Int, quote: Char): Int {
        var i = from + 1
        while (i < n && text[i] != quote) {
            i += if (text[i] == '\\' && i + 1 < n) 2 else 1
        }
        return minOf(i + 1, n)
    }
}

// 1-based line lookup from an absolute char offset into a file's text.
private class LineIndex(text: String) {
    private val starts: IntArray = buildList {
        add(0)
        for (idx in text.indices) if (text[idx] == '\n') add(idx + 1)
    }.toIntArray()

    fun lineOf(offset: Int): Int {
        val o = offset.coerceIn(0, starts.last())
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}

// ── Generic structural scanning (parens/brackets/braces, code-aware) ──────

// Finds the index of the closing bracket matching the opener at [openIdx] (any of `([{`),
// treating `(`/`[`/`{` uniformly as "depth+1" and `)`/`]`/`}` as "depth-1" — sufficient for
// well-formed source since real code never mismatches bracket *kinds* within a balanced region.
// Chars where [mask] says non-code (inside a string/comment) are ignored entirely.
private fun findMatchingClose(text: String, mask: CodeMask, openIdx: Int): Int {
    var depth = 1
    var i = openIdx + 1
    val n = text.length
    while (i < n) {
        if (mask.isCode[i]) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return n - 1
}

private data class ArgList(val args: List<String>, val closeIdx: Int)

// Splits the call arguments between `(` at [openParenIdx] and its match into top-level
// comma-separated pieces (commas nested inside further parens/brackets/braces/lambdas don't split).
private fun parseArgs(text: String, mask: CodeMask, openParenIdx: Int): ArgList {
    val closeIdx = findMatchingClose(text, mask, openParenIdx)
    val args = mutableListOf<String>()
    var depth = 0
    var segStart = openParenIdx + 1
    var i = segStart
    while (i < closeIdx) {
        if (mask.isCode[i]) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    args.add(text.substring(segStart, i))
                    segStart = i + 1
                }
            }
        }
        i++
    }
    if (closeIdx > openParenIdx) args.add(text.substring(segStart, closeIdx))
    return ArgList(args, closeIdx)
}

// Skips whitespace and comment spans (but not string literals) starting at [from].
private fun skipNonCode(text: String, mask: CodeMask, from: Int): Int {
    var i = from
    while (i < text.length && (!mask.isCode[i] || text[i].isWhitespace())) i++
    return i
}

// ── Message-template extraction ────────────────────────────────────────────

private sealed class TplPart {
    data class Lit(val text: String) : TplPart()

    data object Hole : TplPart()
}

private fun decodeEscape(c: Char): Char = when (c) {
    'n' -> '\n'
    't' -> '\t'
    'r' -> '\r'
    'b' -> '\b'
    else -> c // covers \" \\ \' \$ and anything unrecognised (kept as the literal char itself)
}

private fun decodeQuotedLiteral(raw: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            sb.append(decodeEscape(raw[i + 1]))
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// If [i] sits on the closing quote(s) of the (non-)triple-quoted string, returns the index just
// past them; otherwise null.
private fun closingQuoteEnd(expr: String, i: Int, n: Int, triple: Boolean): Int? = if (triple) {
    if (expr[i] == '"' && i + 2 < n && expr.startsWith("\"\"\"", i)) i + 3 else null
} else {
    if (expr[i] == '"') i + 1 else null
}

// If [i] sits on a `$identifier` or `${...}` interpolation start, returns the index just past it
// (braces inside `${...}` are balanced, ignoring nesting depth only — no string-awareness needed
// since template holes don't themselves get inspected further); otherwise null.
private fun interpolationEnd(expr: String, i: Int, n: Int): Int? {
    if (expr[i] != '$' || i + 1 >= n) return null
    if (expr[i + 1] == '{') {
        var depth = 1
        var j = i + 2
        while (j < n && depth > 0) {
            when (expr[j]) {
                '{' -> depth++
                '}' -> depth--
            }
            j++
        }
        return j
    }
    if (Character.isJavaIdentifierStart(expr[i + 1])) {
        var j = i + 1
        while (j < n && Character.isJavaIdentifierPart(expr[j])) j++
        return j
    }
    return null
}

// Consumes one string literal (plain or Kotlin triple-quoted) starting at expr[startIdx] == '"',
// appending its literal text into [literal] and pushing Hole markers (flushing [literal] first)
// for `$x` / `${...}` interpolations. Returns the index just past the closing quote(s).
private fun consumeStringLiteral(expr: String, startIdx: Int, literal: StringBuilder, parts: MutableList<TplPart>): Int {
    val n = expr.length
    val triple = startIdx + 2 < n && expr[startIdx + 1] == '"' && expr[startIdx + 2] == '"'
    var i = if (triple) startIdx + 3 else startIdx + 1

    fun flushHole() {
        if (literal.isNotEmpty()) {
            parts.add(TplPart.Lit(literal.toString()))
            literal.clear()
        }
        parts.add(TplPart.Hole)
    }

    while (i < n) {
        closingQuoteEnd(expr, i, n, triple)?.let { return it }

        if (!triple && expr[i] == '\\' && i + 1 < n) {
            literal.append(decodeEscape(expr[i + 1]))
            i += 2
            continue
        }

        val interpEnd = interpolationEnd(expr, i, n)
        if (interpEnd != null) {
            flushHole()
            i = interpEnd
            continue
        }

        literal.append(expr[i])
        i++
    }
    return i
}

// Converts a Java Formatter template into the matcher form used by [buildMatcher]: all valid
// formatting specifiers (including positional/width/precision variants) become dynamic holes,
// while `%%` remains one literal percent. A malformed percent sequence stays literal rather than
// widening the matcher unexpectedly.
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
private fun formatterTemplateParts(format: String): List<TplPart> {
    val parts = mutableListOf<TplPart>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            parts += TplPart.Lit(literal.toString())
            literal.clear()
        }
    }

    var i = 0
    while (i < format.length) {
        if (format[i] != '%') {
            literal.append(format[i++])
            continue
        }
        if (i + 1 >= format.length) {
            literal.append(format[i++])
            continue
        }
        when (format[i + 1]) {
            '%' -> {
                literal.append('%')
                i += 2
                continue
            }
            'n' -> {
                literal.append('\n')
                i += 2
                continue
            }
        }

        var j = i + 1
        // Optional argument index, e.g. %2$s. If the digits are not followed by '$', they are
        // width digits and are re-read by the width parser below.
        val indexStart = j
        while (j < format.length && format[j].isDigit()) j++
        if (j < format.length && format[j] == '$') j++ else j = indexStart
        while (j < format.length && format[j] in "-#+ 0,(<") j++
        while (j < format.length && format[j].isDigit()) j++
        if (j < format.length && format[j] == '.') {
            j++
            while (j < format.length && format[j].isDigit()) j++
        }
        if (j < format.length && format[j] in "tT") {
            j++
            if (j < format.length && format[j] in "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc") {
                j++
            } else {
                literal.append('%')
                i++
                continue
            }
        } else if (j < format.length && format[j] in "bBhHsScCdoxXeEfgGaA") {
            j++
        } else {
            literal.append('%')
            i++
            continue
        }
        flushLiteral()
        parts += TplPart.Hole
        i = j
    }
    return parts
}

// Handles a complete `String.format("template", ...)` / `java.lang.String.format(...)`
// expression. Its first argument must resolve to static literal content: interpolated/dynamic
// templates deliberately fall back to ordinary expression parsing below rather than guessing.
private fun stringFormatTemplateParts(expr: String): List<TplPart>? {
    val prefix = Regex("""^\s*(?:java\s*\.\s*lang\s*\.\s*)?String\s*\.\s*format\s*\(""")
        .find(expr) ?: return null
    val mask = CodeMask(expr)
    val openParenIdx = prefix.range.last
    val args = parseArgs(expr, mask, openParenIdx)
    if (expr.substring(args.closeIdx + 1).isNotBlank()) return null
    val formatArg = args.args.firstOrNull() ?: return null
    val formatParts = buildTemplateParts(formatArg)
    if (formatParts.any { it is TplPart.Hole }) return null
    return formatterTemplateParts(formatParts.filterIsInstance<TplPart.Lit>().joinToString(separator = "") { it.text })
}

// Parses a raw argument-expression source snippet (e.g. `"count=" + n`, `"User $id logged in"`)
// into an ordered list of literal / dynamic-hole parts. Non-string content between literals is a
// hole UNLESS it's pure `+`/whitespace glue joining two adjacent literals (`"a" + "b"` -> "ab").
private fun buildTemplateParts(expr: String): List<TplPart> {
    stringFormatTemplateParts(expr)?.let { return it }
    val parts = mutableListOf<TplPart>()
    val literal = StringBuilder()
    val outside = StringBuilder()
    var i = 0
    val n = expr.length

    fun flushOutside() {
        val glueOnly = outside.all { it == '+' || it.isWhitespace() }
        if (!glueOnly) {
            if (literal.isNotEmpty()) {
                parts.add(TplPart.Lit(literal.toString()))
                literal.clear()
            }
            parts.add(TplPart.Hole)
        }
        outside.clear()
    }

    while (i < n) {
        if (expr[i] == '"') {
            flushOutside()
            i = consumeStringLiteral(expr, i, literal, parts)
        } else {
            outside.append(expr[i])
            i++
        }
    }
    flushOutside()
    if (literal.isNotEmpty()) parts.add(TplPart.Lit(literal.toString()))
    return parts
}

private data class MatcherInfo(val pattern: String, val literalLen: Int)

// Returns null when the template has no literal content at all (fully dynamic message) — such a
// site is un-indexable per spec and must be skipped rather than emitted with a match-everything matcher.
private fun buildMatcher(parts: List<TplPart>): MatcherInfo? {
    if (parts.none { it is TplPart.Lit }) return null
    val sb = StringBuilder("^")
    var literalLen = 0
    for (part in parts) {
        when (part) {
            is TplPart.Lit -> {
                sb.append(Regex.escape(part.text))
                literalLen += part.text.length
            }
            TplPart.Hole -> sb.append(".*?")
        }
    }
    if (parts.last() is TplPart.Lit) sb.append("$")
    return MatcherInfo(sb.toString(), literalLen)
}

// ── TAG resolution ──────────────────────────────────────────────────────────

private val KOTLIN_CONST_RE = Regex(
    """(?:(?:private|internal|protected|public|const)\s+){0,4}val\s+(\w+)\s*(?::\s*String\s*\?)?\s*=\s*"((?:[^"\\]|\\.)*)"""",
)
private val JAVA_CONST_RE = Regex(
    """(?:(?:private|public|protected|static|final)\s+){0,5}String\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"""",
)
private val FULL_STRING_LITERAL_RE = Regex("""^"((?:[^"\\]|\\.)*)"$""")
private val IDENTIFIER_RE = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

// First `package a.b.c` declaration (Kotlin, or Java with a trailing `;` that the identifier
// character class already excludes) in a real-code region of the file.
private val PACKAGE_DECL_RE = Regex("""^\s*package\s+([A-Za-z_][\w.]*)""", RegexOption.MULTILINE)

// Class-name-derived tag expressions (`Foo::class.simpleName`, `javaClass.canonicalName`, etc.)
// resolve to either the referenced class's SIMPLE name or its FULLY-QUALIFIED name, depending on
// which accessor member the source uses. `simpleName`/`getSimpleName()` are SIMPLE; everything
// that reflects the canonical/qualified name (`qualifiedName`, `canonicalName`,
// `getCanonicalName()`, `getName()`, `name` — the Kotlin `Class.name` idiom used via
// `javaClass.name` / `Foo::class.java.name`) is QUALIFIED.
private enum class AccessorKind { SIMPLE, QUALIFIED }

private val QUALIFIED_ACCESSOR_TOKENS = setOf("qualifiedName", "canonicalName", "getCanonicalName", "getName", "name")

private fun accessorKind(token: String): AccessorKind =
    if (token in QUALIFIED_ACCESSOR_TOKENS) AccessorKind.QUALIFIED else AccessorKind.SIMPLE

// `Foo::class.java.simpleName` / `Foo::class.simpleName` / `Foo::class.java.canonicalName` /
// `Foo::class.qualifiedName` / `Foo::class.java.name`, optionally qualified (`com.x.Foo::...`).
// The `(?!this\b)` guard keeps this from swallowing the self-referring `this::class...` form
// below — that one names the *enclosing* class, not a literal referenced class, so it needs its
// own path. Capture groups: 1 = referenced class ref, 2 = accessor token.
private const val KOTLIN_CLASS_LITERAL_RHS =
    """(?!this\b)([A-Za-z_][\w.]*)\s*::\s*class(?:\s*\.\s*java)?\s*\.\s*(simpleName|qualifiedName|canonicalName|name)"""
private val KOTLIN_CLASS_LITERAL_TAG_RE = Regex("^$KOTLIN_CLASS_LITERAL_RHS$")
private val KOTLIN_CLASS_LITERAL_CONST_RE = Regex(
    """(?:(?:private|internal|protected|public|const)\s+){0,4}val\s+(\w+)\s*(?::\s*String\s*\?)?\s*=\s*$KOTLIN_CLASS_LITERAL_RHS""",
)

// `Foo.class.getSimpleName()` / `Foo.class.getCanonicalName()` / `Foo.class.getName()`.
// Capture groups: 1 = referenced class ref, 2 = accessor method name.
private const val JAVA_CLASS_LITERAL_RHS =
    """([A-Za-z_][\w.]*)\s*\.\s*class\s*\.\s*(getSimpleName|getCanonicalName|getName)\s*\(\s*\)"""
private val JAVA_CLASS_LITERAL_TAG_RE = Regex("^$JAVA_CLASS_LITERAL_RHS$")
private val JAVA_CLASS_LITERAL_CONST_RE = Regex(
    """(?:(?:private|public|protected|static|final)\s+){0,5}String\s+(\w+)\s*=\s*$JAVA_CLASS_LITERAL_RHS""",
)

// `javaClass.simpleName` / `javaClass.canonicalName` / `javaClass.name` / `this.javaClass.simpleName` /
// `this::class.simpleName` / `this::class.qualifiedName` / `this::class.java.canonicalName` — all
// name whatever class/object textually encloses this expression, so they resolve via
// [findEnclosingNamedType] rather than a capture group. Capture group 1 = accessor token.
private const val KOTLIN_SELF_CLASS_RHS =
    """(?:(?:this\s*\.\s*)?javaClass|this\s*::\s*class(?:\s*\.\s*java)?)\s*\.\s*(simpleName|qualifiedName|canonicalName|name)"""
private val KOTLIN_SELF_CLASS_TAG_RE = Regex("^$KOTLIN_SELF_CLASS_RHS$")
private val KOTLIN_SELF_CLASS_CONST_RE = Regex(
    """(?:(?:private|internal|protected|public|const)\s+){0,4}val\s+(\w+)\s*(?::\s*String\s*\?)?\s*=\s*$KOTLIN_SELF_CLASS_RHS""",
)

// Java self forms: `getClass().getSimpleName()` / `getClass().getCanonicalName()` /
// `getClass().getName()`, optionally `this.getClass()...`. Capture group 1 = accessor method name.
private const val JAVA_SELF_CLASS_RHS =
    """(?:this\s*\.\s*)?getClass\s*\(\s*\)\s*\.\s*(getSimpleName|getCanonicalName|getName)\s*\(\s*\)"""
private val JAVA_SELF_CLASS_TAG_RE = Regex("^$JAVA_SELF_CLASS_RHS$")
private val JAVA_SELF_CLASS_CONST_RE = Regex(
    """(?:(?:private|public|protected|static|final)\s+){0,5}String\s+(\w+)\s*=\s*$JAVA_SELF_CLASS_RHS""",
)

private fun classSimpleNameFromQualified(qualified: String): String = qualified.substringAfterLast('.')

// Applies an [AccessorKind] to a resolved class SIMPLE name: SIMPLE passes it through unchanged;
// QUALIFIED builds `filePackage + "." + simpleName` (or just `simpleName` for the default
// package), matching what `getCanonicalName()`/`qualifiedName`/`getName()` produce at runtime for
// a TOP-LEVEL class. NOTE (nested-class caveat): for a NESTED class, the real runtime value also
// includes the outer class(es) (`pkg.Outer.Inner`), but this always yields `pkg.Inner` since the
// indexer doesn't track class nesting — acceptable for v1, most tag-holding classes are top-level.
private fun applyAccessor(simpleName: String, kind: AccessorKind, filePackage: String?): String = when (kind) {
    AccessorKind.SIMPLE -> simpleName
    AccessorKind.QUALIFIED -> if (filePackage.isNullOrEmpty()) simpleName else "$filePackage.$simpleName"
}

// Finds the file's `package` declaration (first match in a real-code region), or null for the
// default package. Parsed once per file and threaded into tag resolution so QUALIFIED-accessor
// results can be built as `package + "." + simpleName`.
private fun findFilePackage(text: String, mask: CodeMask): String? {
    for (m in PACKAGE_DECL_RE.findAll(text)) {
        if (mask.isCode.getOrElse(m.range.first) { false }) return m.groupValues[1]
    }
    return null
}

// Scans the whole file for `TAG`-like constant definitions — Kotlin `val`/`const val`, Java
// `static final String` — assigned either a string literal, a class-literal expression
// (`Foo::class.java.simpleName`, `Baz.class.getCanonicalName()`), or a self-referring
// class-name expression (`javaClass.simpleName`, `this::class.qualifiedName`,
// `getClass().getCanonicalName()`) — so identifier-based tag args (the overwhelmingly common
// `Log.d(TAG, ...)` case) can be resolved to their literal value. Definitions retain their
// enclosing class scope: a source file commonly has several classes, each with its own `TAG`.
private data class ScopedConst(val value: String, val enclosingType: String?)

private fun buildConstMap(text: String, mask: CodeMask, filePackage: String?): Map<String, List<ScopedConst>> {
    val map = LinkedHashMap<String, MutableList<ScopedConst>>()

    fun isReal(offset: Int) = mask.isCode.getOrElse(offset) { false }

    fun add(name: String, value: String, offset: Int) {
        val scoped = ScopedConst(value, findEnclosingNamedType(text, mask, offset))
        val entries = map.getOrPut(name) { mutableListOf() }
        if (entries.none { it.enclosingType == scoped.enclosingType }) entries += scoped
    }

    for (re in listOf(KOTLIN_CONST_RE, JAVA_CONST_RE)) {
        for (m in re.findAll(text)) {
            if (!isReal(m.range.first)) continue
            add(m.groupValues[1], decodeQuotedLiteral(m.groupValues[2]), m.range.first)
        }
    }
    for (re in listOf(KOTLIN_CLASS_LITERAL_CONST_RE, JAVA_CLASS_LITERAL_CONST_RE)) {
        for (m in re.findAll(text)) {
            if (!isReal(m.range.first)) continue
            val simple = classSimpleNameFromQualified(m.groupValues[2])
            add(m.groupValues[1], applyAccessor(simple, accessorKind(m.groupValues[3]), filePackage), m.range.first)
        }
    }
    for (re in listOf(KOTLIN_SELF_CLASS_CONST_RE, JAVA_SELF_CLASS_CONST_RE)) {
        for (m in re.findAll(text)) {
            if (!isReal(m.range.first)) continue
            val enclosing = findEnclosingNamedType(text, mask, m.range.first) ?: continue
            add(m.groupValues[1], applyAccessor(enclosing, accessorKind(m.groupValues[2]), filePackage), m.range.first)
        }
    }
    return map
}

// Resolves an explicit class-literal or self-referring class-name expression (see the RHS regexes
// above) to a tag value, applying the right [AccessorKind]. Self forms resolve the enclosing class
// via [findEnclosingNamedType] at [callOffset] — the offset of the enclosing `Log`/`Timber` call,
// which sits in the same brace scope as its own arguments and so is a safe stand-in for the
// argument's own offset. Returns null when [trimmed] matches none of these forms.
private fun resolveClassNameExpr(trimmed: String, callOffset: Int, text: String, mask: CodeMask, filePackage: String?): String? {
    KOTLIN_CLASS_LITERAL_TAG_RE.matchEntire(trimmed)?.let {
        val simple = classSimpleNameFromQualified(it.groupValues[1])
        return applyAccessor(simple, accessorKind(it.groupValues[2]), filePackage)
    }
    JAVA_CLASS_LITERAL_TAG_RE.matchEntire(trimmed)?.let {
        val simple = classSimpleNameFromQualified(it.groupValues[1])
        return applyAccessor(simple, accessorKind(it.groupValues[2]), filePackage)
    }
    val selfAccessorToken = KOTLIN_SELF_CLASS_TAG_RE.matchEntire(trimmed)?.groupValues?.get(1)
        ?: JAVA_SELF_CLASS_TAG_RE.matchEntire(trimmed)?.groupValues?.get(1)
        ?: return null
    val enclosing = findEnclosingNamedType(text, mask, callOffset) ?: return null
    return applyAccessor(enclosing, accessorKind(selfAccessorToken), filePackage)
}

private fun resolveTag(
    rawArg: String,
    callOffset: Int,
    text: String,
    mask: CodeMask,
    constMap: Map<String, List<ScopedConst>>,
    filePackage: String?,
    globalConstants: Map<String, String> = emptyMap(),
): String? {
    val trimmed = rawArg.trim()
    FULL_STRING_LITERAL_RE.matchEntire(trimmed)?.let { return decodeQuotedLiteral(it.groupValues[1]) }
    resolveClassNameExpr(trimmed, callOffset, text, mask, filePackage)?.let { return it }
    if (IDENTIFIER_RE.matches(trimmed)) {
        val enclosingType = findEnclosingNamedType(text, mask, callOffset)
        return constMap[trimmed]?.firstOrNull { it.enclosingType == enclosingType }?.value
            ?: constMap[trimmed]?.firstOrNull { it.enclosingType == null }?.value
    }
    // Cross-file constants must be qualified (`Telemetry.BUG`). A bare `TAG` is scoped to the
    // containing file/type; resolving it from a project-wide map makes every Log.d(TAG, ...) use
    // whichever class happened to be scanned first.
    globalConstants[trimmed]?.let { return it }
    return null
}

private fun buildGlobalConstants(texts: Map<String, String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    texts.forEach { (_, text) ->
        val mask = CodeMask(text)
        val pkg = findFilePackage(text, mask)
        for (regex in listOf(KOTLIN_CONST_RE, JAVA_CONST_RE)) {
            regex.findAll(text).forEach { match ->
                if (!mask.isCode.getOrElse(match.range.first) { false }) return@forEach
                val owner = findEnclosingNamedType(text, mask, match.range.first)
                val value = decodeQuotedLiteral(match.groupValues[2])
                val name = match.groupValues[1]
                if (owner != null) {
                    result["$owner.$name"] = value
                    if (!pkg.isNullOrBlank()) result["$pkg.$owner.$name"] = value
                }
            }
        }
    }
    return result
}

// ── Method-boundary detection ────────────────────────────────────────────────

// Constructs / control-flow keywords that can precede `(...)  {` — used to reject false-positive
// "method signature" matches like `if (x) {` or `for (i in xs) {` in the Java-style heuristic.
private val BLOCK_KEYWORDS = setOf(
    "if", "for", "while", "switch", "catch", "else", "do", "try",
    "synchronized", "class", "interface", "enum", "object", "companion", "when", "init", "annotation",
)

private val KOTLIN_FUN_RE = Regex("""\bfun\s*(?:<[^>]*>\s*)?(\w+)\s*\(""")
private val JAVA_METHOD_RE = Regex("""(\w+)\s*\([^()]*\)\s*(?:throws\s+[\w.,\s<>\[\]]+)?\s*$""")

// A Kotlin `init { }` block's header is just the soft keyword `init` immediately before the brace
// (whitespace/newlines aside). `fun init()` is checked first via [KOTLIN_FUN_RE], so this can't
// misfire on a real function named `init`. Known minor edge, not worth guarding against: a
// higher-order call written as `val x = init { ... }` textually ends in `init` too and would be
// mis-read as an init block.
private val KOTLIN_INIT_RE = Regex("""(?:^|\W)(init)\s*$""")

// A Kotlin secondary constructor's header ends in `constructor(...)`.
private val KOTLIN_CONSTRUCTOR_RE = Regex("""\bconstructor\s*\(""")

private data class NameMatch(val name: String, val offsetInHeader: Int)

// `init { }` blocks and secondary `constructor(...) { }` are Kotlin-only forms, so this runs
// regardless of [isJavaFile] — the Java heuristic below stays gated as before.
private fun extractKotlinSpecialForm(trimmed: String): NameMatch? {
    KOTLIN_INIT_RE.find(trimmed)?.let { return NameMatch("init", it.groups[1]!!.range.first) }
    KOTLIN_CONSTRUCTOR_RE.find(trimmed)?.let { return NameMatch("constructor", it.range.first) }
    return null
}

private fun extractJavaMethodName(trimmed: String): NameMatch? {
    if (trimmed.endsWith("->")) return null
    val m = JAVA_METHOD_RE.find(trimmed) ?: return null
    val name = m.groupValues[1]
    if (name in BLOCK_KEYWORDS) return null
    return NameMatch(name, m.range.first)
}

// [header] is the statement text immediately preceding an opening `{`. isJavaFile gates the
// generic "identifier(...)" heuristic off for .kt files, where it would misfire on Compose-style
// trailing-lambda calls like `Column(modifier) { ... }` — Kotlin only ever declares functions via `fun`.
private fun extractMethodName(header: String, isJavaFile: Boolean): NameMatch? {
    KOTLIN_FUN_RE.find(header)?.let { return NameMatch(it.groupValues[1], it.range.first) }
    val trimmed = header.trimEnd()
    extractKotlinSpecialForm(trimmed)?.let { return it }
    if (!isJavaFile) return null
    return extractJavaMethodName(trimmed)
}

private fun findEnclosingOpenBrace(text: String, mask: CodeMask, fromOffset: Int): Int? {
    var depth = 0
    var i = fromOffset - 1
    while (i >= 0) {
        if (mask.isCode[i]) {
            when (text[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        i--
    }
    return null
}

private fun findHeaderStart(text: String, mask: CodeMask, openBraceIdx: Int): Int {
    var i = openBraceIdx - 1
    while (i >= 0) {
        if (mask.isCode[i] && (text[i] == ';' || text[i] == '{' || text[i] == '}')) return i + 1
        i--
    }
    return 0
}

private data class MethodInfo(val name: String, val startLine: Int, val endLine: Int)

private const val MAX_ENCLOSING_SCAN_ITERATIONS = 200

// Matches a type-declaration header ending in `class Name`, `object Name`, `interface Name`, or
// `enum class Name`. An anonymous `companion object` (no name follows `object`) simply fails to
// match, which is what lets [findEnclosingNamedType] fall through and keep walking outward to it.
private val TYPE_HEADER_RE = Regex("""\b(?:enum\s+class|class|interface|object)\s+(\w+)""")

// Walks outward from [fromOffset] through enclosing `{...}` blocks — same traversal as
// [findEnclosingMethod] — looking for the nearest NAMED type declaration (class/object/interface/
// enum). Used to resolve self-referring tag expressions like `javaClass.simpleName`: an anonymous
// companion object block is skipped (developers writing `javaClass.simpleName` inside a companion
// mean the outer class), so the walk continues to its containing `class Name`. Returns null at
// file top level, where there's no enclosing class name to derive.
private fun findEnclosingNamedType(text: String, mask: CodeMask, fromOffset: Int): String? {
    var pos = fromOffset
    var guard = 0
    while (guard++ < MAX_ENCLOSING_SCAN_ITERATIONS) {
        val openBrace = findEnclosingOpenBrace(text, mask, pos) ?: return null
        val headerStart = findHeaderStart(text, mask, openBrace)
        val header = text.substring(headerStart, openBrace)
        TYPE_HEADER_RE.find(header)?.let { return it.groupValues[1] }
        pos = openBrace
    }
    return null
}

// Walks outward from [callOffset] through enclosing `{...}` blocks (skipping non-method blocks
// like `if`/`for`/lambdas) until a method/function signature is found, or the file top level is
// reached — in which case the call is attributed to a synthetic "<file>" pseudo-method spanning
// just its own line.
private fun findEnclosingMethod(text: String, mask: CodeMask, lines: LineIndex, callOffset: Int, isJavaFile: Boolean): MethodInfo {
    var pos = callOffset
    var guard = 0
    while (guard++ < MAX_ENCLOSING_SCAN_ITERATIONS) {
        val openBrace = findEnclosingOpenBrace(text, mask, pos) ?: break
        val headerStart = findHeaderStart(text, mask, openBrace)
        val header = text.substring(headerStart, openBrace)
        val nameMatch = extractMethodName(header, isJavaFile)
        if (nameMatch != null) {
            val startLine = lines.lineOf(headerStart + nameMatch.offsetInHeader)
            val endBrace = findMatchingClose(text, mask, openBrace)
            return MethodInfo(nameMatch.name, startLine, lines.lineOf(endBrace))
        }
        pos = openBrace
    }
    val line = lines.lineOf(callOffset)
    return MethodInfo("<file>", line, line)
}

// ── Call-site extraction ──────────────────────────────────────────────────────

private val LOG_METHODS = setOf("v", "d", "i", "w", "e", "wtf")
private val TIMBER_METHODS = setOf("v", "d", "i", "w", "e", "wtf")
private val CALL_RE = Regex("""\b(Log|Timber)\s*\.\s*(\w+)\s*\(""")

private data class ChainCall(val method: String, val openParenIdx: Int)

// From just after a `Timber.tag(...)` call's closing paren, looks for an immediately-chained
// `.d(...)`-style call (`Timber.tag("Net").e(...)`) — comments/whitespace between them are skipped.
private fun findChainedTimberCall(text: String, mask: CodeMask, afterCloseParenIdx: Int): ChainCall? {
    var i = skipNonCode(text, mask, afterCloseParenIdx + 1)
    if (i >= text.length || text[i] != '.') return null
    i = skipNonCode(text, mask, i + 1)
    val identStart = i
    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
    val ident = text.substring(identStart, i)
    if (ident !in TIMBER_METHODS) return null
    i = skipNonCode(text, mask, i)
    if (i >= text.length || text[i] != '(') return null
    return ChainCall(ident, i)
}

private fun buildSite(
    filePath: String,
    text: String,
    mask: CodeMask,
    lines: LineIndex,
    isJavaFile: Boolean,
    callStartIdx: Int,
    msgExprRaw: String,
    tag: String?,
    configurationDependent: Boolean = false,
): LogCallSite? {
    val parts = buildTemplateParts(msgExprRaw)
    val matcherInfo = buildMatcher(parts) ?: return null
    val methodInfo = findEnclosingMethod(text, mask, lines, callStartIdx, isJavaFile)
    return LogCallSite(
        filePath = filePath,
        tag = tag,
        methodName = methodInfo.name,
        methodStartLine = methodInfo.startLine,
        methodEndLine = methodInfo.endLine,
        callLine = lines.lineOf(callStartIdx),
        matcher = matcherInfo.pattern,
        literalLen = matcherInfo.literalLen,
        configurationDependent = configurationDependent,
    )
}

// Bundles the per-file scan state so the match-processing helpers below don't need a long,
// repeated parameter list.
private class ScanCtx(
    val filePath: String,
    val text: String,
    val mask: CodeMask,
    val lines: LineIndex,
    val constMap: Map<String, List<ScopedConst>>,
    val isJavaFile: Boolean,
    val filePackage: String?,
    val globalConstants: Map<String, String>,
)

private fun extractCallSites(
    filePath: String,
    text: String,
    isJavaFile: Boolean,
    globalConstants: Map<String, String> = emptyMap(),
): List<LogCallSite> {
    val mask = CodeMask(text)
    val filePackage = findFilePackage(text, mask)
    val constMap = buildConstMap(text, mask, filePackage)
    val ctx = ScanCtx(filePath, text, mask, LineIndex(text), constMap, isJavaFile, filePackage, globalConstants)
    return CALL_RE.findAll(text).mapNotNull { processCallMatch(ctx, it) }.toList()
}

private fun processCallMatch(ctx: ScanCtx, m: MatchResult): LogCallSite? {
    val startIdx = m.range.first
    if (!ctx.mask.isCode.getOrElse(startIdx) { false }) return null
    val receiver = m.groupValues[1]
    val method = m.groupValues[2]
    val openParenIdx = m.range.last

    return when {
        receiver == "Log" -> processLogCall(ctx, startIdx, method, openParenIdx)
        method == "tag" -> processTimberTagChain(ctx, startIdx, openParenIdx)
        method in TIMBER_METHODS -> processTimberCall(ctx, startIdx, openParenIdx)
        else -> null
    }
}

private fun processLogCall(ctx: ScanCtx, startIdx: Int, method: String, openParenIdx: Int): LogCallSite? {
    if (method !in LOG_METHODS) return null
    val callArgs = parseArgs(ctx.text, ctx.mask, openParenIdx)
    if (callArgs.args.size < 2) return null
    val tag = resolveTag(callArgs.args[0], startIdx, ctx.text, ctx.mask, ctx.constMap, ctx.filePackage, ctx.globalConstants)
    return buildSite(ctx.filePath, ctx.text, ctx.mask, ctx.lines, ctx.isJavaFile, startIdx, callArgs.args[1], tag)
}

// Handles a `Timber.tag("X").d(...)` chain — the "tag" call itself isn't a log site, the log
// site is the method chained immediately after it.
private fun processTimberTagChain(ctx: ScanCtx, startIdx: Int, openParenIdx: Int): LogCallSite? {
    val tagArgs = parseArgs(ctx.text, ctx.mask, openParenIdx)
    val tagValue = tagArgs.args.getOrNull(0)?.let {
        resolveTag(it, startIdx, ctx.text, ctx.mask, ctx.constMap, ctx.filePackage, ctx.globalConstants)
    }
    val chain = findChainedTimberCall(ctx.text, ctx.mask, tagArgs.closeIdx) ?: return null
    val callArgs = parseArgs(ctx.text, ctx.mask, chain.openParenIdx)
    if (callArgs.args.isEmpty()) return null
    return buildSite(ctx.filePath, ctx.text, ctx.mask, ctx.lines, ctx.isJavaFile, startIdx, callArgs.args[0], tagValue)
}

private fun processTimberCall(ctx: ScanCtx, startIdx: Int, openParenIdx: Int): LogCallSite? {
    val callArgs = parseArgs(ctx.text, ctx.mask, openParenIdx)
    if (callArgs.args.isEmpty()) return null
    return buildSite(ctx.filePath, ctx.text, ctx.mask, ctx.lines, ctx.isJavaFile, startIdx, callArgs.args[0], null)
}

private val CUSTOM_CALL_RE = Regex("""\b([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)?)\s*\.\s*(\w+)\s*\(""")
private val KOTLIN_FUNCTION_DECL_RE = Regex("""\bfun\s+(\w+)\s*\(([^)]*)\)""")
private val JAVA_METHOD_DECL_RE = Regex(
    """(?m)^\s*(?:(?:public|private|protected|static|final|synchronized)\s+)*[\w<>,.?\[\]]+\s+(\w+)\s*\(([^)]*)\)\s*\{""",
)

private fun parameterNames(raw: String, isJavaFile: Boolean): List<String> = raw.split(',').mapNotNull { parameter ->
    val cleaned = parameter.substringBefore('=').trim()
    if (cleaned.isBlank()) return@mapNotNull null
    if (isJavaFile) Regex("""([A-Za-z_]\w*)\s*$""").find(cleaned)?.groupValues?.get(1)
    else Regex("""([A-Za-z_]\w*)\s*(?::|$)""").find(cleaned)?.groupValues?.get(1)
}

private fun enclosingFunctionParameters(text: String, callOffset: Int, methodName: String, isJavaFile: Boolean): List<String> {
    val declarations = if (isJavaFile) JAVA_METHOD_DECL_RE.findAll(text).toList() else KOTLIN_FUNCTION_DECL_RE.findAll(text).toList()
    val declaration = declarations.lastOrNull { it.range.first < callOffset && it.groupValues[1] == methodName } ?: return emptyList()
    return parameterNames(declaration.groupValues[2], isJavaFile)
}

private fun declaredOwnerCandidates(receiver: String, text: String, beforeOffset: Int, filePackage: String?): Set<String> {
    val normalizedReceiver = receiver.replace(" ", "")
    val simpleReceiver = normalizedReceiver.substringAfterLast('.')
    val candidates = linkedSetOf(normalizedReceiver, simpleReceiver)
    val declaration = Regex(
        """(?m)\b(?:public\s+|private\s+|protected\s+|internal\s+|final\s+|override\s+)*""" +
            """(?:val|var)\s+$simpleReceiver\s*:\s*([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""",
    ).findAll(text.substring(0, beforeOffset)).lastOrNull()
    declaration?.groupValues?.getOrNull(1)?.let {
        candidates += it
        if (!filePackage.isNullOrBlank() && !it.contains('.')) candidates += "$filePackage.$it"
    }
    val parameter = Regex("""\b$simpleReceiver\s*:\s*([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")
        .findAll(text.substring(0, beforeOffset)).lastOrNull()
    parameter?.groupValues?.getOrNull(1)?.let {
        candidates += it
        if (!filePackage.isNullOrBlank() && !it.contains('.')) candidates += "$filePackage.$it"
    }
    val javaField = Regex(
        """(?m)\b(?:public\s+|private\s+|protected\s+|static\s+|final\s+|volatile\s+)*([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s+$simpleReceiver\s*(?:=|;)""",
    ).findAll(text.substring(0, beforeOffset)).lastOrNull()
    javaField?.groupValues?.getOrNull(1)?.let {
        candidates += it
        if (!filePackage.isNullOrBlank() && !it.contains('.')) candidates += "$filePackage.$it"
    }
    val javaParameter = Regex(
        """\b([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s+$simpleReceiver\s*(?:[,)]|$)""",
    ).findAll(text.substring(0, beforeOffset)).lastOrNull()
    javaParameter?.groupValues?.getOrNull(1)?.let {
        candidates += it
        if (!filePackage.isNullOrBlank() && !it.contains('.')) candidates += "$filePackage.$it"
    }
    return candidates
}

private fun ownerMatches(rule: SourceWrapperRule, candidates: Set<String>): Boolean {
    val configured = rule.ownerType.trim()
    if (configured.isBlank()) return false
    val configuredSimple = configured.substringAfterLast('.')
    return if (configured.contains('.')) configured in candidates else configuredSimple in candidates
}

private fun extractWrapperCallSites(
    filePath: String,
    text: String,
    isJavaFile: Boolean,
    wrapperRules: List<SourceWrapperRule>,
    globalConstants: Map<String, String>,
): List<LogCallSite> {
    if (wrapperRules.isEmpty()) return emptyList()
    val mask = CodeMask(text)
    val lines = LineIndex(text)
    val filePackage = findFilePackage(text, mask)
    val constMap = buildConstMap(text, mask, filePackage)
    return CUSTOM_CALL_RE.findAll(text).mapNotNull { match ->
        val startIdx = match.range.first
        if (!mask.isCode.getOrElse(startIdx) { false }) return@mapNotNull null
        val receiver = match.groupValues[1].replace(Regex("\\s+"), "")
        val methodName = match.groupValues[2]
        val candidates = declaredOwnerCandidates(receiver, text, startIdx, filePackage)
        val rule = wrapperRules.firstOrNull { it.methodName == methodName && ownerMatches(it, candidates) }
            ?: return@mapNotNull null
        val args = parseArgs(text, mask, match.range.last).args
        val tagExpr = args.getOrNull(rule.tagArgumentIndex) ?: return@mapNotNull null
        val messageExpr = args.getOrNull(rule.messageArgumentIndex) ?: return@mapNotNull null
        val tag = resolveTag(tagExpr, startIdx, text, mask, constMap, filePackage, globalConstants)
        buildSite(
            filePath,
            text,
            mask,
            lines,
            isJavaFile,
            startIdx,
            messageExpr,
            tag,
            configurationDependent = true,
        )
    }.toList()
}

private fun discoverWrapperRules(texts: Map<String, String>): List<SourceWrapperRule> {
    val discovered = linkedSetOf<SourceWrapperRule>()
    texts.forEach { (filePath, text) ->
        val mask = CodeMask(text)
        val pkg = findFilePackage(text, mask)
        val isJavaFile = filePath.endsWith(".java", ignoreCase = true)
        CALL_RE.findAll(text).forEach { match ->
            if (!mask.isCode.getOrElse(match.range.first) { false }) return@forEach
            val receiver = match.groupValues[1]
            if (receiver != "Log" && receiver != "Timber") return@forEach
            val args = parseArgs(text, mask, match.range.last).args
            if (args.size < 2) return@forEach
            val method = findEnclosingMethod(text, mask, LineIndex(text), match.range.first, isJavaFile)
            if (method.name == "<file>") return@forEach
            val params = enclosingFunctionParameters(text, match.range.first, method.name, isJavaFile)
            val tagIndex = params.indexOf(args[0].trim()).takeIf { it >= 0 } ?: return@forEach
            val messageIndex = params.indexOf(args[1].trim()).takeIf { it >= 0 } ?: return@forEach
            val owner = findEnclosingNamedType(text, mask, match.range.first) ?: return@forEach
            val ownerName = if (pkg.isNullOrBlank()) owner else "$pkg.$owner"
            val rule = SourceWrapperRule(ownerName, method.name, tagIndex, messageIndex)
            discovered += rule
            implementedTypes(text, owner).forEach { interfaceName ->
                discovered += rule.copy(ownerType = interfaceName)
                if (!pkg.isNullOrBlank()) discovered += rule.copy(ownerType = "$pkg.$interfaceName")
            }
        }
    }
    return discovered.toList()
}

private fun implementedTypes(text: String, owner: String): List<String> {
    val kotlinMatch = Regex("""(?:class|object)\s+$owner\b[^\{]*:\s*([^\{]+)\{""").find(text)
    val javaMatch = Regex("""class\s+$owner\b[^\{]*\bimplements\s+([^\{]+)\{""").find(text)
    val implemented = kotlinMatch?.groupValues?.getOrNull(1) ?: javaMatch?.groupValues?.getOrNull(1) ?: return emptyList()
    return implemented.split(',').map { it.trim().substringBefore('<').trim() }
        .filter { it.matches(Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")) }
}
