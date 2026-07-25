package com.alarmcontrol.di

import com.alarmcontrol.core.filtering.RuleAnalyzer
import com.alarmcontrol.notifications.DefaultRuleAnalyzer
import com.alarmcontrol.notifications.Matcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the pure [Matcher] engine. It carries no DI annotations itself (`:notifications` has no
 * Hilt dependency), so `:app` — the composition root — supplies it. Stateless, hence a singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideMatcher(): Matcher = Matcher()

    @Provides
    @Singleton
    fun provideRuleAnalyzer(): RuleAnalyzer = DefaultRuleAnalyzer()
}
