package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class PrintRecoveryTest {
    @Test fun queuedJobCanBeClaimedOnceEvenWhenCallbackAndScanOverlap() {
        for (reconnect in listOf(false, true)) {
            var managed = false
            var starts = 0
            repeat(2) {
                if (PrintRecovery.decide(PrintRecovery.State.QUEUED, managed, reconnect) == PrintRecovery.Action.START) {
                    starts++; managed = true
                }
            }
            assertEquals(1, starts)
        }
    }
    @Test fun processRestartNeverReplaysPossiblyPrintedPages() {
        assertEquals(PrintRecovery.Action.FAIL_UNCERTAIN,
            PrintRecovery.decide(PrintRecovery.State.IN_FLIGHT, managed = false, reconnect = true))
        assertEquals(PrintRecovery.Action.LEAVE,
            PrintRecovery.decide(PrintRecovery.State.IN_FLIGHT, managed = false, reconnect = false))
    }
    @Test fun liveWorkerIsNotCancelledOrSubmittedAgainByRecovery() {
        for (state in PrintRecovery.State.values()) for (reconnect in listOf(false, true)) {
            assertEquals(PrintRecovery.Action.LEAVE, PrintRecovery.decide(state, managed = true, reconnect = reconnect))
        }
    }
    @Test fun completedFailedCancelledAndUnscheduledStatesAreLeftAlone() {
        for (reconnect in listOf(false, true)) {
            assertEquals(PrintRecovery.Action.LEAVE,
                PrintRecovery.decide(PrintRecovery.State.TERMINAL, managed = false, reconnect = reconnect))
        }
    }
}
