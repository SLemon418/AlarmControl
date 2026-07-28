package com.alarmcontrol.ml.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocabulary =
        listOf(
            "[PAD]",
            "[UNK]",
            "[CLS]",
            "[SEP]",
            "알림",
            "sale",
            "##s",
            "!",
            "中",
        )

    @Test
    fun `encodes word pieces punctuation and fixed padding`() {
        val tokenizer = WordPieceTokenizer(vocabulary, maxSequenceLength = 10)

        val encoded = requireNotNull(tokenizer.encode("알림 sales!"))

        assertArrayEquals(intArrayOf(2, 4, 5, 6, 7, 3, 0, 0, 0, 0), encoded.inputIds)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 0, 0, 0, 0), encoded.attentionMask)
        assertArrayEquals(IntArray(10), encoded.tokenTypeIds)
    }

    @Test
    fun `separates CJK and uses unknown for missing word`() {
        val tokenizer = WordPieceTokenizer(vocabulary, maxSequenceLength = 8)

        val encoded = requireNotNull(tokenizer.encode("中 missing"))

        assertArrayEquals(intArrayOf(2, 8, 1, 3, 0, 0, 0, 0), encoded.inputIds)
    }

    @Test
    fun `truncation always retains separator`() {
        val tokenizer = WordPieceTokenizer(vocabulary, maxSequenceLength = 4)

        val encoded = requireNotNull(tokenizer.encode("알림 알림 알림"))

        assertArrayEquals(intArrayOf(2, 4, 4, 3), encoded.inputIds)
        assertEquals(4, encoded.attentionMask.sum())
    }

    @Test
    fun `BERT clean text removes controls without splitting words`() {
        val tokenizer =
            WordPieceTokenizer(
                listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "sa", "##le", "le"),
                maxSequenceLength = 8,
            )
        val joined = intArrayOf(2, 4, 5, 3, 0, 0, 0, 0)
        val separated = intArrayOf(2, 4, 6, 3, 0, 0, 0, 0)

        listOf('\u0000', '\u0007', '\u0085', '\u200b', '\u202e', '\ufffd').forEach {
            assertArrayEquals(joined, requireNotNull(tokenizer.encode("sa${it}le")).inputIds)
        }
        listOf('\t', '\n', '\r', '\u00a0', '\u2028', '\u2029').forEach {
            assertArrayEquals(separated, requireNotNull(tokenizer.encode("sa${it}le")).inputIds)
        }
    }

    @Test
    fun `missing special token fails open`() {
        val tokenizer = WordPieceTokenizer(vocabulary.filterNot { it == "[SEP]" }, maxSequenceLength = 8)

        assertEquals(null, tokenizer.encode("알림"))
    }
}
