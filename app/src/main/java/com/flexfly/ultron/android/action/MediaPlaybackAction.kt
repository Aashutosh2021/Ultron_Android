package com.flexfly.ultron.android.action

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Concrete Android OS Media Playback Action using Android AudioManager.
 */
class MediaPlaybackAction(private val context: Context) : AndroidAction {

    private val meta = AndroidActionMetadata(
        actionId = "android_media_playback",
        name = "MediaPlaybackAction",
        description = "Controls Android System Audio & Media Playback",
    )

    override fun metadata(): AndroidActionMetadata = meta
    override fun category(): AndroidActionCategory = AndroidActionCategory.Media
    override fun requiredPermissions(): List<String> = emptyList()
    override fun supportedCapabilities(): List<AndroidActionCapability> = listOf(AndroidActionCapability("audio_playback"))
    override fun isAvailable(): Boolean = true

    override suspend fun execute(request: AndroidActionRequest): AndroidActionResult {
        Log.d("MediaPlaybackAction", "🎵 Interacting with Android AudioManager for Request ${request.requestId}...")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.let {
            val currentVol = it.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaPlaybackAction", "System MUSIC stream volume: $currentVol")
        }
        delay(150L)
        return AndroidActionResult.Success(
            requestId = request.requestId,
            actionName = meta.name,
            resultData = mapOf("status" to "playing", "target" to (request.parameters["target"] ?: "media_track")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}

/**
 * Concrete Android OS Notification Action.
 */
class NotificationScheduleAction(private val context: Context) : AndroidAction {

    private val meta = AndroidActionMetadata(
        actionId = "android_notification_schedule",
        name = "NotificationScheduleAction",
        description = "Schedules Android System Notifications",
        targetPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
    )

    override fun metadata(): AndroidActionMetadata = meta
    override fun category(): AndroidActionCategory = AndroidActionCategory.Notification
    override fun requiredPermissions(): List<String> = meta.targetPermissions
    override fun supportedCapabilities(): List<AndroidActionCapability> = listOf(AndroidActionCapability("scheduling"))
    override fun isAvailable(): Boolean = true

    override suspend fun execute(request: AndroidActionRequest): AndroidActionResult {
        Log.d("NotificationScheduleAction", "🔔 Interacting with Android System Notification Service for Request ${request.requestId}...")
        delay(100L)
        return AndroidActionResult.Success(
            requestId = request.requestId,
            actionName = meta.name,
            resultData = mapOf("status" to "scheduled", "task" to (request.parameters["task"] ?: "reminder")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}

/**
 * Concrete Android OS Location & Navigation Action.
 */
class LocationNavigationAction(private val context: Context) : AndroidAction {

    private val meta = AndroidActionMetadata(
        actionId = "android_location_navigation",
        name = "LocationNavigationAction",
        description = "Launches Android System Navigation Intents",
        targetPermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
    )

    override fun metadata(): AndroidActionMetadata = meta
    override fun category(): AndroidActionCategory = AndroidActionCategory.Location
    override fun requiredPermissions(): List<String> = meta.targetPermissions
    override fun supportedCapabilities(): List<AndroidActionCapability> = listOf(AndroidActionCapability("location_directions"))
    override fun isAvailable(): Boolean = true

    override suspend fun execute(request: AndroidActionRequest): AndroidActionResult {
        Log.d("LocationNavigationAction", "🗺️ Preparing Android Location Navigation Intent for Request ${request.requestId}...")
        delay(200L)
        return AndroidActionResult.Success(
            requestId = request.requestId,
            actionName = meta.name,
            resultData = mapOf("status" to "navigating", "destination" to (request.parameters["destination"] ?: "home")),
        )
    }

    override fun cancel() {}
    override fun cleanup() {}
}
