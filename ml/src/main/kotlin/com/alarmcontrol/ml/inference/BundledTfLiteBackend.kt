package com.alarmcontrol.ml.inference

import android.content.Context
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
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

    @Synchronized
    override fun run(features: FloatArray): FloatArray? {
        val model = interpreter ?: return null
        return try {
            val input = arrayOf(features)
            val output = Array(1) { FloatArray(outputSize) }
            model.run(input, output)
            output[0]
        } catch (_: Exception) {
            null
        }
    }

    private fun loadInterpreter(): Interpreter? =
        runCatchingPreservingCancellation {
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
        }.getOrNull()
}
