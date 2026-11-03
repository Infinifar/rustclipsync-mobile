package io.github.infinifar.rustclipsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic credentials and no native/clipboard access. Lifecycle checks require an isolated test app. */
@RunWith(AndroidJUnit4::class)
class ConnectionRestoreTest {
    private class Store : ConnectionStore {
        var value: ConnectionCredentials? = ConnectionCredentials("https://synthetic.invalid", "synthetic", "synthetic-password")
        var unreadable = false
        var clears = 0
        override fun load(): ConnectionCredentials? {
            check(!unreadable) { "synthetic storage failure" }
            return value
        }
        override fun save(value: ConnectionCredentials) { this.value = value }
        override fun clear() { value = null; unreadable = false; clears++ }
    }
    private suspend fun controller(store: Store): SessionController = withContext(Dispatchers.Main) {
        SessionController(InstrumentationRegistry.getInstrumentation().targetContext,
            resources = { error("restoration must not open clipboard or native session") }, credentials = { store })
    }
    private suspend fun ready(controller: SessionController) = withTimeout(5_000) {
        while (withContext(Dispatchers.Main) { controller.credentialsBusy || controller.busy }) delay(10)
    }

    @Test fun newControllerRestoresConnectionWithoutConnectingAndForgetSurvivesReopen() = runBlocking {
        val store = Store()
        val first = controller(store)
        ready(first)
        withContext(Dispatchers.Main) {
            assertEquals("https://synthetic.invalid", first.connectionServer)
            assertEquals("synthetic", first.connectionUsername)
            assertEquals("synthetic-password", first.connectionPassword)
            assertTrue(first.savedCredentials)
            assertFalse(first.connected)
        }
        val second = controller(store)
        ready(second)
        withContext(Dispatchers.Main) {
            assertEquals(first.connectionPassword, second.connectionPassword)
            second.forgetCredentials()
        }
        ready(second)
        val third = controller(store)
        ready(third)
        withContext(Dispatchers.Main) {
            assertEquals(1, store.clears)
            assertEquals("", third.connectionServer)
            assertEquals("", third.connectionPassword)
            assertFalse(third.savedCredentials)
        }
    }

    @Test fun readFailureDoesNotSilentlyEraseAndRequiresExplicitForget() = runBlocking {
        val store = Store().apply { unreadable = true }
        val controller = controller(store)
        ready(controller)
        withContext(Dispatchers.Main) {
            assertTrue(controller.credentialLoadFailed)
            assertEquals(R.string.credentials_load_failed, controller.credentialMessage!!.resource)
            assertNotNull(store.value)
            assertEquals(0, store.clears)
            controller.forgetCredentials()
        }
        ready(controller)
        withContext(Dispatchers.Main) {
            assertFalse(controller.credentialLoadFailed)
            assertEquals(1, store.clears)
            assertNull(store.value)
        }
    }
    @Test fun stoppingASessionOrTimingOutDoesNotEraseBackgroundOptIn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("background-running-v2", 0)
        val had = prefs.contains("enabled")
        val previous = prefs.getBoolean("enabled", false)
        try {
            val first = controller(Store())
            ready(first)
            withContext(Dispatchers.Main) {
                first.configureBackgroundRunning(true)
                first.close()
                assertTrue(first.backgroundEnabled)
                first.serviceTimedOut()
                assertTrue(first.backgroundEnabled)
                assertFalse(first.backgroundRunning)
            }
            val reopened = controller(Store())
            ready(reopened)
            withContext(Dispatchers.Main) {
                assertTrue(reopened.backgroundEnabled)
                assertFalse(reopened.connected)
                reopened.configureBackgroundRunning(false)
            }
            assertFalse(prefs.getBoolean("enabled", true))
        } finally {
            prefs.edit().apply { if (had) putBoolean("enabled", previous) else remove("enabled") }.commit()
        }
    }

    @Test fun pauseSurvivesReopeningWithoutDiscardingCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("sync-settings", 0)
        val had = prefs.contains("paused")
        val previous = prefs.getBoolean("paused", false)
        try {
            val first = controller(Store())
            ready(first)
            withContext(Dispatchers.Main) { first.setPaused(true); assertTrue(first.paused) }
            val reopened = controller(Store())
            ready(reopened)
            withContext(Dispatchers.Main) {
                assertTrue(reopened.paused)
                assertEquals("synthetic-password", reopened.connectionPassword)
                reopened.setPaused(false)
                assertFalse(reopened.paused)
            }
        } finally {
            prefs.edit().apply { if (had) putBoolean("paused", previous) else remove("paused") }.commit()
        }
    }

}
