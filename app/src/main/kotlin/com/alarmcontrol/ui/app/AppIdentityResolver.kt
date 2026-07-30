package com.alarmcontrol.ui.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Presentation-only installed-app identity; notification content is never involved. */
data class AppIdentityUi(
    val label: String,
    val icon: ImageBitmap?,
    val isPackageFallback: Boolean = false,
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
        private val cache =
            object : LruCache<String, AppIdentityUi>(MAX_CACHE_KIB) {
                override fun sizeOf(
                    key: String,
                    value: AppIdentityUi,
                ): Int =
                    value.icon
                        ?.let { icon ->
                            (icon.width.toLong() * icon.height * BYTES_PER_PIXEL / BYTES_PER_KIB)
                                .coerceIn(1, Int.MAX_VALUE.toLong())
                                .toInt()
                        } ?: 1
            }

        override fun resolve(packageName: String): AppIdentityUi =
            synchronized(cache) {
                cache.get(packageName) ?: load(packageName).also { cache.put(packageName, it) }
            }

        private fun load(packageName: String): AppIdentityUi =
            try {
                val packageManager = context.packageManager
                val info = packageManager.applicationInfo(packageName)
                val safeLabel = packageManager.getApplicationLabel(info).toString().safeAppLabel("")
                val usesPackageFallback = safeLabel.isBlank()
                val label = safeLabel.ifBlank { packageName }
                val iconSize = (ICON_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
                val icon =
                    try {
                        packageManager.getApplicationIcon(info).toBitmap(iconSize, iconSize).asImageBitmap()
                    } catch (_: RuntimeException) {
                        null
                    }
                AppIdentityUi(label = label, icon = icon, isPackageFallback = usesPackageFallback)
            } catch (_: PackageManager.NameNotFoundException) {
                AppIdentityUi(label = packageName, icon = null, isPackageFallback = true)
            } catch (_: RuntimeException) {
                // OEM PackageManager/icon decoding failures must not break an entire UI flow.
                AppIdentityUi(label = packageName, icon = null, isPackageFallback = true)
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
            const val MAX_CACHE_KIB = 8 * 1_024
            const val BYTES_PER_PIXEL = 4L
            const val BYTES_PER_KIB = 1_024L
        }
    }

/** Keeps untrusted installed-app labels from injecting controls or monopolising list layouts. */
internal fun String.safeAppLabel(fallback: String): String {
    val sanitized =
        buildString(minOf(length, MAX_APP_LABEL_CHARS)) {
            for (character in this@safeAppLabel) {
                if (length >= MAX_APP_LABEL_CHARS) break
                if (!character.isISOControl() && character !in BIDI_CONTROL_CHARACTERS) {
                    append(character)
                }
            }
        }.dropLastWhile(Character::isHighSurrogate)
            .trim()
    return sanitized.ifBlank { fallback }
}

private const val MAX_APP_LABEL_CHARS = 100
private val BIDI_CONTROL_CHARACTERS =
    setOf(
        '\u200E',
        '\u200F',
        '\u202A',
        '\u202B',
        '\u202C',
        '\u202D',
        '\u202E',
        '\u2066',
        '\u2067',
        '\u2068',
        '\u2069',
    )

@Module
@InstallIn(SingletonComponent::class)
abstract class AppIdentityModule {
    @Binds
    @Singleton
    abstract fun bindAppIdentityResolver(impl: AndroidAppIdentityResolver): AppIdentityResolver
}
