package com.alarmcontrol.ml.semantic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmcontrol.ml.MlConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the packaged seven-way encoder with the Android LiteRT runtime. */
@RunWith(AndroidJUnit4::class)
class SemanticBundledModelInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun realRuntimeLoadsAndProducesInputDependentFiniteLogits() {
        val loaded = loadSemanticAssets()
        val encoder =
            LiteRTSemanticEncoder(
                context = context,
                modelAsset = MlConfig.SEMANTIC_MODEL_ASSET,
                tokenizer =
                    WordPieceTokenizer(
                        vocabulary = loaded.vocabulary,
                        maxSequenceLength = loaded.maxSequenceLength,
                    ),
                maxSequenceLength = loaded.maxSequenceLength,
                outputSize = loaded.labels.size,
                expectedInputNames = loaded.inputNames,
                expectedModelSha256 = loaded.modelSha256,
                expectedModelSizeBytes = loaded.modelSizeBytes,
            )

        val marketing = encoder.encode("오늘만 50% 할인 쿠폰 혜택")
        val security = encoder.encode("새 로그인 인증 코드가 발급되었습니다")

        assertNotNull("Android LiteRT must run the semantic model", marketing)
        assertNotNull("Android LiteRT must accept a second input", security)
        assertEquals(7, marketing!!.size)
        assertEquals(7, security!!.size)
        assertTrue(marketing.all { value -> value.isFinite() })
        assertTrue(security.all { value -> value.isFinite() })
        assertFalse(
            "The converted graph must depend on token inputs",
            marketing.contentEquals(security),
        )
    }

    @Test
    fun realVocabularyMatchesPythonGoldenTokenIds() {
        val loaded = loadSemanticAssets()
        val tokenizer =
            WordPieceTokenizer(
                vocabulary = loaded.vocabulary,
                maxSequenceLength = loaded.maxSequenceLength,
            )

        assertGolden(
            tokenizer,
            text = "보안 코드가 발급되었습니다!",
            expected =
                intArrayOf(
                    2,
                    7896,
                    9519,
                    4070,
                    10600,
                    4479,
                    4480,
                    4576,
                    6216,
                    5,
                    3,
                ),
        )
        assertGolden(
            tokenizer,
            text = "flash sale, verified.",
            expected =
                intArrayOf(
                    2,
                    18716,
                    15756,
                    30569,
                    6976,
                    16,
                    30960,
                    9919,
                    30544,
                    18,
                    3,
                ),
        )
        assertGolden(
            tokenizer,
            text = "배송 status: ready 완료",
            expected =
                intArrayOf(
                    2,
                    11268,
                    9076,
                    6577,
                    7972,
                    30,
                    19192,
                    7794,
                    4121,
                    8637,
                    3,
                ),
        )
        assertGolden(
            tokenizer,
            text = "\u1107\u1169\u110B\u1161\u11AB 알림\u0000sale",
            expected = intArrayOf(2, 7896, 18898, 29483, 6976, 3),
        )
        assertGolden(
            tokenizer,
            text = "sa\u200ble sa\u202ele sa\u0007le",
            expected =
                intArrayOf(
                    2,
                    30569,
                    6976,
                    30569,
                    6976,
                    30569,
                    6976,
                    3,
                ),
        )

        val truncated =
            requireNotNull(
                tokenizer.encode(List(200) { "알림" }.joinToString(" ")),
            )
        assertEquals(128, truncated.inputIds.size)
        assertEquals(2, truncated.inputIds.first())
        assertTrue(
            truncated.inputIds
                .sliceArray(1 until 127)
                .all { token -> token == 18898 },
        )
        assertEquals(3, truncated.inputIds.last())
        assertEquals(128, truncated.attentionMask.sum())
    }

    private fun loadSemanticAssets(): LoadedSemanticModelAssets {
        val assets =
            SemanticModelAssets.load(
                context = context,
                manifestAsset = MlConfig.SEMANTIC_MODEL_MANIFEST_ASSET,
                modelAsset = MlConfig.SEMANTIC_MODEL_ASSET,
                vocabularyAsset = MlConfig.SEMANTIC_VOCAB_ASSET,
                labelsAsset = MlConfig.SEMANTIC_LABELS_ASSET,
            )
        assertNotNull("Semantic assets must pass their packaged manifest", assets)
        return requireNotNull(assets)
    }

    private fun assertGolden(
        tokenizer: WordPieceTokenizer,
        text: String,
        expected: IntArray,
    ) {
        val encoded = requireNotNull(tokenizer.encode(text))
        assertArrayEquals(
            expected,
            encoded.inputIds.copyOfRange(0, expected.size),
        )
        assertTrue(
            encoded.inputIds
                .copyOfRange(expected.size, encoded.inputIds.size)
                .all { token -> token == 0 },
        )
        assertEquals(expected.size, encoded.attentionMask.sum())
        assertTrue(encoded.tokenTypeIds.all { tokenType -> tokenType == 0 })
    }
}
