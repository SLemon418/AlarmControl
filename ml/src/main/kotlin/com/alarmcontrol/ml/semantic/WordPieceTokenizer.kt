package com.alarmcontrol.ml.semantic

import java.text.Normalizer

/** Fixed-shape BERT/ ELECTRA WordPiece input produced entirely on-device. */
internal class WordPieceTokenizer(
    vocabulary: List<String>,
    private val maxSequenceLength: Int,
    private val lowercase: Boolean = false,
) {
    private val tokenIds = vocabulary.withIndex().associate { (index, token) -> token to index }
    private val paddingId = tokenIds[PAD_TOKEN]
    private val unknownId = tokenIds[UNKNOWN_TOKEN]
    private val classificationId = tokenIds[CLASSIFICATION_TOKEN]
    private val separatorId = tokenIds[SEPARATOR_TOKEN]

    fun encode(text: String): EncodedSemanticInput? {
        val pad = paddingId ?: return null
        val unknown = unknownId ?: return null
        val classification = classificationId ?: return null
        val separator = separatorId ?: return null
        if (maxSequenceLength < MIN_SEQUENCE_LENGTH) return null

        val contentIds = mutableListOf<Int>()
        for (token in basicTokenize(text)) {
            for (piece in wordPieces(token, unknown)) {
                if (contentIds.size >= maxSequenceLength - SPECIAL_TOKEN_COUNT) break
                contentIds += piece
            }
            if (contentIds.size >= maxSequenceLength - SPECIAL_TOKEN_COUNT) break
        }

        val ids = IntArray(maxSequenceLength) { pad }
        val mask = IntArray(maxSequenceLength)
        val tokenTypes = IntArray(maxSequenceLength)
        val sequence = listOf(classification) + contentIds + separator
        sequence.forEachIndexed { index, id ->
            ids[index] = id
            mask[index] = 1
        }
        return EncodedSemanticInput(ids, mask, tokenTypes)
    }

    private fun basicTokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                val value = current.toString().let { if (lowercase) it.lowercase() else it }
                tokens += value
                current.clear()
            }
        }

        normalized.forEach { character ->
            when {
                character.isBertWhitespace() -> flush()
                character.isBertRemovedCharacter() -> Unit
                character.isPunctuation() || character.isCjkCharacter() -> {
                    flush()
                    tokens += character.toString()
                }
                else -> current.append(character)
            }
        }
        flush()
        return tokens
    }

    private fun wordPieces(
        token: String,
        unknown: Int,
    ): List<Int> {
        tokenIds[token]?.let { return listOf(it) }
        if (token.length > MAX_WORD_CHARACTERS) return listOf(unknown)

        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            val match = longestMatch(token, start) ?: return listOf(unknown)
            pieces += match.id
            start = match.end
        }
        return pieces
    }

    private fun longestMatch(
        token: String,
        start: Int,
    ): WordPieceMatch? {
        var end = token.length
        while (start < end) {
            val candidate =
                token.substring(start, end).let {
                    if (start == 0) it else CONTINUATION_PREFIX + it
                }
            tokenIds[candidate]?.let { return WordPieceMatch(id = it, end = end) }
            end -= 1
        }
        return null
    }

    private fun Char.isBertWhitespace(): Boolean =
        this == ' ' ||
            this == '\t' ||
            this == '\n' ||
            this == '\r' ||
            when (Character.getType(this)) {
                Character.SPACE_SEPARATOR.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> true
                else -> false
            }

    private fun Char.isBertRemovedCharacter(): Boolean =
        code == 0 ||
            code == REPLACEMENT_CHARACTER ||
            when (Character.getType(this)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                -> true
                else -> false
            }

    private fun Char.isPunctuation(): Boolean =
        when (Character.getType(this)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }

    private fun Char.isCjkCharacter(): Boolean {
        val code = code
        return code in cjkUnifiedRange ||
            code in cjkExtensionARange ||
            code in cjkCompatibilityRange
    }

    private companion object {
        const val PAD_TOKEN = "[PAD]"
        const val UNKNOWN_TOKEN = "[UNK]"
        const val CLASSIFICATION_TOKEN = "[CLS]"
        const val SEPARATOR_TOKEN = "[SEP]"
        const val CONTINUATION_PREFIX = "##"
        const val SPECIAL_TOKEN_COUNT = 2
        const val MIN_SEQUENCE_LENGTH = 4
        const val MAX_WORD_CHARACTERS = 100
        const val REPLACEMENT_CHARACTER = 0xFFFD
        val cjkUnifiedRange = 0x4E00..0x9FFF
        val cjkExtensionARange = 0x3400..0x4DBF
        val cjkCompatibilityRange = 0xF900..0xFAFF
    }
}

private data class WordPieceMatch(
    val id: Int,
    val end: Int,
)

internal data class EncodedSemanticInput(
    val inputIds: IntArray,
    val attentionMask: IntArray,
    val tokenTypeIds: IntArray,
)
