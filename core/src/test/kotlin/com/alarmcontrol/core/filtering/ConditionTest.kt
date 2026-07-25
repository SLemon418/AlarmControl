package com.alarmcontrol.core.filtering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionTest {
    private fun snapshot(
        packageName: String = "com.example.clock",
        title: String? = "Alarm",
        text: String? = "Time to wake up",
        category: String? = "alarm",
        channelId: String? = "alarms",
        isOngoing: Boolean = false,
        mlCategory: String? = null,
        postedMinuteOfDay: Int? = null,
        isAdvertisement: Boolean? = null,
        semanticIntent: SemanticIntent? = null,
        importance: NotificationImportance? = null,
        conversation: Boolean? = null,
        foregroundService: Boolean? = null,
        rateCounts: Map<RateSignal, Int> = emptyMap(),
    ) = NotificationSnapshot(
        packageName = packageName,
        title = title,
        text = text,
        category = category,
        channelId = channelId,
        postedAtMillis = 0L,
        isOngoing = isOngoing,
        mlCategory = mlCategory,
        postedMinuteOfDay = postedMinuteOfDay,
        isAdvertisement = isAdvertisement,
        semanticIntent = semanticIntent,
        importance = importance,
        isConversation = conversation,
        isForegroundService = foregroundService,
        rateCounts = rateCounts,
    )

    @Test
    fun `packageEquals matches exact package only`() {
        assertTrue(Condition.PackageEquals("com.example.clock").matches(snapshot()))
        assertFalse(Condition.PackageEquals("com.other.app").matches(snapshot()))
    }

    @Test
    fun `titleContains honours ignoreCase and null title`() {
        assertTrue(Condition.TitleContains("alarm").matches(snapshot(title = "Alarm")))
        assertFalse(Condition.TitleContains("alarm", ignoreCase = false).matches(snapshot(title = "Alarm")))
        assertFalse(Condition.TitleContains("alarm").matches(snapshot(title = null)))
    }

    @Test
    fun `textContains matches substring and rejects null text`() {
        assertTrue(Condition.TextContains("wake").matches(snapshot()))
        assertFalse(Condition.TextContains("wake").matches(snapshot(text = null)))
    }

    @Test
    fun `category and channel equality`() {
        assertTrue(Condition.CategoryEquals("alarm").matches(snapshot()))
        assertFalse(Condition.CategoryEquals("alarm").matches(snapshot(category = null)))
        assertTrue(Condition.ChannelEquals("alarms").matches(snapshot()))
    }

    @Test
    fun `ongoing flag`() {
        assertTrue(Condition.Ongoing(true).matches(snapshot(isOngoing = true)))
        assertFalse(Condition.Ongoing(true).matches(snapshot(isOngoing = false)))
    }

    @Test
    fun `mlCategory matches signal and degrades to false when absent`() {
        assertTrue(Condition.MlCategoryEquals("promotion").matches(snapshot(mlCategory = "promotion")))
        assertFalse(Condition.MlCategoryEquals("promotion").matches(snapshot(mlCategory = null)))
    }

    @Test
    fun `isAdvertisement matches the LLM verdict and degrades to false when absent`() {
        assertTrue(Condition.IsAdvertisement(true).matches(snapshot(isAdvertisement = true)))
        assertFalse(Condition.IsAdvertisement(true).matches(snapshot(isAdvertisement = false)))
        // No LLM signal -> neither true nor false matches, so rules fall back (§5).
        assertFalse(Condition.IsAdvertisement(true).matches(snapshot(isAdvertisement = null)))
        assertFalse(Condition.IsAdvertisement(false).matches(snapshot(isAdvertisement = null)))
    }

    @Test
    fun `semantic and protection signals preserve unknown instead of guessing`() {
        assertEquals(
            ConditionResult.UNKNOWN,
            Condition.SemanticIntentEquals(SemanticIntent.SECURITY).evaluate(snapshot()),
        )
        assertEquals(ConditionResult.UNKNOWN, Condition.Conversation(true).evaluate(snapshot()))
        assertEquals(ConditionResult.UNKNOWN, Condition.ForegroundService(true).evaluate(snapshot()))
        assertEquals(
            ConditionResult.UNKNOWN,
            Condition.ImportanceAtLeast(NotificationImportance.HIGH).evaluate(snapshot()),
        )

        assertTrue(
            Condition
                .SemanticIntentEquals(SemanticIntent.SECURITY)
                .matches(snapshot(semanticIntent = SemanticIntent.SECURITY)),
        )
        assertTrue(Condition.Conversation(true).matches(snapshot(conversation = true)))
        assertTrue(Condition.ForegroundService(true).matches(snapshot(foregroundService = true)))
        assertTrue(
            Condition
                .ImportanceAtLeast(NotificationImportance.HIGH)
                .matches(snapshot(importance = NotificationImportance.MAX)),
        )
    }

    @Test
    fun `frequency condition uses caller count and stays unknown when unavailable`() {
        val signal = RateSignal(RateScope.CHANNEL, 5 * 60_000)
        val condition = Condition.RateAtLeast(RateScope.CHANNEL, 5 * 60_000, threshold = 4)

        assertEquals(ConditionResult.UNKNOWN, condition.evaluate(snapshot()))
        assertEquals(ConditionResult.NO_MATCH, condition.evaluate(snapshot(rateCounts = mapOf(signal to 3))))
        assertEquals(ConditionResult.MATCH, condition.evaluate(snapshot(rateCounts = mapOf(signal to 4))))
    }

    @Test
    fun `frequency constructor rejects unsupported windows and thresholds`() {
        assertThrows(IllegalArgumentException::class.java) {
            Condition.RateAtLeast(RateScope.PACKAGE, 59_999, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Condition.RateAtLeast(RateScope.PACKAGE, 60_000, 1)
        }
    }

    @Test
    fun `allOf is true only when every child matches and false when empty`() {
        val all =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.example.clock"),
                    Condition.CategoryEquals("alarm"),
                ),
            )
        assertTrue(all.matches(snapshot()))
        assertFalse(all.matches(snapshot(category = "message")))
        assertFalse(Condition.AllOf(emptyList()).matches(snapshot()))
    }

    @Test
    fun `anyOf is true when at least one child matches and false when empty`() {
        val any =
            Condition.AnyOf(
                listOf(
                    Condition.PackageEquals("com.other.app"),
                    Condition.CategoryEquals("alarm"),
                ),
            )
        assertTrue(any.matches(snapshot()))
        // Neither child matches: package is not com.other.app and category is not alarm.
        assertFalse(any.matches(snapshot(category = "message")))
        assertFalse(Condition.AnyOf(emptyList()).matches(snapshot()))
    }

    @Test
    fun `not inverts the wrapped condition`() {
        val notAlarm = Condition.Not(Condition.CategoryEquals("alarm"))
        assertFalse(notAlarm.matches(snapshot()))
        assertTrue(notAlarm.matches(snapshot(category = "message")))
    }

    @Test
    fun `not does not turn an unavailable model signal into a match`() {
        assertFalse(Condition.Not(Condition.MlCategoryEquals("promotion")).matches(snapshot(mlCategory = null)))
        assertFalse(Condition.Not(Condition.IsAdvertisement(true)).matches(snapshot(isAdvertisement = null)))
        assertFalse(Condition.Not(Condition.TimeWindow(0, 60)).matches(snapshot(postedMinuteOfDay = null)))
        assertEquals(
            ConditionResult.UNKNOWN,
            Condition.Not(Condition.MlCategoryEquals("promotion")).evaluate(snapshot(mlCategory = null)),
        )
    }

    @Test
    fun `three state composites short circuit known results before unknown signals`() {
        val unknownMl = Condition.MlCategoryEquals("promotion")

        assertEquals(
            ConditionResult.MATCH,
            Condition.AnyOf(listOf(Condition.CategoryEquals("alarm"), unknownMl)).evaluate(snapshot()),
        )
        assertEquals(
            ConditionResult.NO_MATCH,
            Condition.AllOf(listOf(Condition.PackageEquals("com.other"), unknownMl)).evaluate(snapshot()),
        )
        assertEquals(
            ConditionResult.UNKNOWN,
            Condition.AllOf(listOf(Condition.PackageEquals("com.example.clock"), unknownMl)).evaluate(snapshot()),
        )
    }

    @Test
    fun `timeWindow matches inside a same-day window and rejects outside`() {
        val nineToFive = Condition.TimeWindow(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        assertTrue(nineToFive.matches(snapshot(postedMinuteOfDay = 12 * 60)))
        assertTrue(nineToFive.matches(snapshot(postedMinuteOfDay = 9 * 60))) // inclusive start
        assertTrue(nineToFive.matches(snapshot(postedMinuteOfDay = 17 * 60))) // inclusive end
        assertFalse(nineToFive.matches(snapshot(postedMinuteOfDay = 8 * 60 + 59)))
        assertFalse(nineToFive.matches(snapshot(postedMinuteOfDay = 17 * 60 + 1)))
    }

    @Test
    fun `timeWindow wraps past midnight`() {
        val overnight = Condition.TimeWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60)
        assertTrue(overnight.matches(snapshot(postedMinuteOfDay = 23 * 60))) // 23:00
        assertTrue(overnight.matches(snapshot(postedMinuteOfDay = 3 * 60))) // 03:00
        assertFalse(overnight.matches(snapshot(postedMinuteOfDay = 12 * 60))) // noon
    }

    @Test
    fun `timeWindow degrades to non-match when local time is unknown`() {
        val window = Condition.TimeWindow(0, 1439)
        assertFalse(window.matches(snapshot(postedMinuteOfDay = null)))
    }

    @Test
    fun `nested A AND (B OR C) evaluates correctly`() {
        // from com.example.clock AND (category alarm OR ongoing)
        val rule =
            Condition.AllOf(
                listOf(
                    Condition.PackageEquals("com.example.clock"),
                    Condition.AnyOf(
                        listOf(Condition.CategoryEquals("alarm"), Condition.Ongoing(true)),
                    ),
                ),
            )
        assertTrue(rule.matches(snapshot(category = "alarm", isOngoing = false))) // A + B
        assertTrue(rule.matches(snapshot(category = "message", isOngoing = true))) // A + C
        assertFalse(rule.matches(snapshot(category = "message", isOngoing = false))) // A but neither B nor C
        assertFalse(rule.matches(snapshot(packageName = "com.other", category = "alarm"))) // not A
    }
}
