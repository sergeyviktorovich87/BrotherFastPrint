package ru.brotherfastprint.service

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import ru.brotherfastprint.MainActivity
import ru.brotherfastprint.PrinterConfig
import ru.brotherfastprint.core.*
import ru.brotherfastprint.printing.LocalNetwork
import ru.brotherfastprint.printing.PrintEngine
import ru.brotherfastprint.printing.ReachabilityCache
import ru.brotherfastprint.printing.ServiceDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** System-owned discovery. No Activity, UI observer, multicast or permanently running service needed. */
class BrotherDiscoverySession(private val service: BrotherPrintService, private val engine: PrintEngine) : PrinterDiscoverySession() {
    private val main = ServiceDispatcher.mainHandler()
    private val worker = Executors.newSingleThreadExecutor()
    private val manager = service.getSystemService(ConnectivityManager::class.java)
    private val id = service.generatePrinterId(PrinterConfig.LOCAL_ID)
    private val prefs = service.getSharedPreferences("printer", 0)
    private val reachability = Reachability()
    private var discovering = false
    private var tracking = false
    private var closed = false
    internal val hasActiveRequests: Boolean get() = !closed && (discovering || tracking)
    private var busy = engine.snapshot().busy
    private var probing: CancelScope? = null
    private var defaultRegistered = false
    private var lanRegistered = false
    private val session = nextSession.incrementAndGet()
    private var probeDeadline: DispatchDeadline? = null
    private var lastPublished: PrinterInfo? = null
    private val poll = Runnable {
        val timing = probeDeadline?.measure(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
        if (timing != null && timing.elapsedLateMs > 1000)
            engine.logService("discovery_dispatch_late session=$session late_ms=${timing.elapsedLateMs} awake_late_ms=${timing.awakeLateMs} sleep_ms=${timing.sleepMs}")
        probe()
    }

    private val observer = engine.observe { state ->
        if (!closed) {
            if (state.busy != busy) {
                busy = state.busy
                if (busy) cancelProbe() else scheduleProbe(0)
            }
            publish()
        }
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed) when (key) {
            "ip", "port", "duplex" -> refresh("settings_changed")
            ReachabilityCache.TIME_KEY -> {
                val endpoint = endpoint()
                if (endpoint != null && ReachabilityCache.recentlyOnline(service, endpoint)) {
                    cancelProbe(); reachability.confirmedOnline(); publish()
                }
            }
        }
    }
    private fun callback(source: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh("${source}_available")
        override fun onLost(network: Network) = refresh("${source}_lost")
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh("${source}_capabilities")
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = refresh("${source}_link")
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = refresh("${source}_blocked_$blocked")
    }
    private val defaultCallback = callback("default")
    private val lanCallback = callback("lan")

    init {
        endpoint()?.let { if (ReachabilityCache.recentlyOnline(service, it)) reachability.confirmedOnline() }
        prefs.registerOnSharedPreferenceChangeListener(changed)
        engine.logService("discovery_created session=$session cached=${reachability.state} connected=${service.isSystemConnected} dispatcher=async")
        // Android attaches the observer AFTER this factory returns, then forwards cached printers.
        // Supply capabilities now, before discovery/tracking or delayed UI callbacks can run.
        publish(initial = true)
    }

