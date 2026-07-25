package com.alarmcontrol.data.mapper

import com.alarmcontrol.core.feedback.CategoryFeedback
import com.alarmcontrol.data.db.entity.CategoryFeedbackEntity

/** Maps a domain correction to its stored row. Id is left default so Room assigns it. */
fun CategoryFeedback.toEntity(): CategoryFeedbackEntity =
    CategoryFeedbackEntity(
        packageName = packageName,
        notificationEventId = notificationEventId?.toLongOrNull(),
        predictedLabel = predictedLabel,
        correctedLabel = correctedLabel,
        recordedAtMillis = recordedAtMillis,
    )
