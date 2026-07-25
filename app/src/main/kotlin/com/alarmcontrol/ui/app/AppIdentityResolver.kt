package com.alarmcontrol.ui.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Presentation-only installed-app identity; notification content is never involved. */
data class AppIdentityUi(
    val label: String,
    val icon: ImageBitmap?,
)

/** Resolves package metadata outside Composables so PackageManager I/O never runs during layout. */
fun interface AppIdentityResolver {
    fun resolve(packageName: String): AppIdentityUi
}

@Singleton
class AndroidAppIdentityResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppIdentityResolver {
        private val cache = ConcurrentHashMap<String, AppIdentityUi>()

        override fun resolve(packageName: String): AppIdentityUi = cache.getOrPut(packageName) { load(packageName) }

        private fun load(packageName: String): AppIdentityUi =
            try {
                val packageManager = context.packageManager
                val info = packageManager.applicationInfo(packageName)
                val label = packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
                val iconSize = (ICON_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
                val icon = packageManager.getApplicationIcon(info).toBitmap(iconSize, iconSize).asImageBitmap()
                AppIdentityUi(label = label, icon = icon)
            } catch (_: PackageManager.NameNotFoundException) {
                AppIdentityUi(label = packageName, icon = null)
            }

        @Suppress("DEPRECATION")
        private fun PackageManager.applicationInfo(packageName: String): ApplicationInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                getApplicationInfo(packageName, 0)
            }

        private companion object {
            const val ICON_DP = 40
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AppIdentityModule {
    @Binds
    @Singleton
    abstract fun bindAppIdentityResolver(impl: AndroidAppIdentityResolver): AppIdentityResolver
}
