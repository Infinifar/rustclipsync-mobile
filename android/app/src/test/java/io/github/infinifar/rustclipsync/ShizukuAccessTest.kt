package io.github.infinifar.rustclipsync

import org.junit.Assert.*
import org.junit.Test

class ShizukuAccessTest {
    private class Fixture {
        var permission = ShizukuAccessState.NOT_AUTHORIZED
        var deniedPermanently = false
        val requests = mutableListOf<Int>()
        val visible = mutableListOf<ShizukuAccessState>()
        val access = ShizukuAccess({ permission }, { requests.add(it) }, { deniedPermanently }, { visible.add(it) })
    }

    @Test fun grantedCallbackShowsSuccessWithoutBindingOrReadingClipboard() {
        val f = Fixture()
        f.access.requestPermission()
        assertEquals(listOf(ShizukuAccess.REQUEST_CODE), f.requests)
        assertEquals(ShizukuAccessState.REQUESTING, f.visible.last())
        f.access.refresh() // Activity pause/resume while the dialog is open.
        assertEquals(ShizukuAccessState.REQUESTING, f.visible.last())
        f.permission = ShizukuAccessState.AUTHORIZED
        assertTrue(f.access.result(ShizukuAccess.REQUEST_CODE, true))
        assertEquals(ShizukuAccessState.AUTHORIZED, f.visible.last())
        f.access.requestPermission()
        assertEquals(1, f.requests.size)
    }

    @Test fun denialIsPreservedAcrossRefreshAndManualGrantCanRecover() {
        val f = Fixture()
        f.access.requestPermission()
        f.access.result(ShizukuAccess.REQUEST_CODE, false)
        f.access.refresh()
        assertEquals(ShizukuAccessState.DENIED, f.visible.last())
        f.deniedPermanently = true
        f.access.requestPermission()
        assertEquals(1, f.requests.size)
        f.permission = ShizukuAccessState.AUTHORIZED
        f.access.binderReceived() // A fresh server handshake after application-management authorization.
        assertEquals(ShizukuAccessState.AUTHORIZED, f.visible.last())
    }

    @Test fun unrelatedCallbackAndGrantWithoutLiveAuthorizationCannotAuthorize() {
        val f = Fixture()
        f.access.requestPermission()
        assertFalse(f.access.result(999, true))
        assertEquals(ShizukuAccessState.REQUESTING, f.access.state)
        assertTrue(f.access.result(ShizukuAccess.REQUEST_CODE, true))
        assertEquals(ShizukuAccessState.NOT_AUTHORIZED, f.access.state)
    }

    @Test fun explicitDenialOverridesStaleSdkGrantUntilFreshAuthorization() {
        val f = Fixture()
        f.permission = ShizukuAccessState.AUTHORIZED
        f.access.refresh()
        f.access.result(ShizukuAccess.REQUEST_CODE, false)
        f.access.refresh()
        assertEquals(ShizukuAccessState.DENIED, f.access.state)
        f.access.result(ShizukuAccess.REQUEST_CODE, true)
        assertEquals(ShizukuAccessState.AUTHORIZED, f.access.state)
        f.access.binderDied()
        f.access.binderReceived()
        assertEquals(ShizukuAccessState.AUTHORIZED, f.access.state)
    }

    @Test fun binderDeathRevocationAndUnsupportedModesDoNotLeaveAuthorizedStatus() {
        val f = Fixture()
        f.permission = ShizukuAccessState.AUTHORIZED
        f.access.refresh()
        f.access.binderDied()
        assertEquals(ShizukuAccessState.UNAVAILABLE, f.visible.last())
        f.permission = ShizukuAccessState.NOT_AUTHORIZED
        f.access.refresh()
        assertEquals(ShizukuAccessState.NOT_AUTHORIZED, f.visible.last())
        for (state in listOf(ShizukuAccessState.SHELL_REQUIRED, ShizukuAccessState.UNAVAILABLE)) {
            f.permission = state
            f.access.requestPermission()
            assertEquals(state, f.visible.last())
        }
        assertTrue(f.requests.isEmpty())
    }

    @Test fun unavailableBinderFailsClosedWhenProbingOrRequesting() {
        val observed = mutableListOf<ShizukuAccessState>()
        val brokenProbe = ShizukuAccess({ throw IllegalStateException() }, { fail("request must not run") }, { false }, { observed.add(it) })
        brokenProbe.requestPermission()
        assertEquals(ShizukuAccessState.UNAVAILABLE, observed.last())
        val brokenRequest = ShizukuAccess({ ShizukuAccessState.NOT_AUTHORIZED }, { throw IllegalStateException() }, { false }, { observed.add(it) })
        brokenRequest.requestPermission()
        assertEquals(ShizukuAccessState.UNAVAILABLE, observed.last())
    }
}
