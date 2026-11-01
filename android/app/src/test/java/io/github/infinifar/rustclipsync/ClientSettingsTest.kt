package io.github.infinifar.rustclipsync

import org.junit.Assert.*
import org.junit.Test

class ClientSettingsTest {
    @Test fun updateComparisonUsesNumericStableVersions() {
        assertEquals(R.string.updates_new, ProjectUpdates.describe("0.1.3", "v0.1.10").resource)
        assertEquals(R.string.updates_current, ProjectUpdates.describe("0.1.10", "v0.1.3").resource)
        assertEquals(R.string.updates_current, ProjectUpdates.describe("0.1.3", "0.1.3").resource)
        assertEquals(R.string.updates_development, ProjectUpdates.describe("0.1.3-dev", "v0.1.4").resource)
        assertEquals(R.string.updates_unknown_tag, ProjectUpdates.describe("0.1.3", "v0.1.4-rc1").resource)
        assertEquals(R.string.updates_unknown_tag, ProjectUpdates.describe("0.1.3", "9999999999999999999999.0.0").resource)
    }

    @Test fun directionControlsBothForegroundAndBackgroundAdmission() {
        assertTrue(SyncDirection.BOTH.sends && SyncDirection.BOTH.receives)
        assertTrue(SyncDirection.UPLOAD.sends); assertFalse(SyncDirection.UPLOAD.receives)
        assertFalse(SyncDirection.DOWNLOAD.sends); assertTrue(SyncDirection.DOWNLOAD.receives)
    }
}
