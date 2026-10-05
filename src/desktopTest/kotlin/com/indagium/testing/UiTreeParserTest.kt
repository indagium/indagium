package com.indagium.testing

import com.indagium.testing.device.parseUiAutomatorDump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiTreeParserTest {
    @Test
    fun trailingUiautomatorChatterAndEntitiesAreHandled() {
        val parsed = assertNotNull(parseUiAutomatorDump(fixtureUiDump()))
        assertEquals(1080, parsed.screenWidth)
        assertEquals(2400, parsed.screenHeight)
        assertEquals(listOf("Sign & go", ""), parsed.nodes.map { it.text })
        assertEquals(4, parsed.totalNodes)
    }

    @Test
    fun numericEntitiesAndLongTextAreDecodedAndClipped() {
        val long = "x".repeat(300)
        val text = "a&#10;b &#x41; &#0; &lt;tag&gt;"
        val xml = """<hierarchy><node text="$text" content-desc="$long" class="a.B" bounds="[0,0][5,5]" clickable="true"/></hierarchy>"""
        val node = assertNotNull(parseUiAutomatorDump(xml)).nodes.single()
        assertEquals("a\nb A  <tag>", node.text)
        assertEquals(121, node.contentDesc.length)
        assertTrue(node.contentDesc.endsWith("…"))
    }

    @Test
    fun outputWithoutAHierarchyIsNull() {
        assertNull(parseUiAutomatorDump("ERROR: null root node returned by UiTestAutomationBridge."))
        assertNull(parseUiAutomatorDump("<hierarchy rotation=\"0\"></hierarchy>"))
    }

    @Test
    fun nodesWithMalformedBoundsAreIgnored() {
        val xml = """<hierarchy><node text="ok" bounds="[0,0][9,9]"/><node text="bad" bounds="oops"/></hierarchy>"""
        assertEquals(listOf("ok"), assertNotNull(parseUiAutomatorDump(xml)).nodes.map { it.text })
    }
}
