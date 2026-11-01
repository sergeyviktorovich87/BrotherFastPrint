package ru.brotherfastprint.render

import kotlin.math.roundToInt

/** Imageable area from brlaser PPD: left,bottom,right,top = 8,8,8,16 points. */
object PaperGeometry {
    const val DPI = 600
    private const val A4_W = 210.0 / 25.4 * 72.0
    private const val A4_H = 297.0 / 25.4 * 72.0
    private const val LEFT = 8.0
    private const val RIGHT = 8.0
    private const val TOP = 16.0
    private const val BOTTOM = 8.0
    private const val PX = DPI / 72.0
    val width = ((A4_W - LEFT - RIGHT) * PX).roundToInt()
    val height = ((A4_H - TOP - BOTTOM) * PX).roundToInt()

    fun transform(pageWidth: Int, pageHeight: Int, stripY: Int, rotateBack: Boolean = false): FloatArray {
        require(pageWidth in 1..14400 && pageHeight in 1..14400)
        require(stripY in 0 until height)
        val landscape = pageWidth > pageHeight
        val w = if (landscape) pageHeight else pageWidth
        val h = if (landscape) pageWidth else pageHeight
        val scale = minOf(1.0, A4_W / w, A4_H / h)
        val x = (A4_W - w * scale) / 2 - LEFT
        // Match cups-filters Rotated: swap top/bottom margins before reversing the back raster.
        val y = (A4_H - h * scale) / 2 - if (rotateBack) BOTTOM else TOP
        val m = if (landscape) floatArrayOf(
            0f, (-scale * PX).toFloat(), ((x + pageHeight * scale) * PX).toFloat(),
            (scale * PX).toFloat(), 0f, (y * PX).toFloat(), 0f, 0f, 1f,
        ) else floatArrayOf(
            (scale * PX).toFloat(), 0f, (x * PX).toFloat(),
            0f, (scale * PX).toFloat(), (y * PX).toFloat(), 0f, 0f, 1f,
        )
        if (rotateBack) {
            m[0] = -m[0]; m[1] = -m[1]; m[2] = width - m[2]
            m[3] = -m[3]; m[4] = -m[4]; m[5] = height - m[5]
        }
        m[5] -= stripY
        return m
    }
}
