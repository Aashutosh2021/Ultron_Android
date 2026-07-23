package com.flexfly.ultron.voice.speaker

import kotlin.math.sqrt

/**
 * Encapsulates a speaker embedding vector (e.g. 192-dimensional FloatArray).
 *
 * Provides vector normalization and cosine similarity computation.
 */
data class SpeakerEmbedding(val vector: FloatArray) {

    val dimension: Int get() = vector.size

    /**
     * Calculates the Cosine Similarity between this embedding and [other].
     * Returns a float value in `[-1.0, 1.0]`. Higher scores indicate higher speaker identity match.
     */
    fun cosineSimilarity(other: SpeakerEmbedding): Float {
        if (vector.size != other.vector.size || vector.isEmpty()) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in vector.indices) {
            val a = vector[i]
            val b = other.vector[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        if (normA <= 0.0f || normB <= 0.0f) return 0.0f

        val similarity = dotProduct / (sqrt(normA) * sqrt(normB))
        return similarity.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Returns an L2-normalized copy of this embedding vector.
     */
    fun normalize(): SpeakerEmbedding {
        var normSquare = 0.0f
        for (v in vector) {
            normSquare += v * v
        }
        val norm = sqrt(normSquare)
        if (norm <= 0.00001f) return this

        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / norm
        }
        return SpeakerEmbedding(normalized)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerEmbedding) return false
        return vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        return vector.contentHashCode()
    }

    companion object {
        /**
         * Calculates the L2-normalized centroid (mean) embedding profile from multiple enrollment samples.
         */
        fun computeCentroid(embeddings: List<SpeakerEmbedding>): SpeakerEmbedding {
            if (embeddings.isEmpty()) return SpeakerEmbedding(FloatArray(192))
            val dim = embeddings.first().dimension
            val meanVector = FloatArray(dim)

            for (emb in embeddings) {
                for (i in 0 until dim) {
                    if (i < emb.vector.size) {
                        meanVector[i] += emb.vector[i]
                    }
                }
            }

            val count = embeddings.size.toFloat()
            for (i in 0 until dim) {
                meanVector[i] /= count
            }

            return SpeakerEmbedding(meanVector).normalize()
        }
    }
}
