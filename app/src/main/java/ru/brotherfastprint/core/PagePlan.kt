package ru.brotherfastprint.core

class PrintLimitException : IllegalArgumentException("Print job exceeds configured limits")

data class PrintOptions(val copies: Int = 1, val ranges: IntArray? = null, val tonerSave: Boolean = false, val duplex: Int = Duplex.NONE)

/** Ranges use PDF-file indices. Android PrintSpooler already remaps document indices. */
object PagePlan {
    const val MAX_OUTPUT_PAGES = 500
    const val MAX_COPIES = 99
    const val BLANK = -1

    fun create(pageCount: Int, options: PrintOptions): IntArray {
        require(pageCount > 0)
        Duplex.requireValid(options.duplex)
        if (options.copies !in 1..MAX_COPIES) throw PrintLimitException()
        val ranges = options.ranges ?: intArrayOf(0, Int.MAX_VALUE)
        require(ranges.isNotEmpty() && ranges.size % 2 == 0 && ranges.size <= 1000)
        val selected = sortedSetOf<Int>()
        val maxDistinct = MAX_OUTPUT_PAGES / options.copies
        for (i in ranges.indices step 2) {
            val start = ranges[i]
            val end = if (ranges[i + 1] == Int.MAX_VALUE && start == 0) pageCount - 1 else ranges[i + 1]
            require(start >= 0 && end >= start && end < pageCount)
            if (end.toLong() - start + 1 > maxDistinct) throw PrintLimitException()
            for (page in start..end) {
                selected += page
                if (selected.size > maxDistinct) throw PrintLimitException()
            }
        }
        require(selected.isNotEmpty())
        val pages = selected.toIntArray()
        val perCopy = pages.size + if (options.duplex != Duplex.NONE && pages.size % 2 != 0) 1 else 0
        if (perCopy.toLong() * options.copies > MAX_OUTPUT_PAGES) throw PrintLimitException()
        // Software collation: 1,2,3,1,2,3 rather than per-page 1,1,2,2,3,3.
        return IntArray(perCopy * options.copies) { val i = it % perCopy; if (i == pages.size) BLANK else pages[i] }
    }
}
