package com.alarmcontrol.work

import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alarmcontrol.BuildConfig
import com.alarmcontrol.insightsBootstrapWorkName
import com.alarmcontrol.service.DeviceValidationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Android-runtime check for Hilt worker construction, execution, and its Room rollup output. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class NotificationInsightsWorkerInstrumentedTest {
    @Test
    fun bootstrapWorkCompletesAndStoresYesterdayRollup() =
        runBlocking {
            val context =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.applicationContext
            val workManager = WorkManager.getInstance(context)
            val workName = insightsBootstrapWorkName(BuildConfig.VERSION_CODE)
            val finalState = awaitFinishedState(workManager, workName)
            assertEquals(WorkInfo.State.SUCCEEDED, finalState)

            val entryPoint =
                EntryPointAccessors.fromApplication(
                    context,
                    DeviceValidationEntryPoint::class.java,
                )
            val rollups =
                withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
                    entryPoint.dailyInsightRepository().observeRecent(limit = 1).first { it.isNotEmpty() }
                }
            assertEquals(LocalDate.now().minusDays(1).toEpochDay(), rollups.single().epochDay)
            assertTrue(rollups.single().generatedAtMillis > 0L)
        }

    private suspend fun awaitFinishedState(
        workManager: WorkManager,
        workName: String,
    ): WorkInfo.State {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = AtomicReference<WorkInfo.State?>()
        val workInfos = workManager.getWorkInfosForUniqueWorkLiveData(workName)
        val observer = Observer<List<WorkInfo>> { infos -> state.set(infos.singleOrNull()?.state) }
        instrumentation.runOnMainSync { workInfos.observeForever(observer) }
        return try {
            withTimeout(WORK_TIMEOUT_MILLIS) {
                while (state.get()?.isFinished != true) delay(POLL_MILLIS)
                requireNotNull(state.get())
            }
        } finally {
            instrumentation.runOnMainSync { workInfos.removeObserver(observer) }
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val WORK_TIMEOUT_MILLIS = 20_000L
        const val REPOSITORY_TIMEOUT_MILLIS = 10_000L
    }
}
