package com.alarmcontrol.ml.semantic

import com.alarmcontrol.core.feedback.AdFeedbackCounts
import com.alarmcontrol.core.filtering.SemanticIntent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositorySemanticFeedbackBlenderTest {
    private val labels = SemanticIntent.entries.toList()

    @Test
    fun `seven-way local votes form a normalized shrinkage prior`() {
        val counts =
            AdFeedbackCounts(
                byIntent =
                    mapOf(
                        SemanticIntent.MARKETING to 1,
                        SemanticIntent.SECURITY to 5,
                    ),
            )
        val blender =
            RepositorySemanticFeedbackBlender(
                MutableStateFlow(mapOf("com.example" to counts)),
                priorStrength = 3f,
            )
        val model = floatArrayOf(0.7f, 0.1f, 0.05f, 0.05f, 0.04f, 0.03f, 0.03f)

        val result = blender.blend("com.example", labels, model)

        assertEquals(1f, result.sum(), 0.0001f)
        assertTrue(result[SemanticIntent.SECURITY.ordinal] > result[SemanticIntent.MARKETING.ordinal])
    }

    @Test
    fun `missing feedback leaves probabilities unchanged`() {
        val blender =
            RepositorySemanticFeedbackBlender(
                MutableStateFlow(emptyMap()),
            )
        val model = floatArrayOf(0.7f, 0.1f, 0.05f, 0.05f, 0.04f, 0.03f, 0.03f)

        assertArrayEquals(model, blender.blend("com.example", labels, model), 0f)
    }
}
