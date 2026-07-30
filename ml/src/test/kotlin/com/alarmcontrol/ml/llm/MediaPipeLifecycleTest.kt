package com.alarmcontrol.ml.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MediaPipeLifecycleTest {
    @Test
    fun `close between inference acquisition and session creation defers engine teardown`() {
        val events = mutableListOf<String>()
        val engine = FakeNativeResource("engine", events)
        val lifecycle = DeferredCloseResource<FakeNativeResource>(FakeNativeResource::close)
        lifecycle.replace { engine }

        val lease = requireNotNull(lifecycle.acquire())
        lifecycle.close()

        assertSame(engine, lease.value)
        assertFalse(engine.closed)
        assertNull(lifecycle.acquire())

        lease.release { events += "session" }

        assertTrue(engine.closed)
        assertEquals(listOf("session", "engine"), events)
    }

    @Test
    fun `session cleanup failure still releases a deferred engine close exactly once`() {
        val events = mutableListOf<String>()
        val engine = FakeNativeResource("engine", events)
        val lifecycle = DeferredCloseResource<FakeNativeResource>(FakeNativeResource::close)
        lifecycle.replace { engine }
        val lease = requireNotNull(lifecycle.acquire())
        lifecycle.close()

        lease.release {
            events += "session"
            error("native session close failed")
        }
        lease.release { events += "duplicate" }

        assertTrue(engine.closed)
        assertEquals(listOf("session", "engine"), events)
    }

    @Test
    fun `close requested while replacement loads rejects and closes the replacement`() {
        val events = mutableListOf<String>()
        val replacement = FakeNativeResource("replacement", events)
        val lifecycle = DeferredCloseResource<FakeNativeResource>(FakeNativeResource::close)

        lifecycle.replace {
            lifecycle.close()
            replacement
        }

        assertTrue(replacement.closed)
        assertNull(lifecycle.acquire())
        assertEquals(listOf("replacement"), events)
    }

    @Test
    fun `replacement waits until deferred native close has completed`() {
        val events = mutableListOf<String>()
        val original = FakeNativeResource("original", events)
        val replacement = FakeNativeResource("replacement", events)
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val lifecycle =
            DeferredCloseResource<FakeNativeResource> { resource ->
                if (resource === original) {
                    closeStarted.countDown()
                    check(allowClose.await(1, TimeUnit.SECONDS))
                }
                resource.close()
            }
        lifecycle.replace { original }
        val lease = requireNotNull(lifecycle.acquire())
        lifecycle.close()

        val releaseThread = thread(start = true) { lease.release() }
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        val replacementThread =
            thread(start = true) {
                lifecycle.replace {
                    replacementStarted.countDown()
                    replacement
                }
            }

        assertFalse(replacementStarted.await(100, TimeUnit.MILLISECONDS))
        allowClose.countDown()
        releaseThread.join(1_000)
        replacementThread.join(1_000)

        assertFalse(releaseThread.isAlive)
        assertFalse(replacementThread.isAlive)
        assertTrue(replacementStarted.await(1, TimeUnit.SECONDS))
        assertSame(replacement, requireNotNull(lifecycle.acquire()).value)
        assertEquals(listOf("original"), events)
    }
}

private class FakeNativeResource(
    private val name: String,
    private val events: MutableList<String>,
) {
    var closed: Boolean = false
        private set

    fun close() {
        check(!closed)
        closed = true
        events += name
    }
}