    companion object {
        private val nextSession = AtomicInteger()
        fun capabilities(id: PrinterId, defaultDuplex: Int = Duplex.NONE): PrinterCapabilitiesInfo = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addResolution(PrintAttributes.Resolution("600dpi", "600 DPI", 600, 600), true)
            .setMinMargins(PrintAttributes.Margins(112, 223, 112, 112))
            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                when (defaultDuplex) {
                    Duplex.LONG_EDGE -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                    Duplex.SHORT_EDGE -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                    else -> PrintAttributes.DUPLEX_MODE_NONE
                })
            .build()
    }
    private fun endpoint(): Endpoint? = runCatching { PrinterConfig.endpoint(service) }.getOrNull()
    private fun key(endpoint: Endpoint) = "${endpoint.host}:${endpoint.port}"
    private fun publish(initial: Boolean = false) {
        if (closed || (!initial && !service.isSystemConnected)) return
        val endpoint = endpoint()
        // Android has no UNKNOWN status. Keep the saved manual printer selectable while checking;
        // UNAVAILABLE is reserved for an actual failed check, not merely a fresh process/session.
        val state = when {
            endpoint == null -> PrinterInfo.STATUS_UNAVAILABLE
            busy -> PrinterInfo.STATUS_BUSY
            reachability.selectable -> PrinterInfo.STATUS_IDLE
            else -> PrinterInfo.STATUS_UNAVAILABLE
        }
        val detail = when (reachability.state) {
            Reachability.State.UNKNOWN -> "проверка доступности"
            Reachability.State.ONLINE -> "RAW доступен"
            Reachability.State.OFFLINE -> "нет соединения"
        }
        val settings = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val description = if (endpoint == null) "Сохраните IP в BrotherFastPrint"
            else "${endpoint.host}:${endpoint.port} · $detail · A4 / 600 DPI"
        val info = PrinterInfo.Builder(id, PrinterConfig.NAME + " · FastPrint", state)
            .setDescription(description).setCapabilities(capabilities(id, PrinterConfig.duplex(service))).setInfoIntent(settings).build()
        PrinterPublisher.publish(this, info)
        if (lastPublished != info || initial) {
            engine.logService("printer_published session=$session status=$state capabilities=true duplex_modes=7 initial=$initial discovery_active=$isPrinterDiscoveryStarted tracking=$tracking connected=${service.isSystemConnected} cached_count=${printers.size}")
            lastPublished = info
        }
    }
    private fun cancelProbe() {
        probing?.cancel(); probing = null
        reachability.invalidate()
    }
    private fun refresh(reason: String) {
        if (closed) return
        cancelProbe()
        engine.logService("discovery_refresh session=$session reason=$reason connected=${service.isSystemConnected}")
        publish(); scheduleProbe(250)
    }
    private fun watchNetworks() {
        if (closed || !service.isSystemConnected) return
        if (!defaultRegistered) runCatching {
            manager.registerDefaultNetworkCallback(defaultCallback, main); defaultRegistered = true
        }.onFailure { engine.logService("network_watch_default_error category=${it.javaClass.simpleName}") }
        if (!lanRegistered) runCatching {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            manager.registerNetworkCallback(request, lanCallback, main); lanRegistered = true
        }.onFailure { engine.logService("network_watch_lan_error category=${it.javaClass.simpleName}") }
    }
    private fun unwatchNetworks() {
        if (defaultRegistered) runCatching { manager.unregisterNetworkCallback(defaultCallback) }
        if (lanRegistered) runCatching { manager.unregisterNetworkCallback(lanCallback) }
        defaultRegistered = false; lanRegistered = false
    }
    private fun scheduleProbe(delay: Long) {
        main.removeCallbacks(poll)
        if (!closed && service.isSystemConnected && (discovering || tracking)) {
            probeDeadline = DispatchDeadline(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), delay)
            main.postDelayed(poll, delay)
        }
    }
    private fun probe() {
        if (closed || !service.isSystemConnected || probing != null) return
        if (busy) { scheduleProbe(3_000); return }
        val endpoint = endpoint() ?: run { publish(); return }
        val scope = CancelScope(); probing = scope
        val ticket = reachability.beginProbe()
        engine.logService("discovery_probe_begin host=${endpoint.host} port=${endpoint.port}")
        worker.execute {
            val result = runCatching {
                val routes = LocalNetwork.routes(service, endpoint, engine::logService)
                RawTcpTransport(connectTimeoutMs = 3_000, diagnostic = engine::logService).probe(endpoint, scope, routes)
            }
            val error = result.exceptionOrNull()
            if (error != null && !scope.isCancelled) {
                val info = TcpDiagnostics.describe(error)
                engine.logService("discovery_probe_error reason=${info.reason} category=${info.category}")
                if (info.detail.isNotEmpty()) engine.logService("tcp_error_detail=${info.detail}")
            }
            main.post {
                if (closed || !service.isSystemConnected || probing !== scope || scope.isCancelled) return@post
                probing = null
                if (endpoint()?.let(::key) != key(endpoint)) return@post
                if (result.isSuccess) reachability.finishProbe(ticket, true)
                else if (error != null && ProbePolicy.definitiveOffline(TcpDiagnostics.describe(error).reason)) reachability.finishProbe(ticket, false)
                else reachability.invalidate()
                engine.logService("discovery_probe_end online=${result.isSuccess}")
                if (result.isSuccess) ReachabilityCache.markOnline(service, endpoint)
                publish(); scheduleProbe(15_000)
            }
        }
    }
    fun systemDisconnected() {
        main.removeCallbacks(poll); cancelProbe(); unwatchNetworks()
    }
    fun systemConnected() {
        if (!closed && (discovering || tracking)) { watchNetworks(); refresh("system_reconnected") }
    }
    private fun stopIfUnused() {
        if (!discovering && !tracking) {
            main.removeCallbacks(poll); cancelProbe(); unwatchNetworks()
        }
    }
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        discovering = true; watchNetworks(); refresh("discovery_start")
        service.requestQueueCheck()
    }
    override fun onStopPrinterDiscovery() {
        engine.logService("discovery_stop session=$session tracking=$tracking")
        discovering = false; stopIfUnused()
    }
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        if (id in printerIds) {
            cancelProbe(); publish(); probe()
        }
    }
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        if (printerId == id) {
            tracking = true; watchNetworks(); refresh("tracking_start")
            service.requestQueueCheck()
        }
    }
    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        if (printerId == id) {
            engine.logService("tracking_stop session=$session discovering=$discovering")
            tracking = false; stopIfUnused()
        }
    }
    override fun onDestroy() {
        closed = true
        main.removeCallbacks(poll); cancelProbe(); unwatchNetworks(); observer()
        prefs.unregisterOnSharedPreferenceChangeListener(changed)
        worker.shutdownNow()
        engine.logService("discovery_destroyed session=$session")
    }
}
