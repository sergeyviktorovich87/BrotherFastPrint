package ru.brotherfastprint.printing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import ru.brotherfastprint.FastPrintApp
import ru.brotherfastprint.MainActivity
import ru.brotherfastprint.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Main-thread ownership: overlapping sessions/jobs cannot stop each other's protection. */
object PrintKeepAlive {
    private val main = ServiceDispatcher.mainHandler()
    private val next = AtomicLong()
    private val owners = mutableMapOf<Long, String>()
    private var starting = false
    private var running: PrintKeepAliveService? = null

    fun acquire(context: Context, role: String): AutoCloseable {
        require(role == "system_session" || role == "job")
        val app = context.applicationContext
        val id = next.incrementAndGet()
        val closed = AtomicBoolean()
        main.post {
            if (!closed.get()) {
                owners[id] = role
                if (running == null && !starting) {
                    starting = true
                    try {
                        app.startForegroundService(Intent(app, PrintKeepAliveService::class.java))
                        log(app, "keepalive_requested role=$role owners=${owners.size}")
                    } catch (e: RuntimeException) {
                        starting = false
                        log(app, "keepalive_start_denied category=${e.javaClass.simpleName}")
                    }
                } else running?.refresh()
            }
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) main.post {
                if (owners.remove(id) != null) {
                    if (owners.isEmpty()) {
                        starting = false
                        try { app.stopService(Intent(app, PrintKeepAliveService::class.java)) }
                        catch (e: RuntimeException) { log(app, "keepalive_stop_error category=${e.javaClass.simpleName}") }
                    } else running?.refresh()
                }
            }
        }
    }

    internal fun attach(service: PrintKeepAliveService) { starting = false; running = service }
    internal fun detach(service: PrintKeepAliveService) {
        if (running === service) running = null
        starting = false
    }
    internal fun wanted() = owners.isNotEmpty()
    internal fun hasJobs() = "job" in owners.values
    internal fun log(context: Context, message: String) {
        (context.applicationContext as FastPrintApp).diagnostics.record(message)
    }
}

/** Separate from PrintService: only Android starts/stops the framework print component. */
class PrintKeepAliveService : Service() {
    private val main = ServiceDispatcher.mainHandler()
    private var promoted = false
    private var wakeReported = false
    private var wake: PowerManager.WakeLock? = null
    private val pulse = object : Runnable {
        override fun run() {
            if (!promoted || !PrintKeepAlive.wanted()) { stopSelf(); return }
            try {
                // Each acquisition expires independently if the process stops dispatching.
                wake?.acquire(60_000L)
                if (!wakeReported) {
                    wakeReported = true
                    PrintKeepAlive.log(this@PrintKeepAliveService, "keepalive_wake_acquired held=${wake?.isHeld == true} timeout_ms=60000")
                }
            } catch (e: RuntimeException) {
                PrintKeepAlive.log(this@PrintKeepAliveService, "keepalive_wake_error category=${e.javaClass.simpleName}")
            }
            main.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Печать BrotherFastPrint", NotificationManager.IMPORTANCE_LOW))
            promote()
            promoted = true
            PrintKeepAlive.attach(this)
            wake = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "BrotherFastPrint:PrintSession").apply { setReferenceCounted(false) }
            PrintKeepAlive.log(this, "keepalive_foreground_started type=connectedDevice")
            main.post(pulse)
        } catch (e: RuntimeException) {
            PrintKeepAlive.log(this, "keepalive_promotion_failed category=${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun promote() {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_printer)
            .setContentTitle("BrotherFastPrint")
            .setContentText(if (PrintKeepAlive.hasJobs()) "Подготовка и передача задания принтеру" else "Выбор принтера и ожидание задания")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, builder.build())
    }

    internal fun refresh() {
        if (promoted) try { promote() } catch (e: RuntimeException) {
            PrintKeepAlive.log(this, "keepalive_notification_error category=${e.javaClass.simpleName}")
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted || !PrintKeepAlive.wanted()) stopSelfResult(startId) else refresh()
        return START_NOT_STICKY // No boot start, job redelivery or background resurrection.
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        main.removeCallbacks(pulse)
        try { wake?.let { if (it.isHeld) it.release() } }
        catch (e: RuntimeException) { PrintKeepAlive.log(this, "keepalive_wake_release_error category=${e.javaClass.simpleName}") }
        wake = null
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
        promoted = false
        PrintKeepAlive.detach(this)
        PrintKeepAlive.log(this, "keepalive_stopped wake_released=true")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "system_print"
        private const val NOTIFICATION = 9100
    }
}
