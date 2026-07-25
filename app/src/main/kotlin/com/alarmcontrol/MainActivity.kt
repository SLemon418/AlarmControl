package com.alarmcontrol

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.alarmcontrol.ui.AlarmControlApp
import com.alarmcontrol.ui.privacy.LocalSensitiveWindowController
import com.alarmcontrol.ui.privacy.SensitiveWindowController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val sensitiveWindowController =
                remember {
                    SensitiveWindowController { protected ->
                        if (protected) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            CompositionLocalProvider(LocalSensitiveWindowController provides sensitiveWindowController) {
                AlarmControlApp()
            }
        }
    }
}
