package com.alarmcontrol.core.result

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RunCatchingPreservingCancellationTest {
    @Test(expected = CancellationException::class)
    fun `rethrows coroutine cancellation`() {
        runCatchingPreservingCancellation<Unit> { throw CancellationException("cancelled") }
    }

    @Test
    fun `captures an ordinary failure`() {
        val failure = IllegalStateException("failed")

        val result = runCatchingPreservingCancellation<Unit> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }

    @Test(expected = AssertionError::class)
    fun `does not swallow virtual machine errors`() {
        runCatchingPreservingCancellation<Unit> { throw AssertionError("fatal") }
    }

    @Test
    fun `returns a successful value`() {
        assertEquals(42, runCatchingPreservingCancellation { 42 }.getOrThrow())
    }
}
