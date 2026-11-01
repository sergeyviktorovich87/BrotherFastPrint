package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class ReachabilityTest {
    @Test fun freshProcessDoesNotMarkSavedPrinterOfflineBeforeChecking() {
        val state = Reachability()
        assertEquals(Reachability.State.UNKNOWN, state.state)
        assertTrue(state.selectable)
        val ticket = state.beginProbe()
        assertTrue(state.selectable)
        assertTrue(state.finishProbe(ticket, false))
        assertFalse(state.selectable)
    }
    @Test fun aNetworkChangeAllowsRevalidationOfAnOfflinePrinter() {
        val state = Reachability()
        state.finishProbe(state.beginProbe(), false)
        state.invalidate()
        assertTrue(state.selectable)
        state.finishProbe(state.beginProbe(), true)
        assertEquals(Reachability.State.ONLINE, state.state)
    }
    @Test fun cancelledOldNetworkProbeCannotPublishOffline() {
        val state = Reachability()
        val old = state.beginProbe()
        state.invalidate()
        val current = state.beginProbe()
        assertFalse(state.finishProbe(old, false))
        assertTrue(state.finishProbe(current, true))
        assertEquals(Reachability.State.ONLINE, state.state)
    }
    @Test fun successfulPrintCannotBeOverwrittenByAnOlderProbe() {
        val state = Reachability()
        val old = state.beginProbe()
        state.confirmedOnline()
        assertFalse(state.finishProbe(old, false))
        assertEquals(Reachability.State.ONLINE, state.state)
    }
}
