package ru.brotherfastprint.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DispatchDeadlineTest {
    @Test fun normalHandlerDelayHasNoLateDispatch() {
        assertEquals(DispatchDeadline.Delay(0, 0, 0), DispatchDeadline(100_000, 80_000, 5_000).measure(105_000, 85_000))
    }
    @Test fun sleepIsNotMisreportedAsMainThreadStall() {
        assertEquals(DispatchDeadline.Delay(120_010, 10, 120_000),
            DispatchDeadline(100_000, 80_000, 5_000).measure(225_010, 85_010))
    }
    @Test fun awakeQueueDelayRemainsVisible() {
        assertEquals(DispatchDeadline.Delay(118_000, 118_000, 0),
            DispatchDeadline(100_000, 80_000, 5_000).measure(223_000, 203_000))
    }
    @Test fun mixedSleepAndSchedulingDelayAreSeparate() {
        assertEquals(DispatchDeadline.Delay(118_000, 3_000, 115_000),
            DispatchDeadline(100_000, 80_000, 5_000).measure(223_000, 88_000))
    }
    @Test(expected = IllegalArgumentException::class) fun overflowingDeadlineIsRejected() {
        DispatchDeadline(Long.MAX_VALUE - 5, 0, 10)
    }
}
