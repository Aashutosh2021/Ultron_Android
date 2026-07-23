package com.flexfly.ultron.voice

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sqrt

object AudioFeatureExtractor {
    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 512
    private const val WIN_LEN = 400 // 25ms window at 16kHz
    private const val HOP_LEN = 160 // 10ms hop at 16kHz
    private const val N_MELS = 80
    private const val F_MIN = 20.0
    private const val F_MAX = 8000.0

    // Pre-calculated Hann window
    private val hannWindow = FloatArray(WIN_LEN) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (WIN_LEN - 1)))).toFloat()
    }

    // Pre-calculated 80 Mel filterbank matrix (size: N_MELS x (N_FFT / 2 + 1))
    private val melFilters: Array<FloatArray> = createMelFilterbank()

    private fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    private fun createMelFilterbank(): Array<FloatArray> {
        val nBins = N_FFT / 2 + 1
        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melMin + i * (melMax - melMin) / (N_MELS + 1)
        }
        val hzPoints = DoubleArray(N_MELS + 2) { i -> melToHz(melPoints[i]) }
        val binPoints = IntArray(N_MELS + 2) { i ->
            floor((N_FFT + 1) * hzPoints[i] / SAMPLE_RATE).toInt().coerceIn(0, nBins - 1)
        }

        val filters = Array(N_MELS) { FloatArray(nBins) }
        for (m in 1..N_MELS) {
            val fMin = binPoints[m - 1]
            val fCenter = binPoints[m]
            val fMax = binPoints[m + 1]

            for (k in fMin until fCenter) {
                val denom = max(1, fCenter - fMin)
                filters[m - 1][k] = ((k - fMin).toDouble() / denom).toFloat()
            }
            for (k in fCenter until fMax) {
                val denom = max(1, fMax - fCenter)
                filters[m - 1][k] = ((fMax - k).toDouble() / denom).toFloat()
            }
        }
        return filters
    }

    /**
     * Converts 1D PCM audio floats into 2D Log-Mel Spectrogram features [num_frames, 80].
     */
    fun computeLogMelSpectrogram(pcm: FloatArray): Array<FloatArray> {
        if (pcm.size < WIN_LEN) {
            // Pad audio if too short
            val padded = FloatArray(WIN_LEN)
            System.arraycopy(pcm, 0, padded, 0, pcm.size)
            return computeLogMelSpectrogram(padded)
        }

        val numFrames = (pcm.size - WIN_LEN) / HOP_LEN + 1
        val logMelSpec = Array(numFrames) { FloatArray(N_MELS) }
        val real = FloatArray(N_FFT)
        val imag = FloatArray(N_FFT)

        for (t in 0 until numFrames) {
            val offset = t * HOP_LEN
            for (i in 0 until N_FFT) {
                if (i < WIN_LEN && offset + i < pcm.size) {
                    real[i] = pcm[offset + i] * hannWindow[i]
                } else {
                    real[i] = 0.0f
                }
                imag[i] = 0.0f
            }

            // Real Discrete Fourier Transform (DFT) for magnitude spectrum
            val nBins = N_FFT / 2 + 1
            val mag = FloatArray(nBins)
            for (k in 0 until nBins) {
                var rSum = 0.0f
                var iSum = 0.0f
                val angleFactor = -2.0 * PI * k / N_FFT
                for (n in 0 until WIN_LEN) {
                    val angle = angleFactor * n
                    val re = real[n]
                    rSum += (re * cos(angle)).toFloat()
                    iSum += (re * kotlin.math.sin(angle)).toFloat()
                }
                mag[k] = sqrt(rSum * rSum + iSum * iSum)
            }

            // Apply 80 Mel filters and take natural log
            for (m in 0 until N_MELS) {
                var melEnergy = 0.0f
                val filter = melFilters[m]
                for (k in 0 until nBins) {
                    melEnergy += mag[k] * filter[k]
                }
                logMelSpec[t][m] = ln(max(1e-6f, melEnergy))
            }
        }

        return logMelSpec
    }
}
