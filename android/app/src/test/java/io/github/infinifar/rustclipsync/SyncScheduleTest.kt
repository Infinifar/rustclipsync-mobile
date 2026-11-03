package io.github.infinifar.rustclipsync

import org.junit.Assert.*
import org.junit.Test

class SyncScheduleTest {
    @Test fun anEnabledSessionReceivesWhileTheAppIsOpen() {
        assertEquals(SyncSchedule.Tick.FOREGROUND_RECEIVE,
            SyncSchedule.tick(true, true, true, false, true, true, true))
        assertEquals(SyncSchedule.Tick.BACKGROUND,
            SyncSchedule.tick(true, true, true, false, false, false, true))
    }
    @Test fun dialogsSendOnlyAndInactiveSessionsDoNotGrantForegroundClipboardAccess() {
        assertEquals(SyncSchedule.Tick.NONE, SyncSchedule.tick(true, true, true, false, true, false, true))
        assertEquals(SyncSchedule.Tick.NONE, SyncSchedule.tick(true, true, true, false, true, true, false))
        for ((enabled, running, connected, busy) in listOf(
            listOf(false, true, true, false), listOf(true, false, true, false),
            listOf(true, true, false, false), listOf(true, true, true, true))) {
            for (foreground in listOf(true, false)) {
                assertEquals(SyncSchedule.Tick.NONE,
                    SyncSchedule.tick(enabled, running, connected, busy, foreground, true, true))
            }
        }
    }
    @Test fun removingTheTaskRetainsAnExplicitlyEnabledConnectedService() {
        assertTrue(SyncSchedule.keepServiceOnTaskRemoval(true, true))
        assertFalse(SyncSchedule.keepServiceOnTaskRemoval(false, true))
        assertFalse(SyncSchedule.keepServiceOnTaskRemoval(true, false))
    }
}
