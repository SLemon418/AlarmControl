package com.alarmcontrol.service

import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.notifications.CompiledRuleSet
import com.alarmcontrol.notifications.Matcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/**
 * Re-subscribes to the rule source for every refresh id, forcing a post-commit Room query even when
 * the preceding invalidation emission raced ahead of the refresh request.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun collectCompiledRuleRefreshes(
    refreshRequests: Flow<Long>,
    observeRules: () -> Flow<List<Rule>>,
    matcher: Matcher,
    publish: (requestId: Long, compiled: CompiledRuleSet) -> Unit,
    onFailure: (requestId: Long) -> Unit = {},
    retryDelayMillis: (attempt: Int) -> Long = ::ruleRefreshRetryDelayMillis,
) {
    refreshRequests.collectLatest { requestId ->
        var attempt = 0
        while (true) {
            try {
                observeRules().collect { rules ->
                    publish(requestId, matcher.compile(rules))
                    attempt = 0
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onFailure(requestId)
                delay(retryDelayMillis(attempt))
                attempt = (attempt + 1).coerceAtMost(MAX_RULE_REFRESH_BACKOFF_STEP)
                continue
            }
            // A normally completed Room source is also unusable until it is re-subscribed.
            onFailure(requestId)
            delay(retryDelayMillis(attempt))
            attempt = (attempt + 1).coerceAtMost(MAX_RULE_REFRESH_BACKOFF_STEP)
        }
    }
}

internal fun ruleRefreshRetryDelayMillis(attempt: Int): Long {
    val boundedStep = attempt.coerceIn(0, MAX_RULE_REFRESH_BACKOFF_STEP)
    return (INITIAL_RULE_REFRESH_RETRY_MILLIS shl boundedStep)
        .coerceAtMost(MAX_RULE_REFRESH_RETRY_MILLIS)
}

private const val INITIAL_RULE_REFRESH_RETRY_MILLIS = 500L
private const val MAX_RULE_REFRESH_RETRY_MILLIS = 30_000L
private const val MAX_RULE_REFRESH_BACKOFF_STEP = 6
