package com.alarmcontrol.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject

internal interface RateListenerKeyHmacProvider {
    fun hasKey(): Boolean

    fun createKey()

    fun hmac(input: ByteArray): ByteArray

    fun deleteKey()
}

/** Android-Keystore HMAC-SHA256 provider; its non-exportable key is never backed up. */
internal class AndroidKeystoreRateListenerKeyHmacProvider
    internal constructor(
        private val keyAlias: String,
    ) : RateListenerKeyHmacProvider {
        @Inject
        constructor() : this(KEY_ALIAS)

        override fun hasKey(): Boolean = keyStore().containsAlias(keyAlias)

        override fun createKey() {
            if (hasKey()) return
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setKeySize(KEY_BITS)
                            .build(),
                    )
                }.generateKey()
        }

        override fun hmac(input: ByteArray): ByteArray {
            val key =
                keyStore().getKey(keyAlias, null) as? SecretKey
                    ?: throw UnrecoverableKeyException("Listener-key HMAC key is unavailable")
            return Mac
                .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
                .apply { init(key) }
                .doFinal(input)
        }

        override fun deleteKey() {
            keyStore().deleteEntry(keyAlias)
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val KEY_ALIAS = "alarmcontrol.notification-listener-key-hmac.v1"
            const val KEY_BITS = 256
        }
    }
