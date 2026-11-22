package com.indagium.utils

/**
 * Whether every char of [text] is a hexadecimal digit (and [text] is not empty). `toIntOrNull(16)` also accepts a leading `+` or `-`,
 * so a decoder of an escape like `é` must check the digits first: `\u-001` is malformed, not U+FFFF.
 */
internal fun isHexDigits(text: CharSequence): Boolean =
    text.isNotEmpty() && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** The value of [text] when it is exactly hexadecimal digits and fits an [Int], else null (no sign, no whitespace). */
internal fun parseHexDigits(text: CharSequence): Int? =
    if (isHexDigits(text)) text.toString().toIntOrNull(16) else null

/** Like [parseHexDigits] for an eight-digit escape whose value may need the full unsigned range of an [Int]. */
internal fun parseHexDigitsLong(text: CharSequence): Long? =
    if (isHexDigits(text)) text.toString().toLongOrNull(16) else null
