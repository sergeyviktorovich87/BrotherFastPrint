package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class DuplexPlanTest {
    @Test fun oddCopiesDoNotShareTheSameSheet() {
        for (mode in intArrayOf(Duplex.LONG_EDGE, Duplex.SHORT_EDGE)) {
            assertArrayEquals(intArrayOf(0, 1, 2, PagePlan.BLANK, 0, 1, 2, PagePlan.BLANK),
                PagePlan.create(3, PrintOptions(copies = 2, duplex = mode)))
        }
    }
    @Test fun blankBackDependsOnSelectedPagesNotPdfLength() {
        assertArrayEquals(intArrayOf(2, -1, 2, -1),
            PagePlan.create(10, PrintOptions(copies = 2, ranges = intArrayOf(2, 2), duplex = Duplex.LONG_EDGE)))
        assertArrayEquals(intArrayOf(1, 2),
            PagePlan.create(3, PrintOptions(ranges = intArrayOf(1, 2), duplex = Duplex.SHORT_EDGE)))
    }
    @Test fun evenFiftyPageDocumentHasNoInsertedBlanks() {
        val plan = PagePlan.create(50, PrintOptions(copies = 10, duplex = Duplex.LONG_EDGE))
        assertEquals(500, plan.size)
        assertTrue(plan.all { it >= 0 })
        assertEquals(49, plan.last())
    }
    @Test fun paddedSidesCountTowardTheJobLimit() {
        assertEquals(500, PagePlan.create(499, PrintOptions(duplex = Duplex.LONG_EDGE)).size)
        assertThrows(IllegalArgumentException::class.java) {
            PagePlan.create(7, PrintOptions(copies = 63, duplex = Duplex.LONG_EDGE))
        }
    }
    @Test fun rotatedBacksideUsesOutputOrdinalEvenWithSelectedPages() {
        assertFalse(Duplex.rotateBack(Duplex.LONG_EDGE, 0))
        assertTrue(Duplex.rotateBack(Duplex.LONG_EDGE, 1))
        assertFalse(Duplex.rotateBack(Duplex.SHORT_EDGE, 1))
        assertFalse(Duplex.rotateBack(Duplex.NONE, 1))
        assertThrows(IllegalArgumentException::class.java) { PagePlan.create(2, PrintOptions(duplex = 8)) }
    }
}
