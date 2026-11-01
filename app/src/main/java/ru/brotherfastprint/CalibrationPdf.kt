package ru.brotherfastprint

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import ru.brotherfastprint.core.CancelScope
import ru.brotherfastprint.core.RasterProfile
import java.io.File
import java.io.FileOutputStream

object CalibrationPdf {
    fun create(file: File, pages: Int, token: CancelScope, profile: Int = RasterProfile.DOCUMENTS) {
        require(pages in 1..50)
        RasterProfile.requireValid(profile)
        val doc = PdfDocument()
        try {
            for (number in 1..pages) {
                token.check()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
                c.drawText("BrotherFastPrint / A4 / 600 DPI", 40f, 65f, paint)
                paint.textSize = 13f
                c.drawText("TOP - page $number / $pages - portrait / orientation test", 40f, 90f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.5f
                c.drawRect(24f, 24f, 571f, 818f, paint)
                paint.style = Paint.Style.FILL
                c.drawText("100 mm ruler (check with a real ruler)", 60f, 170f, paint)
                val length = 100f / 25.4f * 72f
                c.drawLine(60f, 200f, 60f + length, 200f, paint)
                for (i in 0..10) {
                    val x = 60f + i * length / 10
                    c.drawLine(x, 195f, x, 205f, paint)
                    c.drawText("${i * 10}", x - 4f, 225f, paint)
                }
                for (i in 0..7) {
                    paint.color = Color.rgb(i * 255 / 7, i * 255 / 7, i * 255 / 7)
                    c.drawRect(60f + i * 50, 270f, 110f + i * 50, 320f, paint)
                }
                paint.color = Color.BLACK; paint.textSize = 11f
                c.drawText("Profile: ${RasterProfile.technicalName(profile)}", 60f, 350f, paint)
                // A smooth ramp and shadow patches expose tone clipping on paper.
                for (i in 0..255) {
                    paint.color = Color.rgb(i, i, i)
                    val x = 60f + i * 400f / 256
                    c.drawRect(x, 375f, x + 400f / 256 + 0.1f, 405f, paint)
                }
                for (i in 0..7) {
                    val gray = i * 16
                    paint.color = Color.rgb(gray, gray, gray)
                    c.drawRect(60f + i * 50, 425f, 110f + i * 50, 460f, paint)
                }
                paint.color = Color.BLACK
                c.drawText("Shadow detail: gray 0 / 16 / 32 / 48 / 64 / 80 / 96 / 112", 60f, 480f, paint)
                paint.textSize = 96f
                c.drawText(number.toString(), 230f, 560f, paint)
                paint.textSize = 11f
                c.drawText("BOTTOM / left", 40f, 790f, paint)
                c.drawText("RIGHT", 490f, 790f, paint)
                doc.finishPage(page)
            }
            FileOutputStream(file).use(doc::writeTo)
        } finally { doc.close() }
    }
}
