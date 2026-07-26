package com.alarmcontrol.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.result.DataResult
import com.alarmcontrol.data.insights.InsightsHousekeeper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Periodic, fully on-device insights worker (CLAUDE.md §5). A thin shell — like the
 * `NotificationListenerService` — it just delegates to [InsightsHousekeeper] (which is unit-tested on
 * its own) and maps the result to a WorkManager outcome. No network is touched; the work is local
 * aggregation + retention cleanup + a DataStore write.
 */
@HiltWorker
class NotificationInsightsWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val housekeeper: InsightsHousekeeper,
        private val clock: Clock,
        @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            withContext(ioDispatcher) {
                when (insightWorkOutcome(housekeeper.run(clock.millis(), clock.zone), runAttemptCount)) {
                    InsightWorkOutcome.SUCCESS -> Result.success()
                    InsightWorkOutcome.RETRY -> Result.retry()
                    InsightWorkOutcome.FAILURE -> Result.failure()
                }
            }
    }

internal enum class InsightWorkOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

/** Pure WorkManager result policy kept separately testable from Android worker construction. */
internal fun insightWorkOutcome(
    result: DataResult<*>,
    runAttemptCount: Int,
): InsightWorkOutcome =
    when (result) {
        is DataResult.Success -> InsightWorkOutcome.SUCCESS
        is DataResult.Failure,
        DataResult.Loading,
        -> {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                InsightWorkOutcome.RETRY
            } else {
                InsightWorkOutcome.FAILURE
            }
        }
    }

private const val MAX_RETRY_ATTEMPTS = 3
