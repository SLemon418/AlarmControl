package com.alarmcontrol.service

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.core.filtering.Condition
import com.alarmcontrol.core.filtering.NotificationEvent
import com.alarmcontrol.core.filtering.NotificationEventRepository
import com.alarmcontrol.core.filtering.NotificationImportance
import com.alarmcontrol.core.filtering.Rule
import com.alarmcontrol.core.filtering.RuleAction
import com.alarmcontrol.core.filtering.RuleExecutionMode
import com.alarmcontrol.core.filtering.RuleRepository
import com.alarmcontrol.core.settings.SettingsRepository
import com.alarmcontrol.data.db.dao.NotificationEventDao
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end Android-runtime check for the listener's real Binder actions.
 *
 * Android's notification shell posts controlled notifications under `com.android.shell`. Temporary
 * title-scoped rules exercise monitor, cancel, snooze, burst handling, and forced-idle behavior
 * without touching notifications from another installed app or granting notification-posting
 * permission to the product/test packages.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class NotificationFilterServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var targetContext: Context
    private lateinit var ruleRepository: RuleRepository
    private lateinit var eventRepository: NotificationEventRepository
    private lateinit var eventDao: NotificationEventDao
    private lateinit var settingsRepository: SettingsRepository
    private val createdRuleIds = mutableListOf<String>()
    private val postedTags = mutableSetOf<String>()
    private var originalFilteringEnabled: Boolean? = null
    private var originalListenerAccessGranted: Boolean? = null

    @Before
    fun setUp() =
        runBlocking {
            targetContext = instrumentation.targetContext.applicationContext
            originalListenerAccessGranted =
                targetContext.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(targetContext)
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    targetContext,
                    DeviceValidationEntryPoint::class.java,
                )
            ruleRepository = entryPoint.ruleRepository()
            eventRepository = entryPoint.eventRepository()
            eventDao = entryPoint.notificationEventDao()
            settingsRepository = entryPoint.settingsRepository()
            clearDeviceValidationEvents()
            postedTags += LEGACY_PROBE_TAGS
            grantListenerAccess()
            originalFilteringEnabled = settingsRepository.filteringEnabled.first()
            settingsRepository.setFilteringEnabled(true)
            awaitListenerAccess()

            createdRuleIds +=
                saveRule(
                    titleMarker = MONITOR_TITLE,
                    action = RuleAction.Cancel,
                    mode = RuleExecutionMode.MONITOR,
                )
            createdRuleIds +=
                saveRule(
                    titleMarker = CANCEL_TITLE,
                    action = RuleAction.Cancel,
                    mode = RuleExecutionMode.ACTIVE,
                )
            createdRuleIds +=
                saveRule(
                    titleMarker = SNOOZE_TITLE,
                    action = RuleAction.Snooze(SNOOZE_MILLIS),
                    mode = RuleExecutionMode.ACTIVE,
                )
            createdRuleIds +=
                saveRule(
                    titleMarker = BURST_TITLE_PREFIX,
                    action = RuleAction.Cancel,
                    mode = RuleExecutionMode.ACTIVE,
                )
            createdRuleIds +=
                saveRule(
                    titleMarker = RANKING_TITLE,
                    action = RuleAction.Cancel,
                    mode = RuleExecutionMode.ACTIVE,
                    extraCondition = Condition.ImportanceAtLeast(NotificationImportance.MIN),
                )
            awaitRulesPersisted()
            // Room invalidation reaches the listener asynchronously after the test observer.
            delay(RULE_CACHE_SETTLE_MILLIS)
        }

    @After
    fun tearDown() =
        runBlocking {
            val failures = mutableListOf<Throwable>()

            suspend fun attempt(block: suspend () -> Unit) {
                runCatching { block() }.onFailure(failures::add)
            }

            attempt { shellOutput("dumpsys deviceidle unforce") }
            attempt { cancelPostedNotifications() }
            createdRuleIds.forEach { ruleId ->
                attempt {
                    if (::ruleRepository.isInitialized) {
                        ruleRepository.deleteRule(ruleId)
                    }
                }
            }
            attempt {
                if (::settingsRepository.isInitialized) {
                    originalFilteringEnabled?.let { settingsRepository.setFilteringEnabled(it) }
                }
            }
            attempt {
                if (::eventDao.isInitialized) {
                    clearDeviceValidationEvents()
                }
            }
            attempt {
                restoreRuntimePermissions()
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Device-validation cleanup failed", failures.first()).also { aggregate ->
                    failures.drop(1).forEach(aggregate::addSuppressed)
                }
            }
        }

    @Test
    fun monitorCancelLeavesNotificationWhileActiveRulesCancelAndSnooze() =
        runBlocking {
            val monitorStart = System.currentTimeMillis()
            postNotification(MONITOR_NOTIFICATION_ID, MONITOR_TITLE)
            val monitorEvent = awaitEvent(monitorStart, createdRuleIds[0])
            assertEquals(RuleAction.Keep, monitorEvent.action)
            assertEquals(createdRuleIds[0], monitorEvent.monitoredRuleId)
            assertEquals(RuleAction.Cancel, monitorEvent.monitoredAction)
            assertTrue(notificationIsActive(MONITOR_NOTIFICATION_ID))

            val cancelStart = System.currentTimeMillis()
            postNotification(CANCEL_NOTIFICATION_ID, CANCEL_TITLE)
            val cancelEvent = awaitEvent(cancelStart, createdRuleIds[1])
            assertEquals(createdRuleIds[1], cancelEvent.matchedRuleId)
            assertEquals(RuleAction.Cancel, cancelEvent.action)
            awaitNotificationState(CANCEL_NOTIFICATION_ID, expectedActive = false)

            val snoozeStart = System.currentTimeMillis()
            postNotification(SNOOZE_NOTIFICATION_ID, SNOOZE_TITLE)
            val snoozeEvent = awaitEvent(snoozeStart, createdRuleIds[2])
            assertEquals(createdRuleIds[2], snoozeEvent.matchedRuleId)
            // Event history intentionally stores only the action kind, not the rule's duration.
            assertTrue(snoozeEvent.action is RuleAction.Snooze)
            awaitNotificationState(SNOOZE_NOTIFICATION_ID, expectedActive = false)

            assertFalse(notificationIsActive(CANCEL_NOTIFICATION_ID))
            assertFalse(notificationIsActive(SNOOZE_NOTIFICATION_ID))
        }

    @Test
    fun rapidNotificationBurstStillProcessesTheNewestPost() =
        runBlocking {
            repeat(BURST_NOTIFICATION_COUNT) { index ->
                postNotification(
                    id = BURST_NOTIFICATION_ID_BASE + index,
                    title = "${BURST_TITLE_PREFIX}_$index",
                )
            }
            delay(POST_TIME_SEPARATION_MILLIS)

            val sentinelStart = System.currentTimeMillis()
            postNotification(BURST_SENTINEL_NOTIFICATION_ID, "${BURST_TITLE_PREFIX}_sentinel")

            val sentinelEvent = awaitEvent(sentinelStart, createdRuleIds[3])
            assertEquals(createdRuleIds[3], sentinelEvent.matchedRuleId)
            assertEquals(RuleAction.Cancel, sentinelEvent.action)
            awaitNotificationState(BURST_SENTINEL_NOTIFICATION_ID, expectedActive = false)
            assertTrue(
                NotificationManagerCompat
                    .getEnabledListenerPackages(targetContext)
                    .contains(targetContext.packageName),
            )
        }

    @Test
    fun forcedIdleDoesNotBreakListenerCancellation() =
        runBlocking {
            val idleResult = shellOutput("dumpsys deviceidle force-idle")
            try {
                assertTrue(idleResult.contains("idle", ignoreCase = true))

                val postedAt = System.currentTimeMillis()
                postNotification(DOZE_NOTIFICATION_ID, CANCEL_TITLE)

                val event = awaitEvent(postedAt, createdRuleIds[1])
                assertEquals(createdRuleIds[1], event.matchedRuleId)
                assertEquals(RuleAction.Cancel, event.action)
                awaitNotificationState(DOZE_NOTIFICATION_ID, expectedActive = false)
            } finally {
                shellOutput("dumpsys deviceidle unforce")
            }
        }

    @Test
    fun frameworkRankingImportanceFeedsTheProtectionCondition() =
        runBlocking {
            val postedAt = System.currentTimeMillis()
            postNotification(RANKING_NOTIFICATION_ID, RANKING_TITLE)

            val event = awaitEvent(postedAt, createdRuleIds[4])
            assertEquals(createdRuleIds[4], event.matchedRuleId)
            assertEquals(RuleAction.Cancel, event.action)
            assertNotNull("Samsung ranking should expose notification importance", event.importance)
            awaitNotificationState(RANKING_NOTIFICATION_ID, expectedActive = false)
        }

    private fun grantListenerAccess() {
        runShellCommand(
            "cmd notification allow_listener " +
                "${targetContext.packageName}/$LISTENER_COMPONENT",
        )
    }

    private fun restoreRuntimePermissions() {
        if (originalListenerAccessGranted == false) {
            runShellCommand(
                "cmd notification disallow_listener " +
                    "${targetContext.packageName}/$LISTENER_COMPONENT",
            )
        }
    }

    private suspend fun awaitListenerAccess() {
        withTimeout(LISTENER_TIMEOUT_MILLIS) {
            while (
                targetContext.packageName !in
                NotificationManagerCompat.getEnabledListenerPackages(targetContext)
            ) {
                delay(POLL_MILLIS)
            }
        }
    }

    private suspend fun clearDeviceValidationEvents() {
        eventDao.deleteForPackageChannels(SHELL_PACKAGE, listOf(SHELL_CHANNEL))
    }

    private suspend fun saveRule(
        titleMarker: String,
        action: RuleAction,
        mode: RuleExecutionMode,
        extraCondition: Condition? = null,
    ): String =
        ruleRepository.saveRule(
            Rule(
                id = "",
                name = "Device validation $titleMarker",
                priority = DEVICE_VALIDATION_PRIORITY,
                condition =
                    Condition.AllOf(
                        buildList {
                            add(Condition.PackageEquals(SHELL_PACKAGE))
                            add(Condition.TitleContains(titleMarker))
                            extraCondition?.let(::add)
                        },
                    ),
                action = action,
                executionMode = mode,
            ),
        )

    private suspend fun awaitRulesPersisted() {
        withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
            ruleRepository.observeRules().first { rules ->
                createdRuleIds.all { id -> rules.any { it.id == id } }
            }
        }
    }

    private fun postNotification(
        id: Int,
        title: String,
    ) {
        val tag = tagFor(id)
        postedTags += tag
        shellOutput("cmd notification post -t $title $tag $NOTIFICATION_TEXT")
    }

    private suspend fun cancelPostedNotifications() {
        if (postedTags.isEmpty()) return
        createdRuleIds +=
            saveRule(
                titleMarker = CLEANUP_TITLE,
                action = RuleAction.Cancel,
                mode = RuleExecutionMode.ACTIVE,
            )
        awaitRulesPersisted()
        delay(RULE_CACHE_SETTLE_MILLIS)
        postedTags.toList().forEach { tag ->
            shellOutput("cmd notification post -t $CLEANUP_TITLE $tag $NOTIFICATION_TEXT")
            withTimeout(EVENT_TIMEOUT_MILLIS) {
                while (notificationIsActive(tag)) {
                    delay(POLL_MILLIS)
                }
            }
        }
    }

    private fun notificationKey(tag: String): String = "0|$SHELL_PACKAGE|$SHELL_NOTIFICATION_ID|$tag|$SHELL_UID"

    private fun tagFor(id: Int): String = "$TAG_PREFIX$id"

    private fun notificationIsActive(id: Int): Boolean = notificationIsActive(tagFor(id))

    private fun notificationIsActive(tag: String): Boolean =
        shellOutput("cmd notification list")
            .lineSequence()
            .any { key -> key == notificationKey(tag) }

    private suspend fun awaitEvent(
        postedAfterMillis: Long,
        expectedRuleId: String,
    ): NotificationEvent =
        withTimeout(EVENT_TIMEOUT_MILLIS) {
            eventRepository
                .observeRecent(EVENT_LIMIT)
                .map { events ->
                    events.firstOrNull { event ->
                        event.packageName == SHELL_PACKAGE &&
                            event.postedAtMillis >= postedAfterMillis &&
                            (
                                event.matchedRuleId == expectedRuleId ||
                                    event.monitoredRuleId == expectedRuleId
                            )
                    }
                }.filterNotNull()
                .first()
        }

    private suspend fun awaitNotificationState(
        id: Int,
        expectedActive: Boolean,
    ) {
        withTimeout(EVENT_TIMEOUT_MILLIS) {
            while (notificationIsActive(id) != expectedActive) {
                delay(POLL_MILLIS)
            }
        }
    }

    private fun runShellCommand(command: String) {
        check(shellOutput(command).isBlank()) {
            "Shell command failed: $command"
        }
    }

    private fun shellOutput(command: String): String {
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            )
        return output.use { input ->
            input.readBytes().decodeToString()
        }
    }

    private companion object {
        const val LISTENER_COMPONENT = "com.alarmcontrol.service.NotificationFilterService"
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_CHANNEL = "shell_cmd"
        const val SHELL_NOTIFICATION_ID = 2020
        const val SHELL_UID = 2000
        const val TAG_PREFIX = "alarmcontrol_device_"
        val LEGACY_PROBE_TAGS = setOf("alarmcontrol_probe", "monitor")
        const val NOTIFICATION_TEXT = "AlarmControl_device_validation"
        const val MONITOR_TITLE = "AlarmControl_monitor_probe"
        const val CANCEL_TITLE = "AlarmControl_cancel_probe"
        const val SNOOZE_TITLE = "AlarmControl_snooze_probe"
        const val BURST_TITLE_PREFIX = "AlarmControl_burst_probe"
        const val RANKING_TITLE = "AlarmControl_ranking_probe"
        const val CLEANUP_TITLE = "AlarmControl_cleanup_probe"
        const val MONITOR_NOTIFICATION_ID = 71_001
        const val CANCEL_NOTIFICATION_ID = 71_002
        const val SNOOZE_NOTIFICATION_ID = 71_003
        const val DOZE_NOTIFICATION_ID = 71_004
        const val RANKING_NOTIFICATION_ID = 71_005
        const val BURST_NOTIFICATION_ID_BASE = 72_000
        const val BURST_SENTINEL_NOTIFICATION_ID = 72_999

        // Stay below Samsung's per-package system notification cap; the pure coordinator test
        // separately exercises the 64-entry application queue and overflow policy.
        const val BURST_NOTIFICATION_COUNT = 20
        const val DEVICE_VALIDATION_PRIORITY = 2_000_000_000
        const val SNOOZE_MILLIS = 60_000L
        const val POST_TIME_SEPARATION_MILLIS = 100L
        const val RULE_CACHE_SETTLE_MILLIS = 750L
        const val POLL_MILLIS = 50L
        const val LISTENER_TIMEOUT_MILLIS = 10_000L
        const val REPOSITORY_TIMEOUT_MILLIS = 10_000L
        const val EVENT_TIMEOUT_MILLIS = 15_000L
        const val EVENT_LIMIT = 100
    }
}
