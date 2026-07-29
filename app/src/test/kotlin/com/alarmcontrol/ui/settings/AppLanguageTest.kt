package com.alarmcontrol.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `empty or unsupported locale uses system default`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("fr-FR"))
        assertTrue(AppLanguage.SYSTEM.toLocaleList().isEmpty)
    }

    @Test
    fun `regional English and Korean locales map to supported language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguageTags("ko-KR"))
        assertEquals("en", AppLanguage.ENGLISH.toLocaleList().toLanguageTags())
        assertEquals("ko", AppLanguage.KOREAN.toLocaleList().toLanguageTags())
    }
}
