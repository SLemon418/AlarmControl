package com.alarmcontrol.notifications

import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationSnapshot
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * JVM timing tests (CLAUDE.md §9) that exercise the engine the way the listener does: a large batch
 * of notifications evaluated against many deeply-nested rules. These prove (1) deep-nest evaluation
 * stays fast, (2) the compiled path agrees with the one-off path, and (3) compiling once beats
 * re-compiling per notification. Absolute bounds are deliberately generous so the tests catch
 * catastrophic regressions without flaking on slow/loaded CI machines; relative timings are reported.
 */
class MatcherBenchmarkTest {
    private val matcher = Matcher()

    private fun snapshot(i: Int) =
        NotificationSnapshot(
            packageName = "com.app${i % 50}",
            title = "Title $i",
            text = "message $i body",
            category = if (i % 3 == 0) "alarm" else "msg",
            channelId = "ch${i % 10}",
            postedAtMillis = 0L,
            isOngoing = i % 2 == 0,
            mlCategory = if (i % 4 == 0) "promotion" else null,
            postedMinuteOfDay = i % 1440,
        )

    /** A rule whose condition nests [depth] levels of AllOf/AnyOf/Not/TimeWindow around a leaf pair. */
    private fun deepRule(
        id: Int,
        depth: Int = 5,
    ): Rule {
        var condition: Condition =
            Condition.AllOf(
                listOf(Condition.PackageEquals("com.app$id"), Condition.TitleContains("Title $id")),
            )
        repeat(depth) { level ->
            condition =
                Condition.AllOf(
                    listOf(
                        Condition.AnyOf(
                            // Every fixture is alarm or msg, so evaluation reaches the nested child
                            // instead of short-circuiting at the first level.
                            listOf(Condition.CategoryEquals("alarm"), Condition.CategoryEquals("msg")),
                        ),
                        Condition.Not(Condition.ChannelEquals("x$level")),
                        Condition.TimeWindow(startMinuteOfDay = 0, endMinuteOfDay = 1439),
                        condition,
                    ),
                )
        }
        return Rule(id = "$id", name = "rule$id", priority = id, condition = condition, action = RuleAction.Cancel)
    }

    @Test
    fun `compiled and one-off evaluation agree across a large batch`() {
        val rules =
            listOf(
                Rule("1", "a", priority = 1, condition = Condition.CategoryEquals("alarm"), action = RuleAction.Cancel),
                Rule(
                    "2",
                    "b",
                    priority = 5,
                    condition =
                        Condition.AnyOf(
                            listOf(Condition.PackageEquals("com.app1"), Condition.MlCategoryEquals("promotion")),
                        ),
                    action = RuleAction.MarkRead,
                ),
                // Disabled, highest priority: must never win — proves compile() really drops it.
                Rule(
                    "3",
                    "c",
                    enabled = false,
                    priority = 99,
                    condition = Condition.CategoryEquals("alarm"),
                    action = RuleAction.Keep,
                ),
            )
        val compiled = matcher.compile(rules)

        repeat(500) { i ->
            val snap = snapshot(i)
            assertEquals(matcher.evaluate(snap, rules), matcher.evaluate(snap, compiled))
        }
    }

    @Test
    fun `evaluating a large batch against deeply nested rules stays fast`() {
        val rules = (0 until 100).map { deepRule(it) }
        val compiled = matcher.compile(rules)
        val snapshots = (0 until 2_000).map { snapshot(it) }

        repeat(2) { snapshots.forEach { matcher.evaluate(it, compiled) } } // warm up the JIT

        val nanos = measureNanoTime { snapshots.forEach { matcher.evaluate(it, compiled) } }
        val millis = nanos / 1_000_000
        val perEvalMicros = nanos.toDouble() / snapshots.size / 1_000
        println(
            "Matcher: ${snapshots.size} notifications x ${rules.size} nested rules in ${millis}ms " +
                "(${"%.2f".format(perEvalMicros)}us/notification)",
        )
        assertTrue("evaluation too slow: ${millis}ms for ${snapshots.size}x${rules.size}", millis < 5_000)
    }

    @Test
    fun `compiling once is faster than recompiling per notification`() {
        // Every rule matches, so the highest-priority one wins immediately and the per-notification
        // cost is dominated by the filter+sort the one-off path repeats for every notification.
        val rules =
            (0 until 2_000).map {
                Rule(
                    "$it",
                    "r$it",
                    priority = it,
                    condition = Condition.CategoryEquals("alarm"),
                    action = RuleAction.Cancel,
                )
            }
        val snapshots = (0 until 2_000).map { snapshot(it).copy(category = "alarm") }
        val compiled = matcher.compile(rules)

        fun recompilePerNotification() = snapshots.forEach { matcher.evaluate(it, rules) }

        fun compileOnce() = snapshots.forEach { matcher.evaluate(it, compiled) }

        recompilePerNotification()
        compileOnce() // warm up

        val recompile = bestOf(3, ::recompilePerNotification)
        val once = bestOf(3, ::compileOnce)
        println(
            "Matcher recompile-per-notification=${recompile / 1_000_000}ms vs " +
                "compile-once=${once / 1_000_000}ms (${recompile / once.coerceAtLeast(1)}x)",
        )
        assertTrue("compile-once ($once ns) should beat recompile-per-notification ($recompile ns)", once < recompile)
    }

    private fun bestOf(
        times: Int,
        block: () -> Unit,
    ): Long {
        var best = Long.MAX_VALUE
        repeat(times) { best = minOf(best, measureNanoTime(block)) }
        return best
    }
}
