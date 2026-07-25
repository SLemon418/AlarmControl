package com.alarmcontrol.ml.feature

import java.util.Locale

/** Turns notification text into the numeric features a model consumes. Pure and deterministic (§5/§9). */
internal interface FeatureExtractor {
    fun extract(text: String): FloatArray
}

/**
 * Baseline bag-of-words extractor: locale-independent lowercase, tokenize on non-letter/digit
 * boundaries, and count occurrences of each [vocabulary] term. Unicode letters are retained, so
 * Korean and other non-Latin notification text is not erased before model inference.
 */
internal class BagOfWordsFeatureExtractor(
    private val vocabulary: List<String>,
) : FeatureExtractor {
    private val indexByToken: Map<String, Int> =
        vocabulary.withIndex().associate { (index, token) -> token to index }

    override fun extract(text: String): FloatArray {
        val counts = FloatArray(vocabulary.size)
        for (token in tokenize(text)) {
            indexByToken[token]?.let { counts[it] += 1f }
        }
        return counts
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(TOKEN_DELIMITER).filter { it.isNotEmpty() }

    private companion object {
        val TOKEN_DELIMITER = Regex("[^\\p{L}\\p{N}]+")
    }
}
