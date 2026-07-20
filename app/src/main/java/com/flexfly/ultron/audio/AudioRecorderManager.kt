package com.flexfly.ultron.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class AudioRecorderManager {

    private val _audioChunks = MutableSharedFlow<String>(0, 200)
    val audioChunks: SharedFlow<String> = _audioChunks

    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    var isMuted: Boolean = false

    private var isRecording: Boolean = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startContinuousRecording() {
        if (isRecording) {
            return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT), 16384)
        )
        audioRecord = record
        val audioSessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            Log.d("AudioRecorder", "✅ AEC enabled")
        } else {
            Log.w("AudioRecorder", "⚠️ AEC not available on this device")
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            Log.d("AudioRecorder", "✅ AGC enabled")
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            Log.d("AudioRecorder", "✅ NS enabled")
        }
        audioRecord?.startRecording()
        isRecording = true
        Log.d("AudioRecorder", "🎤 Continuous recording started")
        recordingJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRecording && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0 && !isMuted) {
                    applyDigitalGain(buffer, bytesRead, 2.5f)
                    val base64Audio = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
                    _audioChunks.emit(base64Audio)
                }
            }
        }
    }

    private fun applyDigitalGain(buffer: ByteArray, bytesRead: Int, gainFactor: Float) {
        if (gainFactor == 1.0f) return
        for (i in 0 until bytesRead step 2) {
            if (i + 1 >= bytesRead) break
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            val boosted = (sample * gainFactor).toInt()
            val clamped = maxOf(Short.MIN_VALUE.toInt(), minOf(Short.MAX_VALUE.toInt(), boosted))
            buffer[i] = (clamped and 0xFF).toByte()
            buffer[i + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        aec?.release()
        aec = null
        agc?.release()
        agc = null
        ns?.release()
        ns = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stopRecording()
        scope.cancel()
    }

    companion object {
        // RECOVERY NOTE: constants restored — AUDIO_FORMAT 2 = ENCODING_PCM_16BIT,
        // CHANNEL_CONFIG 16 = CHANNEL_IN_MONO. AudioSource 7 = VOICE_COMMUNICATION.
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val CHUNK_SIZE = 4096
        const val SAMPLE_RATE = 16000
    }
}
