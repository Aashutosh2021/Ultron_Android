package com.flexfly.ultron.android.action

/**
 * Metadata descriptor for registered Android OS Actions.
 */
data class AndroidActionMetadata(
    val actionId: String,
    val name: String,
    val description: String,
    val minSdkVersion: Int = 21,
    val targetPermissions: List<String> = emptyList(),
)
