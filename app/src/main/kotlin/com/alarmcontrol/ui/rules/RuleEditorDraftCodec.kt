package com.alarmcontrol.ui.rules

import com.alarmcontrol.core.filtering.MAX_CONDITION_VALUE_CHARS
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_DEPTH
import com.alarmcontrol.core.filtering.MAX_RULE_CONDITION_NODES
import com.alarmcontrol.core.filtering.RateScope
import com.alarmcontrol.core.filtering.RuleExecutionMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal const val RULE_EDITOR_DRAFT_SAVED_STATE_KEY = "rule_editor_draft_v1"

/**
 * Bounded, versioned process-death state for the rule editor.
 *
 * Only the user-authored rule definition is retained. Simulator notification title/text, computed
 * traces, warnings, and messages are deliberately excluded.
 */
internal object RuleEditorDraftCodec {
    fun encode(state: RuleEditorState): String? =
        safely {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeBoundedString(state.id, MAX_ID_CHARS)
                output.writeBoundedString(state.name, MAX_DRAFT_NAME_CHARS)
                output.writeBoolean(state.enabled)
                output.writeBoundedString(state.priority, MAX_NUMBER_CHARS)
                output.writeEnum(state.action)
                output.writeEnum(state.executionMode)
                output.writeBoundedString(state.snoozeMinutes, MAX_NUMBER_CHARS)
                output.writeBoolean(state.hasUnsavedChanges)
                output.writeBoolean(state.showDiscardConfirmation)
                output.writeEnum(state.editorMode)
                output.writeBoundedString(state.guidedPackageName, MAX_CONDITION_VALUE_CHARS)
                output.writeBoundedString(state.guidedAppName, MAX_APP_LABEL_CHARS)
                output.writeNullableBoundedString(state.guidedChannelId, MAX_CONDITION_VALUE_CHARS)
                output.writeNullableBoundedString(state.guidedChannelName, MAX_CHANNEL_NAME_CHARS)
                output.writeEnum(state.guidedScope)
                output.writeBoolean(state.guidedTimeEnabled)
                output.writeBoundedString(state.guidedStartTime, MAX_TIME_CHARS)
                output.writeBoundedString(state.guidedEndTime, MAX_TIME_CHARS)
                output.writeBoolean(state.guidedFrequencyEnabled)
                output.writeBoundedString(state.guidedFrequencyMinutes, MAX_NUMBER_CHARS)
                output.writeBoundedString(state.guidedFrequencyThreshold, MAX_NUMBER_CHARS)
                output.writeNode(state.root, depth = 1, budget = NodeBudget())
            }
            val bytes = buffer.toByteArray()
            require(bytes.size <= MAX_BYTES)
            try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } finally {
                bytes.fill(0)
            }
        }

    fun decode(encoded: String): RuleEditorState? {
        if (encoded.length !in 1..MAX_ENCODED_CHARS) return null
        return safely {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(bytes.size <= MAX_BYTES)
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readInt() == MAGIC)
                    require(input.readInt() == VERSION)
                    val state =
                        RuleEditorState(
                            id = input.readBoundedString(MAX_ID_CHARS),
                            name = input.readBoundedString(MAX_DRAFT_NAME_CHARS),
                            enabled = input.readBoolean(),
                            priority = input.readBoundedString(MAX_NUMBER_CHARS),
                            action = input.readEnum(),
                            executionMode = input.readEnum<RuleExecutionMode>(),
                            snoozeMinutes = input.readBoundedString(MAX_NUMBER_CHARS),
                            hasUnsavedChanges = input.readBoolean(),
                            showDiscardConfirmation = input.readBoolean(),
                            editorMode = input.readEnum(),
                            guidedPackageName = input.readBoundedString(MAX_CONDITION_VALUE_CHARS),
                            guidedAppName = input.readBoundedString(MAX_APP_LABEL_CHARS),
                            guidedChannelId = input.readNullableBoundedString(MAX_CONDITION_VALUE_CHARS),
                            guidedChannelName = input.readNullableBoundedString(MAX_CHANNEL_NAME_CHARS),
                            guidedScope = input.readEnum(),
                            guidedTimeEnabled = input.readBoolean(),
                            guidedStartTime = input.readBoundedString(MAX_TIME_CHARS),
                            guidedEndTime = input.readBoundedString(MAX_TIME_CHARS),
                            guidedFrequencyEnabled = input.readBoolean(),
                            guidedFrequencyMinutes = input.readBoundedString(MAX_NUMBER_CHARS),
                            guidedFrequencyThreshold = input.readBoundedString(MAX_NUMBER_CHARS),
                            root =
                                requireNotNull(
                                    input.readNode(depth = 1, budget = NodeBudget()) as? GroupNode,
                                ),
                        )
                    require(input.available() == 0)
                    state
                }
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun DataOutputStream.writeNode(
        node: ConditionNode,
        depth: Int,
        budget: NodeBudget,
    ) {
        budget.consume(depth)
        when (node) {
            is GroupNode -> {
                writeByte(TYPE_GROUP)
                writeBoolean(node.anyOf)
                require(node.children.size <= MAX_RULE_CONDITION_NODES)
                writeInt(node.children.size)
                node.children.forEach { writeNode(it, depth + 1, budget) }
            }
            is NotNode -> {
                writeByte(TYPE_NOT)
                writeNode(node.child, depth + 1, budget)
            }
            is LeafNode -> {
                writeByte(TYPE_LEAF)
                writeEnum(node.kind)
                writeBoundedString(node.value, MAX_CONDITION_VALUE_CHARS)
                writeBoolean(node.ignoreCase)
            }
            is TimeWindowNode -> {
                writeByte(TYPE_TIME)
                writeBoundedString(node.start, MAX_TIME_CHARS)
                writeBoundedString(node.end, MAX_TIME_CHARS)
            }
            is RateNode -> {
                writeByte(TYPE_RATE)
                writeEnum(node.scope)
                writeBoundedString(node.windowMinutes, MAX_NUMBER_CHARS)
                writeBoundedString(node.threshold, MAX_NUMBER_CHARS)
            }
        }
    }

    private fun DataInputStream.readNode(
        depth: Int,
        budget: NodeBudget,
    ): ConditionNode {
        budget.consume(depth)
        return when (readUnsignedByte()) {
            TYPE_GROUP -> {
                val anyOf = readBoolean()
                val childCount = readInt()
                require(childCount in 0..MAX_RULE_CONDITION_NODES)
                GroupNode(
                    key = nextNodeKey(),
                    anyOf = anyOf,
                    children = List(childCount) { readNode(depth + 1, budget) },
                )
            }
            TYPE_NOT -> NotNode(nextNodeKey(), readNode(depth + 1, budget))
            TYPE_LEAF ->
                LeafNode(
                    key = nextNodeKey(),
                    kind = readEnum(),
                    value = readBoundedString(MAX_CONDITION_VALUE_CHARS),
                    ignoreCase = readBoolean(),
                )
            TYPE_TIME ->
                TimeWindowNode(
                    key = nextNodeKey(),
                    start = readBoundedString(MAX_TIME_CHARS),
                    end = readBoundedString(MAX_TIME_CHARS),
                )
            TYPE_RATE ->
                RateNode(
                    key = nextNodeKey(),
                    scope = readEnum<RateScope>(),
                    windowMinutes = readBoundedString(MAX_NUMBER_CHARS),
                    threshold = readBoundedString(MAX_NUMBER_CHARS),
                )
            else -> throw IllegalArgumentException("Unknown condition node")
        }
    }

    private fun DataOutputStream.writeBoundedString(
        value: String,
        maxChars: Int,
    ) {
        require(value.length <= maxChars)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxChars * MAX_UTF8_BYTES_PER_CHAR)
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataInputStream.readBoundedString(maxChars: Int): String {
        val byteCount = readInt()
        require(byteCount in 0..(maxChars * MAX_UTF8_BYTES_PER_CHAR))
        val bytes = ByteArray(byteCount)
        readFully(bytes)
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .also { decoded -> require(decoded.length <= maxChars) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeNullableBoundedString(
        value: String?,
        maxChars: Int,
    ) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value, maxChars)
    }

    private fun DataInputStream.readNullableBoundedString(maxChars: Int): String? =
        if (readBoolean()) readBoundedString(maxChars) else null

    private fun DataOutputStream.writeEnum(value: Enum<*>) {
        writeBoundedString(value.name, MAX_ENUM_CHARS)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readBoundedString(MAX_ENUM_CHARS)
        return requireNotNull(enumValues<T>().singleOrNull { it.name == name })
    }

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }

    private class NodeBudget {
        private var remaining = MAX_RULE_CONDITION_NODES

        fun consume(depth: Int) {
            require(depth <= MAX_RULE_CONDITION_DEPTH)
            require(remaining > 0)
            remaining--
        }
    }

    private const val MAGIC = 0x41435244
    private const val VERSION = 1
    private const val MAX_BYTES = 256 * 1_024
    private const val MAX_ENCODED_CHARS = ((MAX_BYTES + 2) / 3) * 4
    private const val MAX_UTF8_BYTES_PER_CHAR = 4
    private const val MAX_ID_CHARS = 256
    private const val MAX_DRAFT_NAME_CHARS = 1_024
    private const val MAX_APP_LABEL_CHARS = 100
    private const val MAX_CHANNEL_NAME_CHARS = 1_000
    private const val MAX_NUMBER_CHARS = 32
    private const val MAX_TIME_CHARS = 16
    private const val MAX_ENUM_CHARS = 64

    private const val TYPE_GROUP = 1
    private const val TYPE_NOT = 2
    private const val TYPE_LEAF = 3
    private const val TYPE_TIME = 4
    private const val TYPE_RATE = 5
}
