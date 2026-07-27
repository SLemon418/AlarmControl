package com.alarmcontrol.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.ml.llm.LlmFailure
import com.alarmcontrol.ml.llm.LlmInitState
import com.alarmcontrol.service.DeviceValidationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the production Hilt LLM path fails open when no user-imported model exists. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LlmFallbackInstrumentedTest {
    @Test
    fun missingImportedModelBecomesUnavailableWithoutBlockingOrCrashing() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            assumeFalse(
                "A local LLM is installed; run the separate model latency validation instead",
                hasImportedLlmModel(context),
            )
            val manager =
                EntryPointAccessors
                    .fromApplication(
                        context,
                        DeviceValidationEntryPoint::class.java,
                    ).onDeviceLlmManager()

            manager.close()
            try {
                withTimeout(INIT_TIMEOUT_MILLIS) {
                    manager.initialize()
                }
                assertEquals(
                    LlmInitState.Unavailable(LlmFailure.MODEL_MISSING),
                    manager.initState.value,
                )
                assertNull(manager.analyze("Local fallback validation"))
            } finally {
                manager.close()
            }
        }

    private companion object {
        const val INIT_TIMEOUT_MILLIS = 10_000L
    }
}
