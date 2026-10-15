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

    // Wall-clock time until which queued PCM keeps the speaker busy. Each queued
    // chunk extends it by its real duration, so callers can know when Ultron's
    // voice has actually stopped coming out of the speaker (the server-side VAD
    // "turn complete" arrives while several seconds of audio are still draining).
    @Volatile
    private var tailUntilMs: Long = 0L

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
                // Generous internal buffer (16× min) to absorb network jitter without underrunning.
                .setBufferSizeInBytes(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 16
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
                            track2.write(bytes, 0, bytes.size)
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
            if (decode.isNotEmpty()) {
                // Each PCM chunk extends the tail by its real duration (sample count / rate).
                // 16-bit = 2 bytes/sample, 24kHz.
                val samples = decode.size / 2
                val durationMs = (samples * 1000L) / SAMPLE_RATE
                val now = System.currentTimeMillis()
                tailUntilMs = maxOf(now, tailUntilMs) + durationMs
                if (audioQueue.trySend(decode).isFailure) {
                    Log.w(TAG, "Queue full, chunk dropped")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    /**
     * Returns ms until the last queued audio chunk finishes playing. Zero if the
     * queue is empty/drained. Callers use this to keep the mic muted until Ultron's
     * voice has *actually* stopped coming out of the speaker (not just when the
     * server-side VAD says the turn is over).
     */
    fun remainingPlaybackMs(): Long {
        val now = System.currentTimeMillis()
        return maxOf(0L, tailUntilMs - now)
    }

    fun stopAndClear() {
        try {
            while (audioQueue.tryReceive().isSuccess) {
                // drain queued chunks
            }
            tailUntilMs = 0L
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
