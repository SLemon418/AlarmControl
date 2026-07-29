package com.alarmcontrol.data.di

import com.alarmcontrol.core.filtering.RateListenerKeyHasher
import com.alarmcontrol.data.security.AndroidKeystoreNotificationContentCipher
import com.alarmcontrol.data.security.AndroidKeystoreRateListenerKeyHmacProvider
import com.alarmcontrol.data.security.NotificationContentCipher
import com.alarmcontrol.data.security.RateListenerKeyHasherImpl
import com.alarmcontrol.data.security.RateListenerKeyHmacProvider
import com.alarmcontrol.data.security.RateOccurrenceDataCleaner
import com.alarmcontrol.data.security.RoomRateOccurrenceDataCleaner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecurityModule {
    @Binds
    @Singleton
    abstract fun bindNotificationContentCipher(
        impl: AndroidKeystoreNotificationContentCipher,
    ): NotificationContentCipher

    @Binds
    @Singleton
    abstract fun bindRateListenerKeyHmacProvider(
        impl: AndroidKeystoreRateListenerKeyHmacProvider,
    ): RateListenerKeyHmacProvider

    @Binds
    @Singleton
    abstract fun bindRateListenerKeyHasher(impl: RateListenerKeyHasherImpl): RateListenerKeyHasher

    @Binds
    @Singleton
    abstract fun bindRateOccurrenceDataCleaner(impl: RoomRateOccurrenceDataCleaner): RateOccurrenceDataCleaner
}
