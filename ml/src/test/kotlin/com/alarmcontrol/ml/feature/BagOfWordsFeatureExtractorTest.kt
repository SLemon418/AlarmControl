package com.alarmcontrol.ml.feature

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BagOfWordsFeatureExtractorTest {
    private val extractor = BagOfWordsFeatureExtractor(listOf("sale", "off", "friend"))

    @Test
    fun `counts vocabulary tokens case-insensitively and ignores punctuation`() {
        assertArrayEquals(floatArrayOf(1f, 1f, 0f), extractor.extract("Big SALE, 50% off!"), 0f)
    }

    @Test
    fun `repeated tokens accumulate and out-of-vocabulary words are ignored`() {
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), extractor.extract("sale sale spaceship"), 0f)
    }

    @Test
    fun `blank text yields a zero vector`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), extractor.extract("   "), 0f)
    }

    @Test
    fun `unicode tokenizer retains Korean words`() {
        val koreanExtractor = BagOfWordsFeatureExtractor(listOf("할인", "배송", "완료"))

        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f),
            koreanExtractor.extract("오늘만 할인! 배송 완료"),
            0f,
        )
    }
}
