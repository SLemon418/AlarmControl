package com.alarmcontrol.data.di

import com.alarmcontrol.data.security.AndroidKeystoreNotificationContentCipher
import com.alarmcontrol.data.security.NotificationContentCipher
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
}
