package dev.tinycast.android

import dev.tinycast.android.extensions.withDetailImageTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.*
import org.junit.Test

class ExtensionDetailImageTimeoutTest {
    @Test fun successfulWorkRetainsItsResult() = runBlocking {
        val expected = Any()
        assertSame(expected, withDetailImageTimeout { expected })
    }

    @Test fun deadlineCancelsThePendingWorkBeforeReportingAnImageFailure() = runBlocking {
        val released = AtomicBoolean(false)
        try {
            withDetailImageTimeout(25) {
                suspendCancellableCoroutine<Any> { continuation ->
                    continuation.invokeOnCancellation { released.set(true) }
                }
            }
            fail("A never-finishing image operation must time out")
        } catch (failure: IOException) {
            assertEquals("Image request timed out", failure.message)
            assertTrue("Cancellation must release the pending request", released.get())
        }
    }

    @Test fun cancellationByTheViewOwnerRemainsCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        val released = AtomicBoolean(false)
        val job = launch {
            try {
                withDetailImageTimeout {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { released.set(true) }
                        started.complete(Unit)
                    }
                }
                result.complete("unexpected completion")
            } catch (cancelled: CancellationException) {
                result.complete("owner cancelled")
                throw cancelled
            } catch (_: IOException) {
                result.complete("wrong image failure")
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals("owner cancelled", result.await())
        assertTrue(released.get())
    }
}
