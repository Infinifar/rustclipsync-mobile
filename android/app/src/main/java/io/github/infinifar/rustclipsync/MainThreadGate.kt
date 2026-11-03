package io.github.infinifar.rustclipsync

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.infinifar.rustclipsync.bindings.AdapterException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded synchronous adapter for a Rust worker; queued operations are revocable. */
internal class MainThreadGate(private val timeoutMs: Long = 2_000) {
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    fun revoke() { active.set(false) }

    fun <T> call(operation: () -> T): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val task = FutureTask {
            if (!active.get() || SystemClock.elapsedRealtime() >= deadline) throw AdapterException.Unavailable()
            operation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return try { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { cancel(task); throw AdapterException.Timeout() }
        catch (error: ExecutionException) { throw (error.cause as? AdapterException ?: AdapterException.Unavailable()) }
        catch (_: InterruptedException) {
            cancel(task)
            Thread.currentThread().interrupt()
            throw AdapterException.Unavailable()
        }
    }
    private fun cancel(task: FutureTask<*>) { task.cancel(false); main.removeCallbacks(task) }
}
