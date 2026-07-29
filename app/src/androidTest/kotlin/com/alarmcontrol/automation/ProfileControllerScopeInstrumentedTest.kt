package com.alarmcontrol.automation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmcontrol.service.DeviceValidationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileControllerScopeInstrumentedTest {
    @Test
    fun applicationGraphSharesOneControllerAcrossEntryPointLookups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val first =
            EntryPointAccessors
                .fromApplication(context, DeviceValidationEntryPoint::class.java)
                .profileController()
        val second =
            EntryPointAccessors
                .fromApplication(context, DeviceValidationEntryPoint::class.java)
                .profileController()

        assertSame(first, second)
    }
}
