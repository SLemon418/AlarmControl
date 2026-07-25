package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.feedback.AdObservation
import com.alarmcontrol.data.db.entity.LlmObservationEntity

internal fun AdObservation.toEntity(): LlmObservationEntity =
    LlmObservationEntity(
        notificationEventId = notificationEventId.toLong(),
        packageName = packageName,
        predictedIsAdvertisement = predictedIsAdvertisement,
        predictedIntent = predictedIntent.name,
        confidenceScore = confidenceScore,
        correctedIsAdvertisement = correctedIsAdvertisement,
        correctedIntent = correctedIntent?.name,
        analyzedAtMillis = analyzedAtMillis,
    )

internal fun LlmObservationEntity.toDomain(): AdObservation =
    AdObservation(
        notificationEventId = notificationEventId.toString(),
        packageName = packageName,
        predictedIsAdvertisement = predictedIsAdvertisement,
        predictedIntent =
            com.alarmcontrol.core.filtering.SemanticIntent
                .valueOf(predictedIntent),
        confidenceScore = confidenceScore,
        correctedIsAdvertisement = correctedIsAdvertisement,
        correctedIntent = correctedIntent?.let(com.alarmcontrol.core.filtering.SemanticIntent::valueOf),
        analyzedAtMillis = analyzedAtMillis,
    )
