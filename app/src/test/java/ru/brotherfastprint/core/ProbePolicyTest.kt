package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class ProbePolicyTest {
    @Test fun backgroundAndVpnRestrictionsDoNotMakeSavedPrinterUnselectable() {
        for (reason in TcpFailureReason.values()) {
            val state = Reachability()
            val ticket = state.beginProbe()
            if (ProbePolicy.definitiveOffline(reason)) state.finishProbe(ticket, false) else state.invalidate()
            assertEquals(reason.toString(), reason != TcpFailureReason.REFUSED, state.selectable)
        }
    }
    @Test fun laterSuccessfulProbeRestoresPrinterAfterRefusal() {
        val state = Reachability()
        state.finishProbe(state.beginProbe(), false)
        state.finishProbe(state.beginProbe(), true)
        assertTrue(state.selectable)
        assertEquals(Reachability.State.ONLINE, state.state)
    }
}
