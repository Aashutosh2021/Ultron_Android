package com.flexfly.ultron.android.action

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Dedicated Permission Abstraction Gateway checking Android OS system permission status.
 */
class AndroidPermissionGateway(private val context: Context) {

    companion object {
        private const val TAG = "AndroidPermissionGateway"
    }

    fun hasPermission(permission: String): Boolean {
        val status = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "🔑 Permission Gateway check '$permission': ${if (status) "GRANTED" else "DENIED"}")
        return status
    }

    fun hasAllPermissions(permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true
        return permissions.all { hasPermission(it) }
    }

    fun getMissingPermissions(permissions: List<String>): List<String> {
        return permissions.filter { !hasPermission(it) }
    }
}
