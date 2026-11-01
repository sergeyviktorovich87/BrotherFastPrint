package ru.brotherfastprint

import android.content.Context
import android.net.Uri
import ru.brotherfastprint.core.*
import ru.brotherfastprint.printing.*
import java.io.IOException

// The old controller name is retained for source compatibility; all printing uses the shared engine.
data class ScreenState(val busy: Boolean, val status: String, val source: String, val log: String)

class PocController(private val context: Context, private val engine: PrintEngine) {
    private var selected: Uri? = null
    private var samplePages = 0
    var observer: ((ScreenState) -> Unit)? = null
    init { engine.observe { observer?.invoke(snapshot()) } }

    fun snapshot(): ScreenState {
        val state = engine.snapshot()
        return ScreenState(state.busy, state.status,
            if (samplePages > 0) "Тестовая A4 · страниц: $samplePages · рамка и линейка 100 мм"
            else if (selected != null) "PDF выбран · печатаются все страницы" else "PDF пока не выбран", state.log)
    }
    private fun publish() { observer?.invoke(snapshot()) }
    fun select(uri: Uri?) {
        if (snapshot().busy) return
        require(uri == null || uri.scheme == "content")
        selected = uri; samplePages = if (uri == null) 1 else 0
        publish()
    }
    fun selectSample(pages: Int) {
        require(pages in 1..50)
        if (snapshot().busy) return
        selected = null; samplePages = pages; publish()
    }
    fun hasSource() = selected != null || samplePages > 0
    fun cancel() { engine.cancelCurrent() }

    fun probe(endpoint: Endpoint) {
        if (snapshot().busy) return
        engine.probe(endpoint)
    }
    fun printAll(endpoint: Endpoint) {
        if (snapshot().busy || !hasSource()) return
        val uri = selected; val pages = samplePages
        val profile = PrinterConfig.rasterProfile(context)
        engine.submit(endpoint, PrintOptions(tonerSave = PrinterConfig.tonerSave(context), duplex = PrinterConfig.duplex(context), rasterProfile = profile), "direct", { output, token ->
            if (pages > 0) CalibrationPdf.create(output, pages, token, profile)
            else {
                val input = context.contentResolver.openInputStream(uri!!) ?: throw IOException("No PDF input")
                PrintEngine.copyPdf(input, output, token)
            }
        })
    }
}
