package com.alarmcontrol.data.repository

import com.alarmcontrol.data.db.dao.PendingActionSourceGapRow
import com.alarmcontrol.data.db.dao.PendingNotificationActionDao
import com.alarmcontrol.data.db.entity.NotificationActionPromotionReceiptEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionContentEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionEntity
import com.alarmcontrol.data.db.entity.PendingNotificationActionTraceEntity
import com.alarmcontrol.data.db.relation.PendingNotificationActionRelation

internal class FakePendingNotificationActionDao : PendingNotificationActionDao {
    private val lock = Any()
    private val actions = linkedMapOf<String, PendingNotificationActionEntity>()
    private val traces = linkedMapOf<String, MutableList<PendingNotificationActionTraceEntity>>()
    private val contents = linkedMapOf<String, PendingNotificationActionContentEntity>()
    private val promotionReceipts = linkedMapOf<String, Long>()

    override suspend fun insertAction(action: PendingNotificationActionEntity) {
        synchronized(lock) {
            check(actions.putIfAbsent(action.token, action) == null)
        }
    }

    override suspend fun insertTrace(trace: List<PendingNotificationActionTraceEntity>) {
        synchronized(lock) {
            trace.forEach { row ->
                check(actions.containsKey(row.outboxToken))
                traces.getOrPut(row.outboxToken, ::mutableListOf).add(row)
            }
        }
    }

    override suspend fun insertContent(content: PendingNotificationActionContentEntity) {
        synchronized(lock) {
            check(actions.containsKey(content.outboxToken))
            check(contents.putIfAbsent(content.outboxToken, content) == null)
        }
    }

    override fun arm(token: String): Int =
        synchronized(lock) {
            val action = actions[token]?.takeUnless(PendingNotificationActionEntity::armed) ?: return@synchronized 0
            actions[token] = action.copy(armed = true)
            1
        }

    override suspend fun getArmed(token: String): PendingNotificationActionRelation? =
        synchronized(lock) {
            actions[token]
                ?.takeIf(PendingNotificationActionEntity::armed)
                ?.let { action ->
                    PendingNotificationActionRelation(
                        action = action,
                        trace = traces[token].orEmpty().toList(),
                        contents = listOfNotNull(contents[token]),
                    )
                }
        }

    override suspend fun getPromotedEventId(token: String): Long? =
        synchronized(lock) {
            promotionReceipts[token]
        }

    override suspend fun insertPromotionReceipt(receipt: NotificationActionPromotionReceiptEntity) {
        synchronized(lock) {
            check(promotionReceipts.putIfAbsent(receipt.token, receipt.eventId) == null)
            check(promotionReceipts.values.count { it == receipt.eventId } == 1)
        }
    }

    override suspend fun getArmedTokens(): List<String> =
        synchronized(lock) {
            actions.values
                .filter(PendingNotificationActionEntity::armed)
                .sortedWith(
                    compareBy<PendingNotificationActionEntity> { it.createdAtMillis }
                        .thenBy(PendingNotificationActionEntity::token),
                ).map(PendingNotificationActionEntity::token)
        }

    override suspend fun getArmedSourceGapCandidates(): List<PendingActionSourceGapRow> =
        synchronized(lock) {
            actions.values
                .filter(PendingNotificationActionEntity::armed)
                .map { action ->
                    PendingActionSourceGapRow(
                        postedAtMillis = action.postedAtMillis,
                        postedEpochDay = action.postedEpochDay,
                    )
                }
        }

    override suspend fun delete(token: String): Int =
        synchronized(lock) {
            if (actions.remove(token) == null) {
                0
            } else {
                traces.remove(token)
                contents.remove(token)
                1
            }
        }

    override suspend fun deleteUnarmed(): Int =
        synchronized(lock) {
            actions.values
                .filterNot(PendingNotificationActionEntity::armed)
                .map(PendingNotificationActionEntity::token)
                .sumOf(::deleteLocked)
        }

    override suspend fun trimUnarmedToMostRecent(max: Int): Int =
        synchronized(lock) {
            val retained =
                actions.values
                    .filterNot(PendingNotificationActionEntity::armed)
                    .sortedWith(
                        compareByDescending<PendingNotificationActionEntity> { it.createdAtMillis }
                            .thenByDescending(PendingNotificationActionEntity::token),
                    ).take(max)
                    .mapTo(mutableSetOf(), PendingNotificationActionEntity::token)
            actions.values
                .filter { !it.armed && it.token !in retained }
                .map(PendingNotificationActionEntity::token)
                .sumOf(::deleteLocked)
        }

    override suspend fun deleteAll(): Int =
        synchronized(lock) {
            val count = actions.size
            actions.clear()
            traces.clear()
            contents.clear()
            count
        }

    override suspend fun countContents(): Int = contentCount()

    override suspend fun deleteAllContents(): Int =
        synchronized(lock) {
            val count = contents.size
            contents.clear()
            count
        }

    override suspend fun deleteContentsOutsideRetention(
        cutoffMillis: Long,
        nowMillis: Long,
    ): Int =
        synchronized(lock) {
            val tokens =
                contents.values
                    .filter { it.createdAtMillis < cutoffMillis || it.createdAtMillis > nowMillis }
                    .map(PendingNotificationActionContentEntity::outboxToken)
            tokens.count { contents.remove(it) != null }
        }

    override suspend fun deleteContentsForPackage(packageName: String): Int =
        synchronized(lock) {
            val tokens =
                actions.values
                    .filter { it.packageName == packageName }
                    .map(PendingNotificationActionEntity::token)
            tokens.count { contents.remove(it) != null }
        }

    fun actionCount(): Int = synchronized(lock) { actions.size }

    fun contentCount(): Int = synchronized(lock) { contents.size }

    suspend fun relation(token: String): PendingNotificationActionRelation? =
        synchronized(lock) {
            actions[token]?.let { action ->
                PendingNotificationActionRelation(
                    action = action,
                    trace = traces[token].orEmpty().toList(),
                    contents = listOfNotNull(contents[token]),
                )
            }
        }

    private fun deleteLocked(token: String): Int =
        if (actions.remove(token) == null) {
            0
        } else {
            traces.remove(token)
            contents.remove(token)
            1
        }
}
