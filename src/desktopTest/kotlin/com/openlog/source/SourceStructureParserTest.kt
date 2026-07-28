package com.openlog.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceStructureParserTest {
    @Test
    fun kotlinStructureListsNestedTypesMembersAndIgnoresCommentLookalikes() {
        val parsed = SourceStructureParser.parse(
            """
            // class Pretend { fun nope() {} }
            class Host {
                val title = "fun notAFunction() {}"
                init { check(title.isNotEmpty()) }
                constructor(id: String) : this()
                fun run(id: String) { println(id) }
                class Nested { fun child() = Unit }
            }
            """.trimIndent(),
            isJavaFile = false,
        )

        val host = parsed.directChildren(null).single()
        assertEquals("class", host.kind)
        assertEquals("Host", host.name)
        val members = parsed.directChildren(host.id)
        assertEquals(setOf("title", "init", "Host", "run", "Nested"), members.map { it.name }.toSet())
        val nested = members.single { it.name == "Nested" }
        assertEquals("child", parsed.directChildren(nested.id).single().name)
        assertTrue(parsed.declarations.none { it.name == "Pretend" || it.name == "nope" || it.name == "notAFunction" })
    }

    @Test
    fun javaStructureListsFieldsMethodsAndConstructors() {
        val parsed = SourceStructureParser.parse(
            """
            class Host {
                private String name;
                Host() {}
                void run(int id) { if (id > 0) { return; } }
                interface Worker { void work(); }
            }
            """.trimIndent(),
            isJavaFile = true,
        )

        val host = parsed.directChildren(null).single()
        val members = parsed.directChildren(host.id)
        assertEquals(setOf("name", "Host", "run", "Worker"), members.map { it.name }.toSet())
        assertEquals("field", members.single { it.name == "name" }.kind)
        assertEquals("constructor", members.single { it.name == "Host" }.kind)
        assertEquals("method", members.single { it.name == "run" }.kind)
    }
}
