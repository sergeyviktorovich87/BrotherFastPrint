package ru.brotherfastprint

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import ru.brotherfastprint.core.CancelScope
import ru.brotherfastprint.core.PrintOptions
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.render.RenderClient
import ru.brotherfastprint.service.BrotherPrintService
import ru.brotherfastprint.printing.ServiceDispatcher
import java.io.File

/** Device-only: validates the actual PdfRenderer, Binder FD transfer, JNI loader and sandbox. */
class PocInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun testServiceMessagesBypassUiSynchronizationBarriers() {
        val handler = ServiceDispatcher.mainHandler()
        val message = android.os.Message.obtain(handler, Runnable {})
        try {
            assertTrue(handler.sendMessageDelayed(message, 60_000))
            assertTrue(message.isAsynchronous)
            assertEquals(android.os.Looper.getMainLooper(), handler.looper)
        } finally { handler.removeCallbacksAndMessages(null) }
    }

    @Test fun testDiagnosticReportIncludesPersistedEventsWithoutTraceAccess() {
        val diagnostics = (context.applicationContext as FastPrintApp).diagnostics
        diagnostics.record("instrumented_diagnostics_marker")
        val report = diagnostics.report()
        assertTrue(report.contains("app_version=${BuildConfig.VERSION_NAME}"))
        assertTrue(report.contains("instrumented_diagnostics_marker"))
        assertTrue(report.contains("process_exit_info:"))
        assertTrue(report.contains("technical_history:"))
        assertTrue(report.contains("journal_synced=true"))
    }
    private fun makePdf(file: File) {
        val doc = PdfDocument()
        try {
            for (number in 1..2) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                page.canvas.drawColor(Color.WHITE)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
                page.canvas.drawText("Page $number / PDF sandbox test", 40f, 60f, p)
                p.strokeWidth = 0.7f
                for (i in 1..20) page.canvas.drawLine(30f, i * 30f, 540f, i * 30f + 15, p)
                doc.finishPage(page)
            }
            file.outputStream().use(doc::writeTo)
        } finally { doc.close() }
    }

    @Test fun testIsolatedFirstPagePreparationHasCompleteJobFraming() {
        val dir = File(context.noBackupFilesDir, "instrumented_${System.nanoTime()}").apply { mkdirs() }
        try {
            val pdf = File(dir, "input.pdf"); makePdf(pdf)
            val mono = File(dir, "page.mono"); val hbp = File(dir, "job.hbp")
            val events = mutableListOf<String>()
            val bytes = RenderClient(context).prepare(pdf, mono, hbp, CancelScope()) { synchronized(events) { events += it } }
            assertEquals(hbp.length(), bytes)
            assertEquals(604L * 6816, mono.length())
            val encoded = hbp.readBytes()
            assertTrue(encoded.take(128).all { it == 0.toByte() })
            val ascii = encoded.toString(Charsets.ISO_8859_1)
            assertTrue(ascii.contains("@PJL JOB NAME=\"BrotherFastPrint\""))
            assertTrue(ascii.contains("@PJL EOJ NAME=\"BrotherFastPrint\""))
            assertTrue(events.any { it.contains("pdf_open=2") })
            assertTrue(events.any { it.contains("encode_end_bytes=") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun testSelectedPagesAndCopiesShareOneBrotherJob() {
        val dir = File(context.noBackupFilesDir, "multipage_${System.nanoTime()}").apply { mkdirs() }
        try {
            val pdf = File(dir, "input.pdf"); makePdf(pdf)
            val hbp = File(dir, "job.hbp")
            val events = mutableListOf<String>()
            RenderClient(context).prepare(pdf, File(dir, "page.mono"), hbp, CancelScope(),
                PrintOptions(copies = 2, ranges = intArrayOf(1, 1)), 32L * 1024 * 1024,
                { _, _ -> }, { synchronized(events) { events += it } })
            val ascii = hbp.readBytes().toString(Charsets.ISO_8859_1)
            assertEquals(1, "@PJL JOB NAME=".toRegex().findAll(ascii).count())
            assertEquals(1, "@PJL EOJ NAME=".toRegex().findAll(ascii).count())
            assertEquals(2, events.count { it.contains("page_done=") })
            assertEquals(2, events.count { it.contains("pdf_page_index=1") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun testDuplexOddCopiesInsertBlankBacksInOneJob() {
        val dir = File(context.noBackupFilesDir, "duplex_${System.nanoTime()}").apply { mkdirs() }
        try {
            val pdf = File(dir, "input.pdf"); makePdf(pdf)
            val hbp = File(dir, "job.hbp")
            val events = mutableListOf<String>()
            RenderClient(context).prepare(pdf, File(dir, "page.mono"), hbp, CancelScope(),
                PrintOptions(copies = 2, ranges = intArrayOf(1, 1), duplex = Duplex.LONG_EDGE), 32L * 1024 * 1024,
                { _, _ -> }, { synchronized(events) { events += it } })
            val ascii = hbp.readBytes().toString(Charsets.ISO_8859_1)
            assertEquals(1, "@PJL JOB NAME=".toRegex().findAll(ascii).count())
            assertEquals(1, "@PJL EOJ NAME=".toRegex().findAll(ascii).count())
            assertTrue(ascii.contains("\u001b&l2S"))
            assertEquals(4, events.count { it.contains("page_done=") })
            assertEquals(2, events.count { it.contains("blank_back=1") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun testSystemServiceIsExportedOnlyBehindAndroidPrintPermission() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, BrotherPrintService::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_PRINT_SERVICE", info.permission)
        assertTrue(info.metaData.getInt("android.printservice") != 0)
    }

    @Test fun testPdfStripeRenderingMatchesWholeBitmap() {
        val pdf = File.createTempFile("render-test-", ".pdf", context.cacheDir)
        try {
            makePdf(pdf)
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                renderer.openPage(0).use { page ->
                    val w = 300; val h = 421; val rows = 37
                    val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val stripe = Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888)
                    try {
                        full.eraseColor(Color.WHITE)
                        page.render(full, null, Matrix().apply { setScale(0.5f, 0.5f) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val expected = IntArray(w * h); full.getPixels(expected, 0, w, 0, 0, w, h)
                        val actual = IntArray(w * h)
                        for (y in 0 until h step rows) {
                            stripe.eraseColor(Color.WHITE)
                            val n = minOf(rows, h-y)
                            val matrix = Matrix().apply { setValues(floatArrayOf(0.5f,0f,0f,0f,0.5f,-y.toFloat(),0f,0f,1f)) }
                            page.render(stripe, Rect(0,0,w,n), matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            stripe.getPixels(actual,y*w,w,0,0,w,n)
                        }
                        assertTrue(expected.contentEquals(actual))
                    } finally { full.recycle(); stripe.recycle() }
                }
            }
        } finally { pdf.delete() }
    }
}
