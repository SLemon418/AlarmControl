package com.alarmcontrol.data.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RateListenerKeyHmacProviderInstrumentedTest {
    private val alias = "alarmcontrol.test.notification-listener-key-hmac"
    private lateinit var provider: AndroidKeystoreRateListenerKeyHmacProvider

    @Before
    fun setUp() {
        provider = AndroidKeystoreRateListenerKeyHmacProvider(alias)
        provider.deleteKey()
    }

    @After
    fun tearDown() {
        provider.deleteKey()
    }

    @Test
    fun hmacIsStableNonReversibleAndPersistsAcrossProviderInstances() {
        provider.createKey()
        val first = provider.hmac("listener-key-a".toByteArray())
        val repeated = provider.hmac("listener-key-a".toByteArray())
        val different = provider.hmac("listener-key-b".toByteArray())
        val reopened = AndroidKeystoreRateListenerKeyHmacProvider(alias)

        assertTrue(provider.hasKey())
        assertArrayEquals(first, repeated)
        assertArrayEquals(first, reopened.hmac("listener-key-a".toByteArray()))
        assertFalse(first.contentEquals(different))
        assertNotEquals("listener-key-a", first.toUrlSafeDigest())
        assertTrue(first.toUrlSafeDigest().matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun deletedKeyIsReportedMissing() {
        provider.createKey()

        provider.deleteKey()

        assertFalse(provider.hasKey())
    }
}

private fun ByteArray.toUrlSafeDigest(): String =
    Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
