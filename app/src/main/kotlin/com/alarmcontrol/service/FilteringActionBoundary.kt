package com.alarmcontrol.service

import com.alarmcontrol.core.settings.FilteringActionGate

/**
 * Claims current listener work while the filtering gate is held, so a refresh cannot close and
 * reopen between the token claim and its platform action.
 */
internal suspend fun commitFilteringAction(
    token: NotificationProcessingCoordinator.ProcessingToken,
    filteringActionGate: FilteringActionGate,
    beforeCommit: () -> Unit = {},
    tokenCommit: (action: () -> Unit) -> Boolean = token::commit,
    action: () -> Unit,
): Boolean {
    var committed = false
    filteringActionGate.runIfAllowed {
        beforeCommit()
        committed = tokenCommit(action)
    }
    return committed
}

/**
 * Publishes and invalidates under the coordinator boundary, then acknowledges after releasing it.
 * This preserves the gate-to-coordinator lock order used by [commitFilteringAction].
 */
internal fun publishRuleSnapshot(
    coordinator: NotificationProcessingCoordinator,
    filteringActionGate: FilteringActionGate,
    requestId: Long,
    publish: () -> Unit,
) {
    coordinator.invalidateAllAndUpdate(publish)
    filteringActionGate.acknowledgeRuleRefresh(requestId)
}
