package com.alarmcontrol.core.coroutines

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] used for fire-and-forget work
 * that must outlive the component that started it (e.g. a trampoline activity that finishes
 * immediately). Never `GlobalScope` (CLAUDE.md §8).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
