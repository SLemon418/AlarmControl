package com.alarmcontrol.automation

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.alarmcontrol.core.automation.AutomationSource
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.result.runCatchingPreservingCancellation
import com.alarmcontrol.core.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile for the filtering master switch (CLAUDE.md §7): the user pauses/resumes the
 * engine without changing any rule. A first-party action, so it uses [ProfileController.toggle]
 * directly — it is **not** gated by the external-automation opt-in. Reads/writes go through `:core`
 * (`:data` persists). The tile mirrors the independent master setting.
 */
@AndroidEntryPoint
class MasterSwitchTileService : TileService() {
    @Inject lateinit var profileController: ProfileController

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject
    @Dispatcher(AppDispatcher.IO)
    lateinit var dispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    override fun onStartListening() {
        super.onStartListening()
        launchSafely { refreshTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggleFiltering() }
        } else {
            toggleFiltering()
        }
    }

    private fun toggleFiltering() {
        launchSafely {
            profileController.toggle(
                profileId = null,
                source = AutomationSource.QUICK_SETTINGS,
            )
            refreshTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refreshTile() {
        val tile = qsTile ?: return
        val active = settingsRepository.filteringEnabled.first()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.updateTile()
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { block() }
                .onFailure { Log.w(TAG, "Quick Settings update failed") }
        }
    }

    private companion object {
        const val TAG = "AlarmControlTile"
    }
}
