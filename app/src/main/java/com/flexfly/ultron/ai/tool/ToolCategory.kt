package com.flexfly.ultron.ai.tool

/**
 * Extensible categories for ULTRON Tool Framework.
 */
sealed class ToolCategory {
    data object System : ToolCategory()
    data object Media : ToolCategory()
    data object Communication : ToolCategory()
    data object Navigation : ToolCategory()
    data object Files : ToolCategory()
    data object AI : ToolCategory()
    data object Automation : ToolCategory()
    data object Plugin : ToolCategory()
    data class Custom(val name: String) : ToolCategory()
}

/**
 * Feature capability descriptor for tools.
 */
data class ToolCapability(
    val name: String,
    val description: String = "",
)
