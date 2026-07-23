package com.flexfly.ultron.android.action

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-Safe Dynamic Android OS Action Registry.
 */
class AndroidActionRegistry {

    companion object {
        private const val TAG = "AndroidActionRegistry"
    }

    private val actionsMap = ConcurrentHashMap<String, AndroidAction>()

    fun register(action: AndroidAction) {
        val meta = action.metadata()
        actionsMap[meta.name.lowercase()] = action
        actionsMap[meta.actionId.lowercase()] = action
        Log.d(TAG, "🤖 Registered Android OS Action: ${meta.name} (${meta.actionId})")
    }

    fun unregister(identifier: String) {
        val removed = actionsMap.remove(identifier.lowercase())
        if (removed != null) {
            Log.d(TAG, "🧹 Unregistered Android OS Action: $identifier")
        }
    }

    fun findByName(name: String): AndroidAction? {
        return actionsMap[name.lowercase()]
    }

    fun findByCategory(category: AndroidActionCategory): List<AndroidAction> {
        return actionsMap.values.distinctBy { it.metadata().actionId }.filter { it.category() == category }
    }

    fun findByCapability(capabilityName: String): List<AndroidAction> {
        return actionsMap.values.distinctBy { it.metadata().actionId }.filter { action ->
            action.supportedCapabilities().any { it.name.equals(capabilityName, ignoreCase = true) }
        }
    }

    fun getAllActions(): List<AndroidAction> {
        return actionsMap.values.distinctBy { it.metadata().actionId }
    }

    fun clear() {
        actionsMap.clear()
        Log.d(TAG, "🧹 Cleared AndroidActionRegistry")
    }
}
