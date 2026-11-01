package ru.brotherfastprint.core

object Duplex {
    const val NONE = 1
    const val LONG_EDGE = 2
    const val SHORT_EDGE = 4
    val modes = intArrayOf(NONE, LONG_EDGE, SHORT_EDGE)
    fun requireValid(mode: Int) { require(mode in modes) }
    fun label(mode: Int) = when (mode) {
        LONG_EDGE -> "Длинный край"
        SHORT_EDGE -> "Короткий край"
        else -> "Односторонняя"
    }
    // Fixed brlaser &l2S + cupsBackSide Rotated, as used by cups-filters.
    fun rotateBack(mode: Int, outputIndex: Int) = mode == LONG_EDGE && outputIndex % 2 == 1
}
