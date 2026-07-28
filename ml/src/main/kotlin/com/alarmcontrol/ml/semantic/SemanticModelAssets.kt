package com.alarmcontrol.ml.semantic

import android.content.Context
import com.alarmcontrol.core.filtering.SemanticIntent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Loads and verifies the small sidecars before exposing a lazily loaded semantic encoder. */
internal object SemanticModelAssets {
    fun load(
        context: Context,
        manifestAsset: String,
        modelAsset: String,
        vocabularyAsset: String,
        labelsAsset: String,
    ): LoadedSemanticModelAssets? {
        return try {
            val manifestBytes =
                context.readBoundedAsset(
                    name = manifestAsset,
                    maximumBytes = MAX_MANIFEST_BYTES,
                )
            val manifest =
                SemanticModelManifest.parse(manifestBytes.decodeUtf8Strict())
                    ?: return null
            val vocabularyBytes =
                context.readBoundedAsset(
                    name = vocabularyAsset,
                    maximumBytes = manifest.vocabulary.sizeBytes,
                    expectedBytes = manifest.vocabulary.sizeBytes,
                )
            val labelsBytes =
                context.readBoundedAsset(
                    name = labelsAsset,
                    maximumBytes = manifest.labelsAsset.sizeBytes,
                    expectedBytes = manifest.labelsAsset.sizeBytes,
                )
            if (!vocabularyBytes.matches(manifest.vocabulary) ||
                !labelsBytes.matches(manifest.labelsAsset)
            ) {
                return null
            }
            val vocabulary = vocabularyBytes.decodeLinesStrict()
            val labels =
                labelsBytes
                    .decodeLinesStrict()
                    .map(SemanticIntent::valueOf)
            if (vocabulary.size != vocabulary.toSet().size ||
                !vocabulary.containsAll(SPECIAL_TOKENS) ||
                labels != manifest.labels
            ) {
                return null
            }
            val modelLength =
                context.assets.openFd(modelAsset).use { descriptor ->
                    descriptor.declaredLength
                }
            if (modelLength != manifest.model.sizeBytes) return null
            LoadedSemanticModelAssets(
                vocabulary = vocabulary,
                labels = labels,
                maxSequenceLength = manifest.maxSequenceLength,
                confidenceThresholds = manifest.confidenceThresholds,
                inputNames = manifest.inputNames,
                modelSha256 = manifest.model.sha256,
                modelSizeBytes = manifest.model.sizeBytes,
            )
        } catch (_: LinkageError) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun Context.readBoundedAsset(
        name: String,
        maximumBytes: Long,
        expectedBytes: Long? = null,
    ): ByteArray {
        require(maximumBytes in 1..MAX_SIDECAR_BYTES)
        require(expectedBytes == null || expectedBytes in 1..maximumBytes)
        assets.open(name).use { input ->
            val output =
                ByteArrayOutputStream(
                    minOf(maximumBytes, INITIAL_BUFFER_BYTES.toLong()).toInt(),
                )
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maximumBytes)
                output.write(buffer, 0, read)
            }
            require(expectedBytes == null || total == expectedBytes)
            return output.toByteArray()
        }
    }

    private fun ByteArray.matches(contract: SemanticAssetContract): Boolean =
        size.toLong() == contract.sizeBytes &&
            sha256() == contract.sha256

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK)
            }

    private fun ByteArray.decodeUtf8Strict(): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()

    private fun ByteArray.decodeLinesStrict(): List<String> {
        val normalized = decodeUtf8Strict().replace("\r\n", "\n")
        val lines = normalized.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
        require(lines.isNotEmpty() && lines.none(String::isEmpty))
        return lines
    }

    private const val MAX_MANIFEST_BYTES = 64L * 1_024
    private const val MAX_SIDECAR_BYTES = 5L * 1_024 * 1_024
    private const val BUFFER_BYTES = 16 * 1_024
    private const val INITIAL_BUFFER_BYTES = 64 * 1_024
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private val SPECIAL_TOKENS = setOf("[PAD]", "[UNK]", "[CLS]", "[SEP]")
}

internal data class LoadedSemanticModelAssets(
    val vocabulary: List<String>,
    val labels: List<SemanticIntent>,
    val maxSequenceLength: Int,
    val confidenceThresholds: SemanticConfidenceThresholds,
    val inputNames: List<String>,
    val modelSha256: String,
    val modelSizeBytes: Long,
)
