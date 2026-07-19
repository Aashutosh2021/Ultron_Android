package com.flexfly.ultron.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class AudioPlayerManager {

    private var audioTrack: AudioTrack? = null
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize() {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 8
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track.play()
            Log.d(TAG, "✅ AudioTrack initialized & playing")
            scope.launch(Dispatchers.IO) {
                for (bytes in audioQueue) {
                    try {
                        val track2 = audioTrack
                        if (track2 != null && track2.state == AudioTrack.STATE_INITIALIZED) {
                            audioTrack?.write(bytes, 0, bytes.size)
                            Log.d(TAG, "🔊 Wrote ${bytes.size} bytes")
                        } else {
                            Log.w(TAG, "AudioTrack not ready, skipping chunk")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Write error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
        }
    }

    fun playAudioChunk(base64Audio: String) {
        try {
            val decode = Base64.decode(base64Audio, Base64.NO_WRAP)
            if (decode.isNotEmpty() && audioQueue.trySend(decode).isFailure) {
                Log.w(TAG, "Queue full, chunk dropped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    fun stopAndClear() {
        try {
            while (audioQueue.tryReceive().isSuccess) {
                // drain queued chunks
            }
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
    }

    fun release() {
        try {
            audioQueue.close()
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing: ${e.message}")
        }
    }

    companion object {
        // RECOVERY NOTE: constants restored — AUDIO_FORMAT 2 = ENCODING_PCM_16BIT,
        // CHANNEL_CONFIG 4 = CHANNEL_OUT_MONO.
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val SAMPLE_RATE = 24000
        private const val TAG = "AudioPlayer"
    }
}
