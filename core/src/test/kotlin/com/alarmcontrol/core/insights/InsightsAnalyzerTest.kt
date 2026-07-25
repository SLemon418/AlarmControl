package com.alarmcontrol.core.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsAnalyzerTest {
    private fun analyze(
        recent: Map<String, Int>,
        baseline: Map<String, Int> = emptyMap(),
    ) = InsightsAnalyzer.analyze(
        recentCounts = recent,
        baselineCounts = baseline,
        windowDays = 7,
        topN = 3,
        anomalyMinEvents = 5,
        anomalySpikeFactor = 2,
    )

    @Test
    fun `ranks most-muted apps descending and limits to topN`() {
        val report = analyze(mapOf("a" to 2, "b" to 9, "c" to 5, "d" to 7))

        assertEquals(listOf("b", "d", "c"), report.topMutedApps.map { it.packageName })
        assertEquals(23, report.totalEvents)
    }

    @Test
    fun `breaks count ties by package name`() {
        val report = analyze(mapOf("zebra" to 4, "alpha" to 4))

        assertEquals(listOf("alpha", "zebra"), report.topMutedApps.map { it.packageName })
    }

    @Test
    fun `flags an anomaly when recent volume spikes over the baseline`() {
        val report = analyze(recent = mapOf("a" to 10), baseline = mapOf("a" to 2)) // 10 >= 5 and 10 >= 2*2

        assertEquals(listOf("a"), report.anomalies.map { it.packageName })
    }

    @Test
    fun `does not flag volume below the minimum event count`() {
        val report = analyze(recent = mapOf("a" to 4)) // 4 < 5

        assertTrue(report.anomalies.isEmpty())
    }

    @Test
    fun `does not flag steady volume as an anomaly`() {
        val report = analyze(recent = mapOf("a" to 6), baseline = mapOf("a" to 5)) // 6 < 2*5

        assertTrue(report.anomalies.isEmpty())
    }

    @Test
    fun `flags a brand-new noisy app as an anomaly`() {
        val report = analyze(recent = mapOf("a" to 5)) // 5 >= 5 and 5 >= 2*max(0,1)

        assertEquals(listOf("a"), report.anomalies.map { it.packageName })
    }

    @Test
    fun `empty input yields an empty report`() {
        val report = analyze(emptyMap())

        assertEquals(0, report.totalEvents)
        assertTrue(report.topMutedApps.isEmpty())
        assertTrue(report.anomalies.isEmpty())
    }
}
