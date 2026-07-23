package com.flexfly.ultron.plugin.sdk

/**
 * Capability categories offered by plugins.
 */
sealed class PluginCapability {
    data object MusicPlayback : PluginCapability()
    data object Calendar : PluginCapability()
    data object Reminder : PluginCapability()
    data object Notes : PluginCapability()
    data object Messaging : PluginCapability()
    data object Navigation : PluginCapability()
    data object Automation : PluginCapability()
    data object SmartHome : PluginCapability()
    data object Vision : PluginCapability()
    data object Speech : PluginCapability()
    data object Search : PluginCapability()
    data class Custom(val name: String) : PluginCapability()
}

/**
 * Abstract permissions required by plugins.
 *
 * Does NOT directly map to Android permissions.
 */
sealed class PluginPermission {
    data object MediaAccess : PluginPermission()
    data object LocationAccess : PluginPermission()
    data object MemoryAccess : PluginPermission()
    data object AutomationAccess : PluginPermission()
    data object InternetAccess : PluginPermission()
    data object NotificationAccess : PluginPermission()
    data class CustomPermission(val name: String) : PluginPermission()
}
