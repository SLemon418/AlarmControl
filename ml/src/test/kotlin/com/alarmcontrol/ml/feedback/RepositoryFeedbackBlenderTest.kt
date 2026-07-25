package com.alarmcontrol.ml.feedback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryFeedbackBlenderTest {
    private val labels = listOf("promotion", "social", "news", "alarm")

    private fun blender(
        counts: Map<String, Int>,
        strength: Float = 3f,
    ): RepositoryFeedbackBlender =
        RepositoryFeedbackBlender(
            countsByPackage = MutableStateFlow(mapOf("pkg" to counts)),
            priorStrength = strength,
        )

    @Test
    fun `no feedback leaves scores unchanged`() =
        runTest {
            val scores = floatArrayOf(0.7f, 0.2f, 0.1f, 0f)
            val blended = blender(counts = emptyMap()).blend("pkg", labels, scores)
            assertArrayEquals(scores, blended, 0f)
        }

    @Test
    fun `strong consistent feedback flips the argmax`() =
        runTest {
            val scores = floatArrayOf(0.7f, 0.2f, 0.1f, 0f) // model favors promotion (index 0)
            val blended = blender(counts = mapOf("social" to 20)).blend("pkg", labels, scores)
            assertEquals(1, blended.indices.maxByOrNull { blended[it] }) // social (index 1) wins
        }

    @Test
    fun `a single correction does not override a confident model`() =
        runTest {
            val scores = floatArrayOf(0.9f, 0.05f, 0.05f, 0f)
            val blended = blender(counts = mapOf("social" to 1)).blend("pkg", labels, scores)
            assertEquals(0, blended.indices.maxByOrNull { blended[it] }) // still promotion
        }

    @Test
    fun `blended scores remain a probability distribution`() =
        runTest {
            val scores = floatArrayOf(0.7f, 0.2f, 0.1f, 0f)
            val blended = blender(counts = mapOf("social" to 5, "news" to 2)).blend("pkg", labels, scores)
            assertEquals(1f, blended.sum(), 1e-4f)
        }

    @Test
    fun `a scores-labels size mismatch is returned unchanged rather than crashing`() =
        runTest {
            // Fewer labels than model outputs must not index out of bounds (graceful degradation, §5).
            val scores = floatArrayOf(0.5f, 0.3f, 0.2f)
            val blended = blender(counts = mapOf("social" to 20)).blend("pkg", labels, scores)
            assertArrayEquals(scores, blended, 0f)
        }
}
