package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.entity.AdFeedbackPriorEntity
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.LlmObservationEntity
import com.alarmcontrol.data.db.entity.NotificationEventEntity
import com.alarmcontrol.data.db.entity.RuleEntity
import com.alarmcontrol.data.db.entity.RuleSuggestionDismissalEntity
import com.alarmcontrol.data.db.entity.SemanticFeedbackPriorEntity
import com.alarmcontrol.data.db.model.StoredRuleAction
import com.alarmcontrol.data.security.NotificationContentAccessGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDataRepositoryImplTest {
    private val rules = FakeRuleDao()
    private val events = FakeNotificationEventDao()
    private val feedback = FakeCategoryFeedbackDao()
    private val insights = FakeDailyInsightDao()
    private val profiles = FakeProfileDao()
    private val llmObservations = FakeLlmObservationDao()
    private val automationAudit = FakeAutomationAuditDao()
    private val suggestions = FakeRuleSuggestionDao()
    private val contentCipher = FakeNotificationContentCipher()
    private val repository =
        LocalDataRepositoryImpl(
            ImmediateTransactionRunner(),
            rules,
            events,
            feedback,
            insights,
            profiles,
            llmObservations,
            automationAudit,
            suggestions,
            contentCipher,
            NotificationContentAccessGuard(),
        )

    @Test
    fun `clear all reports and removes every database category`() =
        runTest {
            rules.insertRule(
                RuleEntity(
                    name = "rule",
                    enabled = true,
                    priority = 0,
                    action = StoredRuleAction.CANCEL,
                    createdAtMillis = 0,
                    updatedAtMillis = 0,
                ),
            )
            events.insert(sampleEvent())
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1L,
                ),
            )
            insights.upsertInsight(
                DailyInsightEntity(
                    epochDay = 1,
                    windowStartMillis = 0,
                    windowEndMillis = 1,
                    totalNotifications = 1,
                    mutedCount = 1,
                    generatedAtMillis = 1,
                ),
            )
            profiles.store(
                com.alarmcontrol.data.db.entity.FilteringProfileEntity(
                    name = "Focus",
                    createdAtMillis = 1,
                    updatedAtMillis = 1,
                ),
                setOf(1),
            )
            suggestions.dismiss(RuleSuggestionDismissalEntity("channel:com.example:offers", 1L))

            val counts = repository.clearAllDatabaseData()

            assertEquals(1, counts.rules)
            assertEquals(1, counts.profiles)
            assertEquals(1, counts.events)
            assertEquals(1, counts.feedback)
            assertEquals(1, counts.insightDays)
            assertEquals(0, counts.encryptedContents)
            assertEquals(true, contentCipher.keyDeleted)
            assertEquals(0, rules.countAll())
            assertEquals(0, events.countAll())
            assertEquals(0, feedback.countAll())
            assertEquals(0, insights.countAll())
            assertEquals(0, profiles.countAll())
            assertEquals(emptyList<String>(), suggestions.observeDismissedKeys().first())
        }

    @Test
    fun `clearing stored notification content leaves metadata and deletes the key`() =
        runTest {
            events.insertWithTrace(
                sampleEvent().copy(hadEncryptedContent = true),
                emptyList(),
                contentCipher
                    .encrypt("content".encodeToByteArray())
                    .let {
                        com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity(
                            formatVersion = it.formatVersion,
                            aadId = it.aadId,
                            nonce = it.nonce,
                            ciphertext = it.ciphertext,
                            createdAtMillis = 1,
                        )
                    },
            )

            val counts = repository.clearStoredNotificationContent()

            assertEquals(1, counts.encryptedContents)
            assertEquals(1, events.countAll())
            assertEquals(0, events.countEncryptedContents())
            assertEquals(true, contentCipher.keyDeleted)
        }

    @Test
    fun `clearing one excluded package removes only its ciphertext`() =
        runTest {
            listOf("com.private" to 1L, "com.allowed" to 2L).forEach { (packageName, id) ->
                events.insertWithTrace(
                    sampleEvent().copy(id = id, packageName = packageName, hadEncryptedContent = true),
                    emptyList(),
                    contentCipher
                        .encrypt("$packageName-content".encodeToByteArray())
                        .let {
                            com.alarmcontrol.data.db.entity.EncryptedNotificationContentEntity(
                                formatVersion = it.formatVersion,
                                aadId = it.aadId,
                                nonce = it.nonce,
                                ciphertext = it.ciphertext,
                                createdAtMillis = 1,
                            )
                        },
                )
            }

            val counts = repository.clearStoredNotificationContentForPackage("com.private")

            assertEquals(1, counts.encryptedContents)
            assertEquals(2, events.countAll())
            assertEquals(1, events.countEncryptedContents())
        }

    @Test
    fun `partial clear leaves unrelated categories intact`() =
        runTest {
            events.insert(sampleEvent())
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = null,
                    correctedLabel = "news",
                    recordedAtMillis = 1L,
                ),
            )

            repository.clearFeedback()

            assertEquals(1, events.countAll())
            assertEquals(0, feedback.countAll())
        }

    @Test
    fun `clear feedback reports semantic and legacy imported votes without double counting`() =
        runTest {
            feedback.insert(
                CategoryFeedbackEntity(
                    packageName = "com.example",
                    predictedLabel = "promotion",
                    correctedLabel = "social",
                    recordedAtMillis = 1L,
                ),
            )
            llmObservations.upsert(
                LlmObservationEntity(
                    notificationEventId = 1,
                    packageName = "com.example",
                    predictedIsAdvertisement = true,
                    predictedIntent = "MARKETING",
                    confidenceScore = 0.8f,
                    correctedIsAdvertisement = false,
                    correctedIntent = "TRANSACTIONAL",
                    analyzedAtMillis = 1,
                ),
            )
            llmObservations.upsertImportedPriors(
                listOf(AdFeedbackPriorEntity("com.example", true, 2)),
            )
            llmObservations.upsertSemanticImportedPriors(
                listOf(SemanticFeedbackPriorEntity("com.example", "MARKETING", 3)),
            )

            val counts = repository.clearFeedback()

            assertEquals(7, counts.feedback)
            assertEquals(emptyList<Any>(), llmObservations.getFeedbackCounts())
            assertEquals(emptyList<Any>(), llmObservations.getSemanticFeedbackCounts())
        }

    private fun sampleEvent() =
        NotificationEventEntity(
            packageName = "com.example",
            category = null,
            postedAtMillis = 1,
            action = StoredRuleAction.CANCEL,
            matchedRuleId = null,
            recordedAtMillis = 1,
        )
}
