package com.flexfly.ultron.android.action

/**
 * Extensible Android OS Framework Action Categories.
 */
sealed class AndroidActionCategory {
    data object System : AndroidActionCategory()
    data object Media : AndroidActionCategory()
    data object Communication : AndroidActionCategory()
    data object Device : AndroidActionCategory()
    data object Settings : AndroidActionCategory()
    data object Files : AndroidActionCategory()
    data object Sensors : AndroidActionCategory()
    data object Location : AndroidActionCategory()
    data object Network : AndroidActionCategory()
    data object Accessibility : AndroidActionCategory()
    data object Notification : AndroidActionCategory()
    data object Clipboard : AndroidActionCategory()
    data object Camera : AndroidActionCategory()
    data object Microphone : AndroidActionCategory()
    data class Custom(val name: String) : AndroidActionCategory()
}

/**
 * Android OS Feature Capability Descriptor.
 */
data class AndroidActionCapability(
    val name: String,
    val description: String = "",
)
