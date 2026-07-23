package com.flexfly.ultron.voice.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Non-blocking coroutine timer tracking silence intervals and session timeouts.
 *
 * Guarantees that short pauses do not terminate the session, while enforcing
 * strict idle teardowns to save battery and resources.
 */
class SilenceDetector(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "SilenceDetector"
    }

    private var timeoutJob: Job? = null

    fun startTimer(timeoutMs: Long, reason: String, onTimeout: suspend () -> Unit) {
        stopTimer()
        timeoutJob = scope.launch(Dispatchers.Default) {
            Log.d(TAG, "⏱️ Silence timer started ($reason): ${timeoutMs}ms")
            delay(timeoutMs)
            Log.d(TAG, "⏰ Silence timer expired ($reason)")
            onTimeout()
        }
    }

    fun stopTimer() {
        if (timeoutJob?.isActive == true) {
            timeoutJob?.cancel()
            timeoutJob = null
            Log.d(TAG, "⏹️ Silence timer cancelled")
        }
    }

    val isActive: Boolean get() = timeoutJob?.isActive == true
}
