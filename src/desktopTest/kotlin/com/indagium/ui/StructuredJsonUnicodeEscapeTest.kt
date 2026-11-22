package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredJsonUnicodeEscapeTest {
    @Test
    fun aSignedOrNonHexUnicodeEscapeIsMalformed() {
        assertFalse(isStructuredJsonRecord("{\"k\":\"\\u-001\"}"))
        assertFalse(isStructuredJsonRecord("{\"k\":\"\\u+041\"}"))
        assertFalse(isStructuredJsonRecord("{\"k\":\"\\u00zz\"}"))
        assertFalse(isStructuredJsonRecord("{\"k\":\"\\u 041\"}"))
    }

    @Test
    fun aValidUnicodeEscapeStillParses() {
        assertTrue(isStructuredJsonRecord("{\"k\":\"\\u0041\"}"))
        assertTrue(isStructuredJsonRecord("{\"k\":\"\\u00E9\"}"))
    }
}
