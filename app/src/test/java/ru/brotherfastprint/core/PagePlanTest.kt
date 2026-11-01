package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class PagePlanTest {
    @Test fun copiesAreCollatedInDocumentOrder() {
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 1, 2), PagePlan.create(3, PrintOptions(copies = 2)))
    }
    @Test fun frameworkRangesAreAlreadyRelativeToTheSpooledPdf() {
        // Original pages 3..5 may have become a three-page PDF + ALL_PAGES in PrintSpooler.
        assertArrayEquals(intArrayOf(0, 1, 2), PagePlan.create(3, PrintOptions(ranges = intArrayOf(0, Int.MAX_VALUE))))
        // When the spool PDF still contains the full document, use the framework's file indices.
        assertArrayEquals(intArrayOf(2, 3, 4), PagePlan.create(10, PrintOptions(ranges = intArrayOf(2, 4))))
    }
    @Test fun overlapsDoNotPrintPagesTwiceAndUnsortedRangesKeepDocumentOrder() {
        assertArrayEquals(intArrayOf(0, 1, 2, 4), PagePlan.create(5, PrintOptions(ranges = intArrayOf(4, 4, 1, 2, 0, 1))))
    }
    @Test fun fiftyPagesAndCopiesRemainBounded() {
        val result = PagePlan.create(50, PrintOptions(copies = 10))
        assertEquals(500, result.size)
        assertEquals(49, result.last())
        assertEquals(0, result[50])
        rejects { PagePlan.create(50, PrintOptions(copies = 11)) }
        rejects { PagePlan.create(Int.MAX_VALUE, PrintOptions()) }
    }
    @Test fun invalidRangesAreRejectedBeforePrinting() {
        listOf(intArrayOf(), intArrayOf(0), intArrayOf(-1, 1), intArrayOf(2, 1), intArrayOf(0, 3)).forEach { range ->
            rejects { PagePlan.create(3, PrintOptions(ranges = range)) }
        }
        rejects { PagePlan.create(0, PrintOptions()) }
        rejects { PagePlan.create(1, PrintOptions(copies = 0)) }
        rejects { PagePlan.create(1, PrintOptions(copies = 100)) }
    }
    @Test fun smallSelectionOfLargePdfDoesNotAllocateAllPages() {
        assertArrayEquals(intArrayOf(99998, 99999), PagePlan.create(100000, PrintOptions(ranges = intArrayOf(99998, 99999))))
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected rejection before transmission") } catch (_: IllegalArgumentException) { }
    }
}
