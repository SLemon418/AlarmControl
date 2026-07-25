package com.alarmcontrol.core.coroutines

import javax.inject.Qualifier

/**
 * Qualifies an injected [kotlinx.coroutines.CoroutineDispatcher] so call sites declare which
 * dispatcher they need and tests can swap in a deterministic one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Dispatcher(
    val type: AppDispatcher,
)

enum class AppDispatcher {
    Default,
    IO,
}
