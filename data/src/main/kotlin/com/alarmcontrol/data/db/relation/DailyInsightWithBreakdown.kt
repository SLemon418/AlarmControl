package com.alarmcontrol.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.alarmcontrol.data.db.entity.DailyInsightAppCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightCategoryCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightChannelCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightEntity
import com.alarmcontrol.data.db.entity.DailyInsightHourCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightMonitorRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightRuleCountEntity
import com.alarmcontrol.data.db.entity.DailyInsightSemanticCountEntity

/** A [DailyInsightEntity] with its rule-trigger and category breakdown rows assembled by Room. */
data class DailyInsightWithBreakdown(
    @Embedded val insight: DailyInsightEntity,
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val ruleCounts: List<DailyInsightRuleCountEntity>,
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val monitorRuleCounts: List<DailyInsightMonitorRuleCountEntity> = emptyList(),
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val categoryCounts: List<DailyInsightCategoryCountEntity>,
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val channelCounts: List<DailyInsightChannelCountEntity> = emptyList(),
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val appCounts: List<DailyInsightAppCountEntity> = emptyList(),
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val hourCounts: List<DailyInsightHourCountEntity> = emptyList(),
    @Relation(parentColumn = "epoch_day", entityColumn = "epoch_day")
    val semanticCounts: List<DailyInsightSemanticCountEntity> = emptyList(),
)
