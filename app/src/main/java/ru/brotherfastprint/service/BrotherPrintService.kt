package ru.brotherfastprint.service

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrintJobInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import ru.brotherfastprint.FastPrintApp
import ru.brotherfastprint.BuildConfig
import ru.brotherfastprint.PrinterConfig
import ru.brotherfastprint.core.CancelScope
import ru.brotherfastprint.core.PagePlan
import ru.brotherfastprint.core.PrintOptions
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.core.PrintRecovery
import ru.brotherfastprint.core.DispatchDeadline
import ru.brotherfastprint.printing.PrintEngine
import ru.brotherfastprint.printing.ServiceDispatcher
import ru.brotherfastprint.printing.PrintKeepAlive
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** All framework objects are accessed on the main thread; PDF work and sockets stay on the worker. */
class BrotherPrintService : PrintService() {
    private val main = ServiceDispatcher.mainHandler()
    private val engine get() = (application as FastPrintApp).engine
    private data class Active(val job: PrintJob, val token: CancelScope)
    private val jobs = mutableMapOf<PrintJobId, Active>()
    private var connected = false
    internal val isSystemConnected: Boolean get() = connected
    private var discovery: BrotherDiscoverySession? = null
    private val instance = nextInstance.incrementAndGet()
    private var reconnectScan = true
    private var scanDeadline: DispatchDeadline? = null
    private var keepAlive: AutoCloseable? = null
    private var lastQueueCount = -1
    private val queuePoll = Runnable { scanQueue() }

    companion object { private val nextInstance = AtomicInteger() }

    override fun onCreate() {
        super.onCreate()
        engine.logService("service_created instance=$instance dispatcher=async")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        engine.logService("discovery_factory instance=$instance connected=$connected")
        return BrotherDiscoverySession(this, engine).also {
            discovery = it
            engine.logService("discovery_factory_ready instance=$instance cached_count=${it.printers.size}")
        }
    }

    override fun onConnected() {
        connected = true
        keepAlive?.close(); keepAlive = PrintKeepAlive.acquire(this, "system_session")
        engine.logService("service_connected instance=$instance app_version=${BuildConfig.VERSION_NAME} pid=${android.os.Process.myPid()}")
        discovery?.systemConnected()
        val power = getSystemService(android.os.PowerManager::class.java)
        val restricted = if (android.os.Build.VERSION.SDK_INT >= 28)
            getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted else false
        engine.logService("service_power background_restricted=$restricted battery_optimization_ignored=${power.isIgnoringBatteryOptimizations(packageName)}")
        logPowerSnapshot("service_connected")
        reconnectScan = true; lastQueueCount = -1
        // Let the framework attach/publish the initial discovery snapshot before a spooler RPC.
        scheduleQueueScan(250)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // Log callback entry before Binder status/data calls and before the engine's own queue.
        engine.logService("system_job_callback instance=$instance connected=$connected")
        logPowerSnapshot("job_callback")
        acceptJob(printJob, "callback")
    }

