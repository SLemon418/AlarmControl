package com.alarmcontrol.ml

import com.alarmcontrol.core.filtering.NotificationSnapshot

/**
 * On-device notification categorizer (CLAUDE.md §5). This is the **only** public ML surface — the
 * runtime behind it (LiteRT today) can change without touching callers (§5 interface segregation).
 * Everything is local and bundled; no network, ever (§1/§3).
 *
 * `suspend` keeps inference off caller threads and leaves room for another local backend.
 */
interface NotificationClassifier {
    /**
     * Returns a confident categorization for [snapshot], or `null` when there is none — model
     * unavailable, confidence below threshold, empty input, or any failure. Callers treat `null`
     * as "no ML signal" and fall back to rule-only filtering (§5 graceful degradation).
     */
    suspend fun classify(snapshot: NotificationSnapshot): ClassificationResult?
}
