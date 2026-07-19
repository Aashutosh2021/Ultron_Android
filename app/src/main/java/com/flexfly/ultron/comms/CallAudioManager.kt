package com.flexfly.ultron.comms

import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures 16 kHz mono PCM from the mic during a call, streams it out as base64
 * chunks via [onChunk], and fires [onUserSilenceTimeout] after the caller has been
 * quiet for [silenceTimeoutMs]. Muting suppresses both streaming and voice detection.
 *
 * Recovered from `CallAudioManager.kt` (folding in the `start`, `startReadLoop`, and
 * `startSilenceMonitor` coroutine state machines).
 *
 * RECOVERY NOTE: the decompiler emitted the raw AudioRecord/audio constants as their
 * integer values. Restored to named constants:
 *   16 == AudioFormat.CHANNEL_IN_MONO, 2 == AudioFormat.ENCODING_PCM_16BIT,
 *   1 == AudioRecord.STATE_INITIALIZED, 3 == AudioRecord.RECORDSTATE_RECORDING,
 *   Base64 flag 2 == Base64.NO_WRAP, and the audio sources 6/7/1 ==
 *   VOICE_COMMUNICATION / VOICE_RECOGNITION / MIC.
 */
class CallAudioManager(
    private val onChunk: (String) -> Unit,
    private val onUserSilenceTimeout: (() -> Unit)? = null,
    private val silenceTimeoutMs: Long = 5000L,
) {

    var isMuted: Boolean = false

    @Volatile
    private var isRecording: Boolean = false

    @Volatile
    private var timeoutFired: Boolean = false

    @Volatile
    private var lastVoiceDetectedAt: Long = 0L

    @Volatile
    private var activeListeningSince: Long = 0L

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var silenceMonitorJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun start(): Boolean {
        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording")
            return true
        }

        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // RECOVERY NOTE: 6
            MediaRecorder.AudioSource.VOICE_RECOGNITION,   // RECOVERY NOTE: 7
            MediaRecorder.AudioSource.MIC,                 // RECOVERY NOTE: 1
        )
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,  // RECOVERY NOTE: 16
                android.media.AudioFormat.ENCODING_PCM_16BIT, // RECOVERY NOTE: 2
            ),
            4096,
        )

        for (source in sourcesToTry) {
            var attempt = 0
            while (attempt < MAX_INIT_RETRIES) {
                attempt++
                try {
                    val rec = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        android.media.AudioFormat.CHANNEL_IN_MONO,   // RECOVERY NOTE: 16
                        android.media.AudioFormat.ENCODING_PCM_16BIT, // RECOVERY NOTE: 2
                        bufferSize * 4,
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) { // RECOVERY NOTE: getState() == 1
                        rec.startRecording()
                        delay(50L)
                        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { // RECOVERY NOTE: != 3
                            rec.release()
                        } else {
                            audioRecord = rec
                            isRecording = true
                            activeListeningSince = System.currentTimeMillis()
                            lastVoiceDetectedAt = 0L
                            timeoutFired = false
                            Log.d(TAG, "✅ Recording started (source=$source)")
                            startReadLoop(bufferSize)
                            startSilenceMonitor()
                            return true
                        }
                    } else {
                        rec.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Init exception ($source): ${e.message}")
                }
                delay(INIT_RETRY_DELAY_MS)
            }
        }

        Log.e(TAG, "❌ All sources failed")
        return false
    }

    private fun startReadLoop(bufferSize: Int) {
        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            var consecutiveErrors = 0
            while (isActive) {
                val rec = audioRecord
                if (isRecording && rec != null) {
                    val read = try {
                        rec.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Read error: ${e.message}")
                        -1
                    }
                    if (read > 0) {
                        if (!isMuted) {
                            var peak = 0
                            for (i in 0 until read) {
                                val abs = kotlin.math.abs(buffer[i].toInt())
                                if (abs > peak) peak = abs
                            }
                            if (peak > VOICE_AMPLITUDE_THRESHOLD) {
                                lastVoiceDetectedAt = System.currentTimeMillis()
                            }
                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                val j = i * 2
                                val s = buffer[i].toInt()
                                bytes[j] = (s and 0xFF).toByte()
                                bytes[j + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP) // RECOVERY NOTE: flag 2
                            onChunk(encoded)
                        }
                        consecutiveErrors = 0
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors > 10) {
                            Log.e(TAG, "❌ Too many read errors")
                            break
                        }
                    }
                }
                delay(20L)
            }
            Log.d(TAG, "🎤 Read loop exited")
        }
    }

    private fun startSilenceMonitor() {
        if (onUserSilenceTimeout == null) return
        silenceMonitorJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRecording) {
                if (!isMuted && !timeoutFired) {
                    val now = System.currentTimeMillis()
                    val activeFor = now - activeListeningSince
                    val noVoiceYet = lastVoiceDetectedAt == 0L
                    val silenceDuration = if (noVoiceYet) activeFor else now - lastVoiceDetectedAt
                    if (activeFor > 1000 && silenceDuration > silenceTimeoutMs) {
                        timeoutFired = true
                        Log.w(TAG, "⏱ User silence timeout — firing callback")
                        withContext(Dispatchers.Main) {
                            onUserSilenceTimeout.invoke()
                        }
                    }
                }
                delay(500L)
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        silenceMonitorJob?.cancel()
        silenceMonitorJob = null
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (ignored: Exception) {
        }
        audioRecord = null
        Log.d(TAG, "🎤 Recording stopped & released")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CallAudioManager"
        private const val SAMPLE_RATE = 16000
        private const val VOICE_AMPLITUDE_THRESHOLD = 1500
        private const val MAX_INIT_RETRIES = 3
        private const val INIT_RETRY_DELAY_MS = 150L
    }
}
