package com.openlog.ai

/** A user-defined prompt template, invoked either as an Actions-panel button or as /name in the chat box. */
internal data class CustomAiCommand(val name: String, val promptTemplate: String)

internal object CustomAiCommandName {
    private val VALID = Regex("^[A-Za-z0-9_-]+$")

    fun isValid(name: String): Boolean = name.isNotBlank() && VALID.matches(name)
}
