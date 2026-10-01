@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.indagium.ui

import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.AppSettings
import java.awt.GraphicsEnvironment
import java.util.concurrent.ConcurrentHashMap

/** System families are discovered once and resolved lazily so controls and span rendering share them. */
internal object FontCatalog {
    val families: List<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList().sorted() }
            .getOrDefault(emptyList())
    }

    private object MissingFont

    private val normalizedCache = ConcurrentHashMap<String, Any>()
    private val knownByLowercase by lazy { families.associateBy { it.lowercase() } }

    fun resolveOrNull(name: String?): FontFamily? {
        val selected = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = selected.lowercase()
        val resolved = normalizedCache.computeIfAbsent(normalized) {
            val actual = knownByLowercase[normalized] ?: return@computeIfAbsent MissingFont
            runCatching { FontFamily(actual) }.getOrDefault(FontFamily.Default)
        }
        return resolved as? FontFamily
    }

    fun resolve(name: String?, fallback: FontFamily): FontFamily {
        return resolveOrNull(name) ?: fallback
    }
}

internal fun AppSettings.resolvedInterfaceFontFamily(): FontFamily =
    FontCatalog.resolve(interfaceFontFamily, FontFamily.Default)

internal fun AppSettings.resolvedLogFontFamily(): FontFamily = FontCatalog.resolve(
    logFontFamily,
    if (fontMono) FontFamily.Monospace else FontFamily.Default,
)
