package com.indagium

import com.indagium.model.DEFAULT_HOME_LAYOUT
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.homeLayoutJson
import com.indagium.ui.tileLayoutJson
import com.indagium.ui.tileLayoutNodeFromJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class K(val key: String) { A("a"), B("b"), C("c") }

private fun keyOf(k: K) = k.key

private fun fromKey(key: String): K? = K.entries.firstOrNull { it.key == key }

private fun decode(text: String, maxDepth: Int = 8): TileNode<K>? =
    tileLayoutNodeFromJson(Json.parseToJsonElement(text), ::fromKey, 0, maxDepth)

class TileLayoutJsonTest {
    @Test
    fun pinnedSizesRoundTripAndAreWrittenOnlyWhenSet() {
        val tree = TileSplit(
            TileAxis.ROW,
            listOf(
                TileLeaf(K.A, 1f, 240f),
                TileSplit(TileAxis.COLUMN, listOf(TileLeaf(K.B, 2f), TileLeaf(K.C, 0.5f, 120.5f)), 1.5f, 300f),
            ),
        )
        val json = tileLayoutJson(tree, ::keyOf)
        assertEquals(
            """{"split":"row","w":1.0,"children":[{"section":"a","w":1.0,"dp":240.0},""" +
                """{"split":"column","w":1.5,"dp":300.0,"children":[{"section":"b","w":2.0},{"section":"c","w":0.5,"dp":120.5}]}]}""",
            json.toString(),
        )
        assertEquals(tree, tileLayoutNodeFromJson(json, ::fromKey))
        assertTrue("dp" !in tileLayoutJson(TileLeaf(K.A), ::keyOf).toString())
    }

    @Test
    fun malformedInputDecodesToNullInsteadOfThrowing() {
        listOf(
            """[]""",
            """{}""",
            """{"section":"zzz"}""",
            """{"section":5}""".replace("5", "\"zzz\""),
            """{"split":"diagonal","children":[{"section":"a"}]}""",
            """{"split":"row"}""",
            """{"split":"row","children":[]}""",
            """{"split":"row","children":[{"section":"nope"},{"x":1}]}""",
            """{"split":"row","children":"a"}""",
        ).forEach { assertNull(decode(it), it) }
        assertNull(tileLayoutNodeFromJson(JsonNull, ::fromKey))
        assertNull(tileLayoutNodeFromJson(JsonPrimitive("a"), ::fromKey))
    }

    @Test
    fun badFieldsFallBackAndBadChildrenAreSkipped() {
        val leaf = decode("""{"section":"a","w":"heavy","dp":"wide"}""") as TileLeaf<K>
        assertEquals(1f, leaf.weight)
        assertNull(leaf.sizeDp)
        val split = decode("""{"split":"column","children":[{"section":"nope"},{"section":"b","w":3}]}""") as TileSplit<K>
        assertEquals(listOf(TileLeaf(K.B, 3f)), split.children)
    }

    @Test
    fun nestingBeyondTheDepthCapIsDropped() {
        fun nested(levels: Int): String =
            if (levels == 0) """{"section":"a"}""" else """{"split":"row","children":[${nested(levels - 1)}]}"""
        assertNotNull(decode(nested(2), maxDepth = 3))
        assertNull(decode(nested(3), maxDepth = 3))
        assertNotNull(decode(nested(6)))
        assertNull(decode(nested(8)))
    }

    @Test
    fun homeLayoutJsonIsByteIdenticalToWhatItWasBeforeTheGenericEngine() {
        assertEquals(
            """{"split":"column","w":1.0,"children":[{"split":"row","w":1.6,"children":[""" +
                """{"section":"open","w":1.0},{"section":"capture","w":1.0}]},{"section":"tests","w":1.0}]}""",
            homeLayoutJson(DEFAULT_HOME_LAYOUT).toString(),
        )
        assertEquals("""{"section":"tests","w":2.5}""", homeLayoutJson(HomeLayoutLeaf(HomeSectionId.TESTS, 2.5f)).toString())
    }
}
