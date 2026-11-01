package ru.brotherfastprint.render

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import ru.brotherfastprint.core.PagePlan
import ru.brotherfastprint.core.PrintLimitException
import ru.brotherfastprint.core.PrintOptions
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.core.RasterProfile
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** No network or file paths: the host grants only input, reusable raster and output FDs. */
class RenderService : Service() {
    private val used = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun onCreate() { super.onCreate(); NativeBridge.reset() }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException() }
    private fun result(code: Int, bytes: Long = 0, pages: Int = 0) = Bundle().apply {
        putInt("error_code", code); putLong("hbp_bytes", bytes); putInt("output_pages", pages)
    }

    private val binder = object : IRenderService.Stub() {
        override fun cancel() { cancelled.set(true); NativeBridge.cancel() }

        override fun prepare(pdf: ParcelFileDescriptor, mono: ParcelFileDescriptor,
                             hbp: ParcelFileDescriptor, options: Bundle, events: IRenderEvents): Bundle {
            if (!used.compareAndSet(false, true)) {
                pdf.close(); mono.close(); hbp.close(); return result(5)
            }
            val start = SystemClock.elapsedRealtime()
            fun event(code: String, value: Long = 0) = events.event(code, SystemClock.elapsedRealtime() - start, value)
            try {
                pdf.use { input -> mono.use { raster -> hbp.use { output ->
                    checkCancelled()
                    val owned = ParcelFileDescriptor.dup(input.fileDescriptor)
                    val renderer = try { PdfRenderer(owned) } catch (e: Exception) { owned.close(); throw e }
                    renderer.use {
                        event("pdf_open", renderer.pageCount.toLong())
                        val settings = PrintOptions(options.getInt("copies", 1), options.getIntArray("ranges"), options.getBoolean("toner_save"), options.getInt("duplex", Duplex.NONE), options.getInt("raster_profile", RasterProfile.DOCUMENTS))
                        val plan = PagePlan.create(renderer.pageCount, settings)
                        event("raster_profile", settings.rasterProfile.toLong())
                        val maxBytes = options.getLong("max_hbp_bytes", 16L * 1024 * 1024)
                        require(maxBytes in 1..(512L * 1024 * 1024))
                        event("page_count", plan.size.toLong())
                        val width = PaperGeometry.width
                        val height = PaperGeometry.height
                        val stripeRows = 128
                        val bitmap = Bitmap.createBitmap(width, stripeRows, Bitmap.Config.ARGB_8888)
                        try {
                            NativeBridge.beginDocument(output.fd, settings.tonerSave, settings.duplex != Duplex.NONE)
                            for ((ordinal, pdfIndex) in plan.withIndex()) {
                                checkCancelled()
                                event("page_begin", (ordinal + 1).toLong())
                                event("pdf_page_index", pdfIndex.toLong())
                                Os.lseek(raster.fileDescriptor, 0, OsConstants.SEEK_SET)
                                Os.ftruncate(raster.fileDescriptor, 0)
                                if (pdfIndex == PagePlan.BLANK) {
                                    Os.ftruncate(raster.fileDescriptor, ((width + 7) / 8).toLong() * height)
                                    event("blank_back", 1)
                                } else renderer.openPage(pdfIndex).use { page ->
                                    event("pdf_width_pt", page.width.toLong()); event("pdf_height_pt", page.height.toLong())
                                    var renderMs = 0L; var monoMs = 0L
                                    event("render_begin", (ordinal + 1).toLong())
                                    for (y in 0 until height step stripeRows) {
                                        checkCancelled()
                                        val rows = minOf(stripeRows, height-y)
                                        bitmap.eraseColor(Color.WHITE)
                                        val matrix = Matrix().apply { setValues(PaperGeometry.transform(page.width, page.height, y, Duplex.rotateBack(settings.duplex, ordinal))) }
                                        val t0 = SystemClock.elapsedRealtime()
                                        page.render(bitmap, Rect(0, 0, width, rows), matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                        renderMs += SystemClock.elapsedRealtime()-t0
                                        checkCancelled()
                                        val t1 = SystemClock.elapsedRealtime()
                                        NativeBridge.appendMono(bitmap, rows, y, raster.fd, settings.rasterProfile)
                                        monoMs += SystemClock.elapsedRealtime()-t1
                                    }
                                    event("render_end_ms", renderMs); event("mono_end_ms", monoMs)
                                }
                                checkCancelled()
                                event("encode_begin", (ordinal + 1).toLong())
                                val bytes = NativeBridge.appendPage(raster.fd, width, height)
                                if (bytes > maxBytes) throw PrintLimitException()
                                event("encode_page_end_bytes", bytes)
                                event("page_done", (ordinal + 1).toLong())
                            }
                            checkCancelled()
                            val bytes = NativeBridge.finishDocument()
                            if (bytes > maxBytes) throw PrintLimitException()
                            Os.fsync(output.fileDescriptor)
                            event("encode_end_bytes", bytes)
                            return result(0, bytes, plan.size)
                        } finally {
                            bitmap.recycle()
                            NativeBridge.abortDocument()
                        }
                    }
                } } }
            } catch (_: CancellationException) { return result(4) }
            catch (_: PrintLimitException) { return result(6) }
            catch (_: SecurityException) { return result(2) }
            catch (_: IllegalArgumentException) { return result(3) }
            catch (_: IOException) { return result(if (cancelled.get()) 4 else 1) }
            catch (_: Exception) { return result(if (cancelled.get()) 4 else 1) }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        cancelled.set(true); NativeBridge.cancel(); super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
