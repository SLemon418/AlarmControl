package com.alarmcontrol.ml.inference

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * LiteRT (TensorFlow Lite) backend that memory-maps a **bundled** model from assets and runs it —
 * never downloads anything (CLAUDE.md §1/§3). It expects a model with input shape `[1, vocabSize]`
 * and output shape `[1, outputSize]` (per-label scores).
 *
 * If the asset is missing or incompatible, [loadInterpreter] returns `null`, so classification
 * degrades to rule-only filtering (§5). Any run failure degrades the same way rather than crashing
 * the listener pipeline.
 */
internal class BundledTfLiteBackend(
    private val context: Context,
    private val modelAsset: String,
    private val outputSize: Int,
) : InferenceBackend {
    private val interpreter: Interpreter? by lazy { loadInterpreter() }
    private var disabled = false

    @Synchronized
    override fun run(features: FloatArray): FloatArray? {
        if (disabled) return null
        val model = interpreter ?: return null
        return try {
            val input = arrayOf(features)
            val output = Array(1) { FloatArray(outputSize) }
            model.run(input, output)
            output[0]
        } catch (_: LinkageError) {
            disable(model)
            null
        } catch (_: OutOfMemoryError) {
            disable(model)
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun loadInterpreter(): Interpreter? =
        try {
            context.assets.openFd(modelAsset).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val model =
                        stream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.declaredLength,
                        )
                    Interpreter(model)
                }
            }
        } catch (_: LinkageError) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }

    private fun disable(model: Interpreter) {
        disabled = true
        try {
            model.close()
        } catch (_: LinkageError) {
            // The backend is already disabled; native teardown failure must not escape.
        } catch (_: OutOfMemoryError) {
            // The backend is already disabled; retain rule-only fallback.
        } catch (_: Exception) {
            // The backend is already disabled; retain rule-only fallback.
        }
    }
}
