package com.flexfly.ultron.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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
            val created = AcousticEchoCanceler.create(audioSessionId)
            aec = created
            created?.enabled = true
            Log.d("AudioRecorder", "✅ AEC enabled")
        } else {
            Log.w("AudioRecorder", "⚠️ AEC not available on this device")
        }
        audioRecord?.startRecording()
        isRecording = true
        Log.d("AudioRecorder", "🎤 Continuous recording started")
        recordingJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRecording && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0 && !isMuted) {
                    val base64Audio = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
                    _audioChunks.emit(base64Audio)
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        aec?.release()
        aec = null
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
