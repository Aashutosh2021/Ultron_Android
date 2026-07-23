package com.flexfly.ultron.ai.tool

/**
 * Metadata descriptor for registered tools.
 */
data class ToolMetadata(
    val toolId: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "ULTRON Core",
)
