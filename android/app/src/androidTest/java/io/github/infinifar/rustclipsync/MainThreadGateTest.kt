package io.github.infinifar.rustclipsync

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.infinifar.rustclipsync.bindings.AdapterException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic callbacks only: these tests never request an OS clipboard or secure key. */
@RunWith(AndroidJUnit4::class)
class MainThreadGateTest {
    @Test fun workerCallbackRunsOnMainAndReturnsResult() {
        val gate = MainThreadGate()
        assertEquals(42, gate.call {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            42
        })
    }
    @Test fun timedOutQueuedCallbackCannotExecuteLater() {
        val gate = MainThreadGate(timeoutMs = 50)
        val calls = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            // Deliberately occupy the UI queue in this isolated test to induce an expired request.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val result = worker.submit<Throwable?> {
                    try { gate.call { calls.incrementAndGet() }; null } catch (error: Exception) { error }
                }
                assertTrue(result.get(5, TimeUnit.SECONDS) is AdapterException.Timeout)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            assertEquals(0, calls.get())
        } finally { worker.shutdownNow() }
    }
    @Test fun revokedGateRejectsCallbackWithoutExecutingIt() {
        val gate = MainThreadGate()
        val calls = AtomicInteger()
        gate.revoke()
        try { gate.call { calls.incrementAndGet() }; fail("revoked gate accepted callback") }
        catch (_: AdapterException.Unavailable) { }
        assertEquals(0, calls.get())
    }
}
