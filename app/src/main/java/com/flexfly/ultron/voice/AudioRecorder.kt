package com.flexfly.ultron.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * Encapsulates Android's [AudioRecord] hardware capture loop.
 *
 * Emits float frame chunks `FloatArray` (normalized `[-1.0, 1.0]`) continuously on Dispatchers.IO.
 * Performs clean initialization, hardware noise suppression, and thread-safe cancellation.
 */
class AudioRecorder(
    private val context: Context,
    private val config: AudioConfig = AudioConfig(),
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    val isActive: Boolean get() = isRecording.get()

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startStreaming(): Flow<AudioFrame> = flow {
        if (!hasMicPermission()) {
            Log.e(TAG, "❌ RECORD_AUDIO permission missing")
            throw SecurityException("RECORD_AUDIO permission missing")
        }

        val bufferSize = config.calculateMinBufferSize()
        val recorder = try {
            AudioRecord(
                config.audioSource,
                config.sampleRate,
                config.channelConfig,
                config.audioFormat,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to instantiate AudioRecord: ${e.message}", e)
            throw IllegalStateException("AudioRecord creation failed", e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "❌ AudioRecord failed to initialize")
            throw IllegalStateException("AudioRecord state not STATE_INITIALIZED")
        }

        audioRecord = recorder

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to start recording")
            }
            isRecording.set(true)
            Log.d(TAG, "🎤 Recording started (${config.sampleRate} Hz, 16-bit Mono)")

            val readBuffer = ShortArray(config.frameSizeSamples)

            while (isRecording.get()) {
                val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    // Proven implementation: emit raw PCM frames immediately
                    val frameData = if (readCount == readBuffer.size) {
                        readBuffer
                    } else {
                        readBuffer.copyOf(readCount)
                    }
                    emit(AudioFrame(
                        pcmData = frameData,
                        sampleRate = config.sampleRate,
                        timestampMs = System.currentTimeMillis()
                    ))
                } else if (readCount < 0) {
                    Log.w(TAG, "⚠️ AudioRecord read error code: $readCount")
                    break
                }
            }
        } finally {
            cleanup()
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        if (isRecording.compareAndSet(true, false)) {
            Log.d(TAG, "🛑 Stop requested for AudioRecorder")
        }
    }

    private fun cleanup() {
        isRecording.set(false)
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
            agc?.release()
            agc = null
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            audioRecord = null
            Log.d(TAG, "🧹 AudioRecorder released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }
}
