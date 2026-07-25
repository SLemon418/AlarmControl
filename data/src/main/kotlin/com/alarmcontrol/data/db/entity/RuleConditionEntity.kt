package com.alarmcontrol.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alarmcontrol.data.db.model.StoredConditionType

/**
 * One node of a [RuleEntity]'s condition tree (CLAUDE.md §6). Leaves carry a [value] (a package id,
 * substring, `"startMinute,endMinute"` for a time window, …); composites
 * ([StoredConditionType.ALL_OF]/`ANY_OF`/`NOT`) instead have child rows that reference them via
 * [parentId]. A `null` [parentId] marks a root; [position] orders siblings.
 *
 * [negate] is retained only for rows written by the original flat schema (leaf-level NOT) and is
 * honored on read; new rows model `NOT` as a composite node.
 */
@Entity(
    tableName = "rule_conditions",
    foreignKeys = [
        ForeignKey(
            entity = RuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id")],
)
data class RuleConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    /** Parent node's id; `null` for the tree root. */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    /** Order among siblings (defaults to 0 for rows migrated from the old flat schema). */
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
    @ColumnInfo(name = "type") val type: StoredConditionType,
    /** Comparison value for leaves, interpreted per [type]; empty for composites. */
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "ignore_case") val ignoreCase: Boolean = true,
    /** Legacy leaf-level NOT (old flat schema); honored on read. */
    @ColumnInfo(name = "negate") val negate: Boolean = false,
)
