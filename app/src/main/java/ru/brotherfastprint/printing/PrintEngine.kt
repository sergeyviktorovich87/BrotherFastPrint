package ru.brotherfastprint.printing

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import ru.brotherfastprint.BuildConfig
import ru.brotherfastprint.core.*
import ru.brotherfastprint.render.RenderClient
import ru.brotherfastprint.render.RenderFailure
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Source is opened on the worker only when its turn arrives. Never log names, URIs or PDF contents. */
typealias PdfSource = (File, CancelScope) -> Unit

data class EngineState(val busy: Boolean, val pending: Int, val status: String, val log: String,
    val stage: String = "idle", val page: Int = 0, val pages: Int = 0, val reachable: Boolean? = null)
data class PrintOutcome(val sent: Boolean, val cancelled: Boolean, val mayHavePrinted: Boolean, val message: String)
private class SpoolSpaceException : IOException()

/** Shared by direct printing and PrintService: serial jobs, preload to private storage, one RAW stream. */
class PrintEngine(private val context: Context, private val diagnostics: Diagnostics) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = ServiceDispatcher.mainHandler()
    private val lock = Any()
    private val spool = File(context.noBackupFilesDir, "spool").apply { check(mkdirs() || isDirectory) }
    private val lines = ArrayDeque<String>()
    private val serviceLines = ArrayDeque<String>()
    private val serviceStarted = SystemClock.elapsedRealtime()
    private val observers = mutableSetOf<(EngineState) -> Unit>()
    private var pending = 0
    private var current: CancelScope? = null
    private var started = SystemClock.elapsedRealtime()
    private var status = "Готов к печати"
    private var publishScheduled = false
    private var stage = "idle"
    private var page = 0
    private var pages = 0
    private var reachable: Boolean? = null

    init { spool.listFiles()?.forEach { it.deleteRecursively() } }

    fun snapshot(): EngineState = synchronized(lock) {
        EngineState(pending > 0, pending, status,
            (lines.joinToString("\n") + if (serviceLines.isEmpty()) "" else "\nservice_diagnostics:\n" + serviceLines.joinToString("\n")), stage, page, pages, reachable)
    }
    fun logService(message: String) {
        val line = "service_ms=${SystemClock.elapsedRealtime() - serviceStarted} pid=${android.os.Process.myPid()} $message"
        synchronized(lock) {
            if (serviceLines.size >= 160) serviceLines.removeFirst()
            serviceLines.addLast(line)
        }
        diagnostics.record(line)
        publish()
    }
    fun observe(observer: (EngineState) -> Unit): () -> Unit {
        synchronized(lock) { observers += observer }
        main.post { observer(snapshot()) }
        return { synchronized(lock) { observers -= observer }; Unit }
    }
    fun cancelCurrent() { synchronized(lock) { current }?.cancel() }

    private fun publish() {
        synchronized(lock) {
            if (publishScheduled) return
            publishScheduled = true
        }
        main.postDelayed({
            val callbacks = synchronized(lock) { publishScheduled = false; observers.toList() }
            val state = snapshot()
            callbacks.forEach { it(state) }
        }, 100)
    }
    private fun status(value: String) { synchronized(lock) { status = value }; publish() }
    private fun log(value: String) {
        val line = "${SystemClock.elapsedRealtime() - started}ms $value"
        synchronized(lock) {
            if (lines.size >= 2048) lines.removeFirst()
            lines.addLast(line)
        }
        diagnostics.record(line)
        publish()
    }

    fun printerConfigured(online: Boolean? = null) {
        synchronized(lock) {
            reachable = online
            status = if (online == true) "Принтер найден, IP и RAW-порт сохранены" else "Адрес сохранён. Проверьте соединение."
        }
        publish()
    }

    fun probe(endpoint: Endpoint) {
        val token = CancelScope()
        synchronized(lock) { pending++ }
        publish()
        worker.execute {
            synchronized(lock) { current = token; started = SystemClock.elapsedRealtime(); lines.clear() }
            synchronized(lock) { stage = "probe"; reachable = null }
            status("Проверка соединения…")
            log("probe_begin app_version=${BuildConfig.VERSION_NAME} host=${endpoint.host} port=${endpoint.port}")
            try {
                token.check()
                val routes = LocalNetwork.routes(context, endpoint, ::log)
                RawTcpTransport(diagnostic = ::log).probe(endpoint, token, routes)
                ReachabilityCache.markOnline(context, endpoint)
                synchronized(lock) { reachable = true }
                log("probe_ok")
                status("Online · RAW-порт доступен. Бумага и тонер не проверены.")
            } catch (e: Exception) {
                synchronized(lock) { reachable = false }
                val info = TcpDiagnostics.describe(e)
                log("probe_failed reason=${info.reason} category=${info.category} cancelled=${token.isCancelled}")
                if (info.detail.isNotEmpty()) log("tcp_error_detail=${info.detail}")
                status(if (token.isCancelled) "Проверка отменена" else if (e is NoLocalNetworkException)
                    "Подключите телефон к Wi-Fi сети принтера" else "Offline · ${info.reason}. Проверьте Wi-Fi, IP и порт; скопируйте лог.")
            } finally {
                log("probe_end")
                diagnostics.checkpoint()
                synchronized(lock) { current = null; pending--; stage = "idle" }
                publish()
            }
        }
    }

    fun submit(endpoint: Endpoint, options: PrintOptions, origin: String, source: PdfSource,
               progress: (Int, Int) -> Unit = { _, _ -> }, finished: (PrintOutcome) -> Unit = {}): CancelScope {
        require(origin == "system" || origin == "direct")
        RasterProfile.requireValid(options.rasterProfile)
        val token = CancelScope()
        val queuedAt = SystemClock.elapsedRealtime()
        synchronized(lock) { pending++ }
        publish()
        val keepAlive = PrintKeepAlive.acquire(context, "job")
        worker.execute {
            try {
                synchronized(lock) { current = token; started = SystemClock.elapsedRealtime(); lines.clear() }
                log("job_received app_version=${BuildConfig.VERSION_NAME} source=$origin queued_ms=${SystemClock.elapsedRealtime() - queuedAt} copies=${options.copies} duplex=${options.duplex} raster_profile=${RasterProfile.technicalName(options.rasterProfile)} toner_save=${options.tonerSave} dpi=600 paper=A4 mode=preload")
                synchronized(lock) { stage = "prepare"; page = 0; pages = 0 }
                var dir: File? = null
                val result = try {
                    token.check()
                    LocalNetwork.routes(context, endpoint, ::log)
                    if (StatFs(spool.absolutePath).availableBytes < DISK_RESERVE + 16 * MIB) throw SpoolSpaceException()
                    val jobDir = File(spool, "job_${UUID.randomUUID()}")
                    check(jobDir.mkdir()); dir = jobDir
                    val pdf = File(jobDir, "input.pdf")
                    status("Получение PDF…")
                    source(pdf, token)
                    token.check()
                    if (pdf.length() !in 1..MAX_PDF_BYTES) throw PrintLimitException()
                    log("pdf_spooled bytes=${pdf.length()}")
                    val budget = minOf(MAX_HBP_BYTES, StatFs(spool.absolutePath).availableBytes - DISK_RESERVE)
                    if (budget < 16 * MIB) throw SpoolSpaceException()
                    val hbp = File(jobDir, "job.hbp")
                    val size = RenderClient(context).prepare(pdf, File(jobDir, "page.mono"), hbp, token, options, budget, { page, total ->
                        synchronized(lock) { this.page = page; this.pages = total }
                        status("Подготовка стороны $page из $total…")
                        main.post { progress(page, total) }
                    }, ::log)
                    token.check()
                    log("prepared bytes=$size")
                    synchronized(lock) { stage = "send" }
                    status("Передача подготовленного задания…")
                    main.post { progress(0, 0) }
                    log("tcp_begin host=${endpoint.host} port=${endpoint.port}")
                    var lastProgress = 0L
                    val routes = LocalNetwork.routes(context, endpoint, ::log)
                    val bytes = RawTcpTransport(totalTimeoutMs = 10 * 60_000, diagnostic = ::log).send(hbp, endpoint, token, routes) { count ->
                        if (count - lastProgress >= MIB || count == size) {
                            lastProgress = count; log("tcp_accepted_bytes=$count")
                        }
                    }
                    log("tcp_end accepted_bytes=$bytes")
                    ReachabilityCache.markOnline(context, endpoint)
                    synchronized(lock) { reachable = true }
                    PrintOutcome(true, false, true, "Задание передано принтеру. RAW не подтверждает выход бумаги.")
                } catch (e: TransmissionException) {
                    val info = TcpDiagnostics.describe(e)
                    log("tcp_failed phase=${e.phase} reason=${info.reason} category=${info.category} may_have_printed=${e.mayHavePrinted} accepted_bytes=${e.acceptedBytes} cancelled=${token.isCancelled}")
                    if (info.detail.isNotEmpty()) log("tcp_error_detail=${info.detail}")
                    val message = if (e.mayHavePrinted) "Передача прервана. Часть страниц могла напечататься. Проверьте принтер перед повтором."
                        else if (token.isCancelled) "Отменено до передачи данных"
                        else when (info.reason) {
                            TcpFailureReason.REFUSED -> "Принтер отказал в подключении. Проверьте RAW-порт 9100."
                            TcpFailureReason.PERMISSION -> "Android запретил сеть. Проверьте разрешения локальной сети и VPN."
                            TcpFailureReason.UNREACHABLE -> "Нет маршрута к принтеру через Wi-Fi."
                            TcpFailureReason.TIMEOUT -> "Принтер не ответил вовремя."
                            TcpFailureReason.SOCKET_API -> "Ошибка сокета Android. Скопируйте технический лог."
                            TcpFailureReason.UNKNOWN -> "Ошибка подключения. Скопируйте технический лог."
                        }
                    PrintOutcome(false, token.isCancelled, e.mayHavePrinted, message)
                } catch (e: Exception) {
                    log("job_failed category=${e.javaClass.simpleName} cancelled=${token.isCancelled}")
                    val message = if (token.isCancelled || e is CancellationException || e is RenderFailure && e.errorCode == 4)
                        "Подготовка отменена; данные не отправлены"
                    else when (e) {
                        is NoLocalNetworkException -> "Подключите телефон к Wi-Fi сети принтера"
                        is SpoolSpaceException -> "Недостаточно места во внутреннем хранилище. Освободите место и повторите."
                        is PrintLimitException -> "Превышен лимит: PDF 256 МБ, задание 500 страниц с копиями, поток 512 МБ."
                        is RenderFailure -> {
                            log("renderer_error=${e.errorCode}")
                            when (e.errorCode) {
                                2 -> "PDF защищён паролем или не поддерживается"
                                3 -> "Некорректный PDF, размер страницы или диапазон страниц"
                                6 -> "Превышен лимит задания или доступного места для подготовки"
                                else -> "Ошибка подготовки PDF. Скопируйте технический лог."
                            }
                        }
                        else -> "Ошибка подготовки задания. Скопируйте технический лог."
                    }
                    PrintOutcome(false, token.isCancelled || e is CancellationException || e is RenderFailure && e.errorCode == 4, false, message)
                } finally {
                    dir?.let { log("spool_cleaned=${runCatching { it.deleteRecursively() }.getOrDefault(false)}") }
                }
                status(result.message)
                log("job_end sent=${result.sent} cancelled=${result.cancelled} may_have_printed=${result.mayHavePrinted}")
                diagnostics.checkpoint()
                synchronized(lock) { current = null; pending--; stage = "idle" }
                publish()
                main.post { finished(result) }
            } finally { keepAlive.close() }
        }
        return token
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val DISK_RESERVE = 64 * MIB
        const val MAX_PDF_BYTES = 256 * MIB
        const val MAX_HBP_BYTES = 512 * MIB

        /** A print-spooler descriptor can be a pipe; first copy it to a private seekable file. */
        fun copyPdf(input: InputStream, output: File, token: CancelScope) {
            val remove = token.onCancel { input.close() }
            try {
                input.use { src -> FileOutputStream(output).use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        token.check()
                        val n = src.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > MAX_PDF_BYTES) throw PrintLimitException()
                        dst.write(buffer, 0, n)
                    }
                    require(count > 0)
                } }
            } finally { remove() }
        }
        fun copyPdf(input: ParcelFileDescriptor, output: File, token: CancelScope) =
            copyPdf(ParcelFileDescriptor.AutoCloseInputStream(input), output, token)
    }
}
