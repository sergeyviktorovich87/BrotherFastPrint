package ru.brotherfastprint.render

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import ru.brotherfastprint.core.CancelScope
import ru.brotherfastprint.core.PrintOptions
import ru.brotherfastprint.printing.ServiceDispatcher
import java.util.concurrent.Executor
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class RenderClient(private val context: Context) {
    /** Compatibility entry point for the one-page diagnostic. */
    fun prepare(pdf: File, mono: File, hbp: File, cancel: CancelScope, log: (String) -> Unit): Long =
        prepare(pdf, mono, hbp, cancel, PrintOptions(ranges = intArrayOf(0, 0)), 16L * 1024 * 1024, { _, _ -> }, log)

    fun prepare(pdf: File, mono: File, hbp: File, cancel: CancelScope, options: PrintOptions,
                maxBytes: Long, progress: (Int, Int) -> Unit, log: (String) -> Unit): Long {
        val connected = CompletableFuture<IRenderService>()
        val completed = CompletableFuture<Bundle>()
        val service = AtomicReference<IRenderService?>()
        fun lost(reason: String) {
            val error = IOException(reason)
            connected.completeExceptionally(error); completed.completeExceptionally(error)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val remote = IRenderService.Stub.asInterface(binder)
                service.set(remote); connected.complete(remote)
            }
            override fun onServiceDisconnected(name: ComponentName) = lost("Renderer stopped")
            override fun onNullBinding(name: ComponentName) = lost("Renderer unavailable")
            override fun onBindingDied(name: ComponentName) = lost("Renderer binding died")
        }
        val rpc = Executors.newSingleThreadExecutor { task -> Thread(task, "pdf-binder").apply { isDaemon = true } }
        val canceller = Executors.newSingleThreadExecutor { task -> Thread(task, "pdf-cancel").apply { isDaemon = true } }
        val remove = cancel.onCancel {
            connected.completeExceptionally(CancellationException()); completed.completeExceptionally(CancellationException())
            canceller.execute { runCatching { service.get()?.cancel() } }
        }
        var bound = false
        try {
            cancel.check()
            val main = ServiceDispatcher.mainHandler()
            val callbackExecutor = Executor { command -> main.post(command); Unit }
            val intent = Intent(context, RenderService::class.java)
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            log("sandbox_bind_begin api=${android.os.Build.VERSION.SDK_INT} isolated=true")
            bound = if (android.os.Build.VERSION.SDK_INT >= 29)
                context.bindIsolatedService(intent, flags, RenderInstanceName.create(), callbackExecutor, conn)
            else context.bindService(intent, conn, flags)
            check(bound) { "Renderer bind failed" }
            log("sandbox_bind_accepted")
            val remote = connected.get(10, TimeUnit.SECONDS)
            log("sandbox_connected"); cancel.check()
            val settings = Bundle().apply {
                putInt("copies", options.copies); putIntArray("ranges", options.ranges)
                putInt("duplex", options.duplex); putBoolean("toner_save", options.tonerSave); putLong("max_hbp_bytes", maxBytes)
                putInt("raster_profile", options.rasterProfile)
            }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val mode = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                ParcelFileDescriptor.open(mono, mode).use { raster -> ParcelFileDescriptor.open(hbp, mode).use { output ->
                    var page = 0; var total = 0
                    val events = object : IRenderEvents.Stub() {
                        override fun event(code: String, elapsedMs: Long, value: Long) {
                            if (code == "page_count") total = value.toInt()
                            if (code == "page_begin") { page = value.toInt(); progress(page, total) }
                            if (code in setOf("pdf_open", "page_count", "page_begin", "pdf_page_index", "blank_back", "render_begin", "render_end_ms", "mono_end_ms", "encode_begin", "encode_page_end_bytes", "page_done", "encode_end_bytes"))
                                log("sandbox_ms=$elapsedMs page=$page/$total $code=$value")
                        }
                    }
                    rpc.execute {
                        log("sandbox_prepare_begin")
                        try { completed.complete(remote.prepare(input, raster, output, settings, events)) }
                        catch (e: Exception) { completed.completeExceptionally(e) }
                    }
                    val result = try { completed.get(20, TimeUnit.MINUTES) } catch (_: TimeoutException) {
                        log("prepare_timeout"); cancel.cancel(); throw CancellationException()
                    }
                    val errorCode = result.getInt("error_code", -1)
                    if (errorCode != 0) throw RenderFailure(errorCode)
                    val bytes = result.getLong("hbp_bytes", -1)
                    cancel.check()
                    require(bytes == hbp.length() && bytes in 1..maxBytes)
                    return bytes
                } }
            }
        } catch (e: Exception) {
            log("sandbox_failed category=${e.javaClass.simpleName}"); throw e
        } finally {
            remove()
            if (bound) runCatching { context.unbindService(conn) }.onFailure { log("sandbox_unbind_error category=${it.javaClass.simpleName}") }
            rpc.shutdownNow(); canceller.shutdown()
        }
    }
}
class RenderFailure(val errorCode: Int) : IOException("Renderer error code: $errorCode")
