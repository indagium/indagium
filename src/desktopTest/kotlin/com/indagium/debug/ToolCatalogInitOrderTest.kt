package com.indagium.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke check that the catalogues can be initialised in either order. The catalogue files all build
 * their schemas through ToolSchema.kt, so ControlServerKt -> TestSuiteToolCatalogKt -> ToolSchemaKt has
 * no cycle. (Test classes share one JVM, so this only proves the structure when this runs first.)
 */
class ToolCatalogInitOrderTest {
    @Test
    fun theTestSuiteCatalogueIsUsableBeforeTheMainCatalogue() {
        assertTrue(TEST_SUITE_MCP_TOOLS.isNotEmpty())
        assertTrue(TEST_SUITE_MCP_TOOLS.all { it.name.isNotBlank() })
        assertTrue(MCP_TOOLS.containsAll(TEST_SUITE_MCP_TOOLS))
        assertEquals(MCP_TOOLS.size, MCP_TOOLS.map { it.name }.distinct().size)
    }
}
