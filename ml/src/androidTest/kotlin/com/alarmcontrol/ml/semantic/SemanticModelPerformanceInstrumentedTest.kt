package com.alarmcontrol.ml.semantic

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.ml.MlConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/** Opt-in physical-device benchmark for the exact bundled semantic model. */
@RunWith(AndroidJUnit4::class)
class SemanticModelPerformanceInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledModelMeetsNote20LatencyTargetAndReportsMemory() {
        assumeTrue(
            "Run manually with -e semanticPerf true",
            InstrumentationRegistry
                .getArguments()
                .getString(PERF_ARGUMENT)
                .toBoolean(),
        )
        val loaded =
            requireNotNull(
                SemanticModelAssets.load(
                    context = context,
                    manifestAsset = MlConfig.SEMANTIC_MODEL_MANIFEST_ASSET,
                    modelAsset = MlConfig.SEMANTIC_MODEL_ASSET,
                    vocabularyAsset = MlConfig.SEMANTIC_VOCAB_ASSET,
                    labelsAsset = MlConfig.SEMANTIC_LABELS_ASSET,
                ),
            )
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
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val before =
            DeviceSample(
                pssKilobytes = Debug.getPss(),
                rssKilobytes = currentRssKilobytes(),
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                thermalStatus = powerManager.currentThermalStatusOrNull(),
                chargeMicroAmpHours =
                    batteryManager?.getLongProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                    ),
            )

        val coldStarted = SystemClock.elapsedRealtimeNanos()
        val coldLogits = encoder.encode(INPUTS.first())
        val coldMillis =
            (SystemClock.elapsedRealtimeNanos() - coldStarted) /
                NANOS_PER_MILLISECOND
        assertNotNull("Cold semantic inference must succeed", coldLogits)

        repeat(WARMUP_RUNS) { index ->
            assertNotNull(encoder.encode(INPUTS[index % INPUTS.size]))
        }
        val latencyMillis =
            List(MEASURED_RUNS) { index ->
                val started = SystemClock.elapsedRealtimeNanos()
                val logits = encoder.encode(INPUTS[index % INPUTS.size])
                val elapsed =
                    (SystemClock.elapsedRealtimeNanos() - started) /
                        NANOS_PER_MILLISECOND
                assertNotNull("Warm semantic inference must succeed", logits)
                elapsed
            }
        val after =
            DeviceSample(
                pssKilobytes = Debug.getPss(),
                rssKilobytes = currentRssKilobytes(),
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                thermalStatus = powerManager.currentThermalStatusOrNull(),
                chargeMicroAmpHours =
                    batteryManager?.getLongProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                    ),
            )
        val p50Millis = latencyMillis.percentile(0.50)
        val p95Millis = latencyMillis.percentile(0.95)
        reportMetrics(
            coldMillis = coldMillis,
            p50Millis = p50Millis,
            p95Millis = p95Millis,
            before = before,
            after = after,
        )

        if (Build.MODEL == NOTE20_MODEL) {
            assertTrue(
                "Note20 semantic p95 must be <= $NOTE20_P95_TARGET_MILLIS ms; " +
                    "actual=$p95Millis ms",
                p95Millis <= NOTE20_P95_TARGET_MILLIS,
            )
        }
    }

    private fun reportMetrics(
        coldMillis: Double,
        p50Millis: Double,
        p95Millis: Double,
        before: DeviceSample,
        after: DeviceSample,
    ) {
        val serialized =
            buildString {
                append("{\"schema_version\":\"semantic-device-benchmark-v1\"")
                append(",\"device_model\":\"${Build.MODEL.jsonEscaped()}\"")
                append(",\"device_abi\":\"${Build.SUPPORTED_ABIS.firstOrNull().orEmpty().jsonEscaped()}\"")
                append(",\"runs\":$MEASURED_RUNS")
                append(",\"cold_millis\":$coldMillis")
                append(",\"p50_millis\":$p50Millis")
                append(",\"p95_millis\":$p95Millis")
                append(",\"before\":${before.toJson()}")
                append(",\"after\":${after.toJson()}")
                append("}")
            }
        InstrumentationRegistry
            .getInstrumentation()
            .sendStatus(
                STATUS_CODE,
                Bundle().apply {
                    putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$METRIC_PREFIX$serialized\n")
                },
            )
    }

    private fun List<Double>.percentile(fraction: Double): Double {
        val ordered = sorted()
        val index = (ceil(fraction * ordered.size).toInt() - 1).coerceAtLeast(0)
        return ordered[index]
    }

    private fun currentRssKilobytes(): Long? =
        runCatching {
            File("/proc/self/status")
                .useLines { lines ->
                    lines
                        .firstOrNull { line -> line.startsWith("VmRSS:") }
                        ?.split(WHITESPACE)
                        ?.getOrNull(1)
                        ?.toLong()
                }
        }.getOrNull()

    private fun String.jsonEscaped(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")

    private fun PowerManager?.currentThermalStatusOrNull(): Int? =
        if (this != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentThermalStatus
        } else {
            null
        }

    private data class DeviceSample(
        val pssKilobytes: Long,
        val rssKilobytes: Long?,
        val nativeHeapBytes: Long,
        val thermalStatus: Int?,
        val chargeMicroAmpHours: Long?,
    ) {
        fun toJson(): String =
            buildString {
                append("{\"pss_kilobytes\":$pssKilobytes")
                append(",\"rss_kilobytes\":${rssKilobytes ?: "null"}")
                append(",\"native_heap_bytes\":$nativeHeapBytes")
                append(",\"thermal_status\":${thermalStatus ?: "null"}")
                append(",\"charge_microamp_hours\":${chargeMicroAmpHours ?: "null"}")
                append("}")
            }
    }

    private object Instrumentation {
        const val REPORT_KEY_STREAMRESULT = "stream"
    }

    private companion object {
        const val PERF_ARGUMENT = "semanticPerf"
        const val METRIC_PREFIX = "SEMANTIC_PERF="
        const val STATUS_CODE = 0
        const val NOTE20_MODEL = "SM-N981N"
        const val NOTE20_P95_TARGET_MILLIS = 300.0
        const val WARMUP_RUNS = 3
        const val MEASURED_RUNS = 40
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        val WHITESPACE = Regex("\\s+")
        val INPUTS =
            listOf(
                "오늘만 적용되는 운동화 할인 쿠폰을 확인하세요",
                "새 기기 로그인 인증 코드가 발급되었습니다",
                "Your parcel is out for delivery this afternoon",
                "친구가 new running photo에 댓글을 남겼어요",
            )
    }
}
