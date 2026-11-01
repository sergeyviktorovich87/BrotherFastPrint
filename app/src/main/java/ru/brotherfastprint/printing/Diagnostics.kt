package ru.brotherfastprint.printing

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import ru.brotherfastprint.BuildConfig
import ru.brotherfastprint.PrinterConfig
import ru.brotherfastprint.core.CrashSummary
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the main app UID owns this object. The isolated PDF process gets no storage access. */
class Diagnostics(private val context: Context) {
    private val journal = SupportJournal(context.noBackupFilesDir)
    private val crash = File(context.noBackupFilesDir, "last-java-crash.log")
    private val session = "${Process.myPid()}-${java.lang.Long.toHexString(System.nanoTime())}"
    private val main = ServiceDispatcher.mainHandler()
    private val working = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "diagnostic-report").apply { isDaemon = true } })

    fun record(event: String) { journal.append("utc=${Instant.now()} session=$session $event") }
    fun checkpoint(): Boolean = journal.flush()

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val body = "utc=${Instant.now()} app_version=${BuildConfig.VERSION_NAME} session=$session\n" +
                    "last_event=${journal.lastEvent.get()}\n" + CrashSummary.format(error, thread.id)
                FileOutputStream(crash).use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8)); output.fd.sync()
                }
            } catch (_: Throwable) { /* Preserve Android's original fatal-error handling. */ }
            finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        record("process_started app_version=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT}")
    }

    /** Does disk/Binder work off the UI thread. No trace streams or exit descriptions are read. */
    fun report(): String = buildString {
        val synced = checkpoint()
        append("BrotherFastPrint diagnostic report\n")
        append("app_version=${BuildConfig.VERSION_NAME} package=${context.packageName}\n")
        append("captured_utc=${Instant.now()} session=$session uptime_ms=${SystemClock.elapsedRealtime()}\n")
        append("api=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}\n")
        val endpoint = runCatching { PrinterConfig.endpoint(context) }.getOrNull()
        append("printer_host=${endpoint?.host ?: "invalid"} raw_port=${endpoint?.port ?: 0} duplex=${PrinterConfig.duplex(context)} raster_profile=${ru.brotherfastprint.core.RasterProfile.technicalName(PrinterConfig.rasterProfile(context))} toner_save=${PrinterConfig.tonerSave(context)}\n")
        append("journal_synced=$synced ${journal.health()}\n")
        append("\nprocess_exit_info:\n")
        if (Build.VERSION.SDK_INT < 30) append("unavailable api_below_30\n") else {
            try {
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 8)
                if (exits.isEmpty()) append("no_records\n")
                exits.forEach { info ->
                    val process = when {
                        info.processName == context.packageName -> "main"
                        info.processName.startsWith(context.packageName + ":pdf_sandbox") -> "pdf_sandbox"
                        else -> "other"
                    }
                    val reason = when (info.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                        ApplicationExitInfo.REASON_CRASH -> "java_crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                        ApplicationExitInfo.REASON_ANR -> "anr"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                        ApplicationExitInfo.REASON_SIGNALED -> "signal"
                        else -> "other"
                    }
                    append("utc=${Instant.ofEpochMilli(info.timestamp)} pid=${info.pid} process=$process reason=${info.reason}:$reason status=${info.status} importance=${info.importance} pss_kib=${info.pss} rss_kib=${info.rss}\n")
                }
            } catch (e: Exception) { append("exit_info_error=${e.javaClass.simpleName}\n") }
        }
        append("\nlast_java_crash:\n")
        append(runCatching {
            if (!crash.isFile) "none\n" else crash.inputStream().use { input ->
                val bytes = ByteArray(16 * 1024)
                var count = 0
                while (count < bytes.size) {
                    val n = input.read(bytes, count, bytes.size - count)
                    if (n < 0) break
                    count += n
                }
                String(bytes, 0, count, Charsets.UTF_8)
            }
        }.getOrDefault("unavailable\n"))
        append("\ntechnical_history:\n")
        append(journal.recent(10000).joinToString("\n"))
        append("\n")
    }

    fun reportAsync(done: (Result<String>) -> Unit): Boolean {
        if (!working.compareAndSet(false, true)) return false
        worker.execute {
            val result = runCatching { report() }
            working.set(false); main.post { done(result) }
        }
        return true
    }

    fun exportAsync(uri: Uri, done: (Boolean) -> Unit): Boolean {
        if (!working.compareAndSet(false, true)) return false
        worker.execute {
            val success = runCatching {
                require(uri.scheme == "content")
                record("diagnostics_export_begin")
                val body = report()
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No report destination")
                output.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            record("diagnostics_export_end success=$success"); checkpoint()
            working.set(false); main.post { done(success) }
        }
        return true
    }
}
