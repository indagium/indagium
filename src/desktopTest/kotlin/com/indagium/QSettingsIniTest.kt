package com.indagium

import com.indagium.utils.QSettingsIni
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QSettingsIniTest {
    @Test
    fun sectionsPrefixKeysAndBackslashesBecomeSlashes() {
        val ini = QSettingsIni.parse(
            """
            [General]
            top=1

            [Coll]
            sets\size=2
            sets\1\Set\name=Alpha
            """.trimIndent(),
        )
        assertEquals("1", ini.string("top"))
        assertEquals(2, ini.int("Coll/sets/size"))
        assertEquals("Alpha", ini.string("Coll/sets/1/Set/name"))
        assertTrue(ini.hasGroup("Coll/sets"))
        assertFalse(ini.hasGroup("Other"))
    }

    @Test
    fun commentsBlankLinesBomAndCrlfAreTolerated() {
        val ini = QSettingsIni.parse("﻿; a comment\r\n\r\n[S]\r\n# another\r\nk=v\r\n")
        assertEquals("v", ini.string("S/k"))
    }

    @Test
    fun keyEscapesAreDecoded() {
        val ini = QSettingsIni.parse("[S]\nmy%20key=1\n%U00e9=2\n")
        assertEquals("1", ini.string("S/my key"))
        assertEquals("2", ini.string("S/é"))
    }

    @Test
    fun quotedValuesAndEscapesAreDecoded() {
        val ini = QSettingsIni.parse(
            """
            [S]
            a="x\\y \"q\" \x41\x263a"
            b=plain\\d+
            c="  padded  "
            d=  trimmed  
            e="a, b"
            """.trimIndent(),
        )
        assertEquals("x\\y \"q\" A☺", ini.string("S/a"))
        assertEquals("plain\\d+", ini.string("S/b"))
        assertEquals("  padded  ", ini.string("S/c"))
        assertEquals("trimmed", ini.string("S/d"))
        assertEquals("a, b", ini.string("S/e"))
        assertFalse(ini.value("S/e")!!.isList)
    }

    @Test
    fun unquotedCommasMakeAStringList() {
        val ini = QSettingsIni.parse("[S]\nl=a, b ,\"c,d\"\nempty=@Invalid()\nat=@@literal\n")
        assertEquals(listOf("a", "b", "c,d"), ini.stringList("S/l"))
        assertTrue(ini.value("S/l")!!.isList)
        assertEquals(emptyList(), ini.stringList("S/empty"))
        assertEquals("@literal", ini.string("S/at"))
    }

    @Test
    fun variantBlobsAreReportedNotDecoded() {
        val ini = QSettingsIni.parse("[S]\nsize=@Variant(\\0\\0\\0\\x1a)\nok=1\n")
        assertNull(ini.string("S/size"))
        assertEquals(listOf("S/size"), ini.undecodedKeys)
        assertEquals("1", ini.string("S/ok"))
    }

    @Test
    fun boolsAndIntsParseLikeQt() {
        val ini = QSettingsIni.parse("[S]\nt=true\nf=false\none=1\nzero=0\nbad=maybe\nn=15\nx=abc\n")
        assertEquals(true, ini.bool("S/t"))
        assertEquals(false, ini.bool("S/f"))
        assertEquals(true, ini.bool("S/one"))
        assertEquals(false, ini.bool("S/zero"))
        assertNull(ini.bool("S/bad"))
        assertNull(ini.bool("S/missing"))
        assertEquals(15, ini.int("S/n"))
        assertNull(ini.int("S/x"))
    }

    @Test
    fun aTrailingBackslashContinuesTheValueOnTheNextLine() {
        val ini = QSettingsIni.parse("[S]\nk=abc\\\ndef\nnext=1\n")
        assertEquals("abcdef", ini.string("S/k"))
        assertEquals("1", ini.string("S/next"))
    }
}
