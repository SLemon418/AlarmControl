package com.alarmcontrol.ml.semantic

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Memory-mapped, bundled LiteRT encoder with fixed BERT-compatible integer inputs. */
internal class LiteRTSemanticEncoder(
    private val context: Context,
    private val modelAsset: String,
    private val tokenizer: WordPieceTokenizer,
    private val maxSequenceLength: Int,
    private val outputSize: Int,
    private val expectedInputNames: List<String>,
    private val expectedModelSha256: String,
    private val expectedModelSizeBytes: Long,
) : SemanticEncoder {
    private val session: Session? by lazy { loadSession() }
    private var disabled = false

    @Synchronized
    override fun encode(text: String): FloatArray? {
        if (disabled) return null
        val encoded = tokenizer.encode(text) ?: return null
        val loaded = session ?: return null
        return try {
            val inputs =
                Array<Any>(loaded.inputKinds.size) { index ->
                    when (loaded.inputKinds[index]) {
                        InputKind.IDS -> arrayOf(encoded.inputIds)
                        InputKind.MASK -> arrayOf(encoded.attentionMask)
                        InputKind.TYPES -> arrayOf(encoded.tokenTypeIds)
                    }
                }
            val output = Array(1) { FloatArray(outputSize) }
            loaded.interpreter.runForMultipleInputsOutputs(
                inputs,
                mutableMapOf<Int, Any>(0 to output),
            )
            output[0]
        } catch (_: LinkageError) {
            disable(loaded.interpreter)
            null
        } catch (_: OutOfMemoryError) {
            disable(loaded.interpreter)
            null
        } catch (_: Exception) {
            disable(loaded.interpreter)
            null
        }
    }

    private fun loadSession(): Session? {
        return try {
            if (!modelMatchesManifest()) return null
            context.assets.openFd(modelAsset).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val mapped =
                        stream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.declaredLength,
                        )
                    createSession(mapped)
                }
            }
        } catch (_: LinkageError) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun createSession(mapped: MappedByteBuffer): Session? {
        var candidate: Interpreter? = null
        return try {
            val interpreter =
                Interpreter(
                    mapped,
                    Interpreter.Options().setNumThreads(INFERENCE_THREADS),
                )
            candidate = interpreter
            val inputs = mutableListOf<Input>()
            for (index in 0 until interpreter.inputTensorCount) {
                val tensor = interpreter.getInputTensor(index)
                if (tensor.dataType() != DataType.INT32 ||
                    !tensor.shape().contentEquals(intArrayOf(1, maxSequenceLength))
                ) {
                    return null
                }
                val name = tensor.name().normalizedTensorName()
                val kind = name.toInputKind() ?: return null
                inputs += Input(name, kind)
            }
            if (interpreter.outputTensorCount != 1) return null
            val output = interpreter.getOutputTensor(0)
            val inputNames = inputs.map(Input::name)
            val outputMatches =
                output.dataType() == DataType.FLOAT32 &&
                    output.shape().contentEquals(intArrayOf(1, outputSize))
            val inputsMatch =
                inputNames.toSet() == expectedInputNames.toSet() &&
                    inputNames.size == expectedInputNames.size
            if (!outputMatches || !inputsMatch) {
                return null
            }
            Session(interpreter, inputs.map(Input::kind)).also {
                candidate = null
            }
        } finally {
            candidate?.closeSafely()
        }
    }

    private fun modelMatchesManifest(): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open(modelAsset).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                if (size > expectedModelSizeBytes) return false
                digest.update(buffer, 0, read)
            }
        }
        val actualHash =
            digest
                .digest()
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK)
                }
        return size == expectedModelSizeBytes && actualHash == expectedModelSha256
    }

    private fun String.normalizedTensorName(): String =
        substringBefore(':')
            .removePrefix("serving_default_")
            .removePrefix("main_")

    private fun String.toInputKind(): InputKind? =
        when (this) {
            "input_ids" -> InputKind.IDS
            "attention_mask" -> InputKind.MASK
            "token_type_ids" -> InputKind.TYPES
            else -> null
        }

    private fun disable(interpreter: Interpreter) {
        disabled = true
        interpreter.closeSafely()
    }

    private fun Interpreter.closeSafely() {
        try {
            close()
        } catch (_: LinkageError) {
            // Rule-only fallback is already active.
        } catch (_: OutOfMemoryError) {
            // Rule-only fallback is already active.
        } catch (_: Exception) {
            // Rule-only fallback is already active.
        }
    }

    private data class Session(
        val interpreter: Interpreter,
        val inputKinds: List<InputKind>,
    )

    private data class Input(
        val name: String,
        val kind: InputKind,
    )

    private enum class InputKind {
        IDS,
        MASK,
        TYPES,
    }

    private companion object {
        const val INFERENCE_THREADS = 2
        const val HASH_BUFFER_BYTES = 64 * 1_024
        const val UNSIGNED_BYTE_MASK = 0xFF
    }
}