    private fun scheduleQueueScan(delay: Long) {
        main.removeCallbacks(queuePoll)
        if (connected) {
            scanDeadline = DispatchDeadline(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), delay)
            main.postDelayed(queuePoll, delay)
        }
    }

    internal fun requestQueueCheck() { scheduleQueueScan(0) }

    private fun scanQueue() {
        if (!connected) return
        val begin = SystemClock.elapsedRealtime()
        val beginUptime = SystemClock.uptimeMillis()
        val timing = scanDeadline?.measure(begin, beginUptime)
        val reconnect = reconnectScan
        var activeCount = 0
        try {
            if (reconnect) engine.logService("system_queue_query_begin instance=$instance reconnect=true")
            val active = activePrintJobs
            activeCount = active.size
            reconnectScan = false
            if (active.size != lastQueueCount || reconnect || (timing?.elapsedLateMs ?: 0) > 1000) {
                engine.logService("system_queue_scan instance=$instance count=${active.size} reconnect=$reconnect dispatch_late_ms=${timing?.elapsedLateMs ?: 0} awake_late_ms=${timing?.awakeLateMs ?: 0} sleep_ms=${timing?.sleepMs ?: 0}")
                lastQueueCount = active.size
            }
            for (job in active) {
                val state = when (job.info.state) {
                    PrintJobInfo.STATE_QUEUED -> PrintRecovery.State.QUEUED
                    PrintJobInfo.STATE_STARTED, PrintJobInfo.STATE_BLOCKED -> PrintRecovery.State.IN_FLIGHT
                    else -> PrintRecovery.State.TERMINAL
                }
                when (PrintRecovery.decide(state, job.id in jobs, reconnect)) {
                    PrintRecovery.Action.START -> acceptJob(job, "queue_scan")
                    PrintRecovery.Action.FAIL_UNCERTAIN -> {
                        engine.logService("system_recovery_uncertain instance=$instance")
                        job.fail("Служба была перезапущена. Часть страниц могла напечататься; проверьте принтер перед повтором.")
                    }
                    PrintRecovery.Action.LEAVE -> Unit
                }
            }
        } catch (e: Exception) {
            engine.logService("system_queue_scan_error instance=$instance category=${e.javaClass.simpleName}")
        } finally {
            val duration = SystemClock.elapsedRealtime() - begin
            if (duration > 1000) engine.logService("system_queue_scan_slow instance=$instance duration_ms=$duration awake_ms=${SystemClock.uptimeMillis() - beginUptime}")
            // Follow actual system discovery/jobs, with one reconnect grace check; do not poll idle forever.
            if (jobs.isNotEmpty() || activeCount > 0 || discovery?.hasActiveRequests == true || reconnect)
                scheduleQueueScan(5000)
        }
    }

    private fun acceptJob(printJob: PrintJob, source: String) {
        if (!connected || printJob.id in jobs) return
        val info = printJob.info
        if (info.state != PrintJobInfo.STATE_QUEUED) return
        // Creation includes time spent in the print preview; it is NOT an Android queue timestamp.
        engine.logService("system_job_accept instance=$instance via=$source document_age_ms=${(System.currentTimeMillis() - info.creationTime).coerceAtLeast(0)}")
        logPowerSnapshot("job_accept")
        if (info.printerId != generatePrinterId(PrinterConfig.LOCAL_ID)) {
            printJob.fail("Неизвестный принтер. Выберите Brother HL-L2340DWR."); return
        }
        val attrs = info.attributes
        val paper = attrs?.mediaSize?.asPortrait()
        val a4 = PrintAttributes.MediaSize.ISO_A4
        if (paper == null || paper.widthMils != a4.widthMils || paper.heightMils != a4.heightMils ||
            attrs.resolution?.horizontalDpi != 600 || attrs.resolution?.verticalDpi != 600 ||
            attrs.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME ||
            attrs.duplexMode !in intArrayOf(0, Duplex.NONE, Duplex.LONG_EDGE, Duplex.SHORT_EDGE)) {
            printJob.fail("Поддерживается A4, 600 DPI, чёрно-белая печать."); return
        }
        if (info.copies !in 1..PagePlan.MAX_COPIES) {
            printJob.fail("Допустимо от 1 до ${PagePlan.MAX_COPIES} копий."); return
        }
        val endpoint = try { PrinterConfig.endpoint(this) } catch (_: IllegalArgumentException) {
            printJob.fail("Сохраните корректный IP и RAW-порт в BrotherFastPrint."); return
        }
        val ranges = info.pages?.flatMap { listOf(it.start, it.end) }?.toIntArray()
        val options = PrintOptions(info.copies, ranges, PrinterConfig.tonerSave(this), attrs.duplexMode.takeIf { it != 0 } ?: PrinterConfig.duplex(this), PrinterConfig.rasterProfile(this))
        if (!printJob.start()) {
            engine.logService("system_job_start_rejected instance=$instance"); return
        }
        engine.logService("system_job_started instance=$instance via=$source")
        printJob.setStatus("В очереди BrotherFastPrint")
        val token = engine.submit(endpoint, options, "system", { file, scope ->
            // getData() returns a pipe on some Android builds; copy before opening PdfRenderer.
            PrintEngine.copyPdf(requestDocument(printJob, scope), file, scope)
        }, { page, total ->
            if (connected && printJob.isStarted) {
                if (total > 0) {
                    printJob.setStatus("Подготовка стороны $page из $total")
                    printJob.setProgress(0.85f * (page - 1) / total)
                } else {
                    printJob.setStatus("Передача задания принтеру")
                    printJob.setProgress(0.9f)
                }
            }
        }, { outcome ->
            jobs.remove(printJob.id)
            if (connected && printJob.isStarted) {
                when {
                    outcome.cancelled -> { printJob.setStatus(outcome.message); printJob.cancel() }
                    outcome.sent -> { printJob.setProgress(1f); printJob.complete() }
                    else -> printJob.fail(outcome.message)
                }
            }
        })
        jobs[printJob.id] = Active(printJob, token)
        scheduleQueueScan(0)
    }

    /** Runs on the worker. The framework call is posted to main and late descriptors are closed. */
    private fun requestDocument(job: PrintJob, token: CancelScope): ParcelFileDescriptor {
        val request = CompletableFuture<ParcelFileDescriptor>()
        val remove = token.onCancel { request.completeExceptionally(CancellationException()) }
        main.post {
            try {
                engine.logService("system_document_request_begin instance=$instance")
                if (token.isCancelled || !connected || !job.isStarted) throw CancellationException()
                val fd = job.document.data ?: throw IOException("Print spool unavailable")
                if (!request.complete(fd)) fd.close()
                engine.logService("system_document_request_end instance=$instance")
            } catch (e: Exception) { request.completeExceptionally(e) }
        }
        try { return request.get(15, TimeUnit.SECONDS) }
        finally {
            request.completeExceptionally(IOException("Print spool request ended"))
            remove()
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        jobs[printJob.id]?.token?.cancel()
        if (printJob.isStarted || printJob.isQueued || printJob.isBlocked) {
            printJob.setStatus("Отменено. Если передача уже началась, часть страниц могла напечататься.")
            printJob.cancel()
        }
    }

    override fun onDisconnected() {
        connected = false
        keepAlive?.close(); keepAlive = null
        main.removeCallbacks(queuePoll)
        discovery?.systemDisconnected()
        engine.logService("service_disconnected instance=$instance active_jobs=${jobs.size}")
        jobs.values.forEach { it.token.cancel() }
    }
    override fun onDestroy() {
        connected = false
        keepAlive?.close(); keepAlive = null
        main.removeCallbacks(queuePoll)
        discovery?.systemDisconnected()
        engine.logService("service_destroyed instance=$instance active_jobs=${jobs.size}")
        jobs.values.forEach { it.token.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        engine.logService("launcher_task_removed service_owned_by_android=true")
        super.onTaskRemoved(rootIntent)
    }

    private fun logPowerSnapshot(reason: String) {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        val power = getSystemService(android.os.PowerManager::class.java)
        engine.logService("process_power reason=$reason importance=${state.importance} interactive=${power.isInteractive} idle=${power.isDeviceIdleMode} power_save=${power.isPowerSaveMode} elapsed_ms=${SystemClock.elapsedRealtime()} uptime_ms=${SystemClock.uptimeMillis()}")
    }
}
