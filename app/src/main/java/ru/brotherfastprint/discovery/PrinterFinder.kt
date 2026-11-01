package ru.brotherfastprint.discovery

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import ru.brotherfastprint.PrinterConfig
import ru.brotherfastprint.core.*
import ru.brotherfastprint.printing.ServiceDispatcher
import ru.brotherfastprint.printing.LocalNetwork
import ru.brotherfastprint.printing.ReachabilityCache
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.Executor

class PrinterFinder(private val context: Context, private val log: (String) -> Unit) {
    data class Found(val name: String, val endpoint: Endpoint)
    private val main = ServiceDispatcher.mainHandler()
    private val executor = Executor { command -> main.post(command); Unit }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val worker = Executors.newFixedThreadPool(2)
    private val cancel = CancelScope()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val infoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
    private val seen = mutableSetOf<String>()
    private val tested = mutableSetOf<String>()
    private val found = linkedMapOf<String, Found>()
    private val legacy = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var finished = false
    private var multicast: WifiManager.MulticastLock? = null
    private var update: (List<Found>, Boolean) -> Unit = { _, _ -> }
    private val timeout = Runnable { finish() }

    /** Main-thread entry; 15-second bounded search. No print bytes are sent. */
    fun start(onUpdate: (List<Found>, Boolean) -> Unit) {
        update = onUpdate
        log("search_begin api=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < 33) runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            multicast = wifi.createMulticastLock("BrotherFastPrint search").apply { setReferenceCounted(false); acquire() }
        }.onFailure { log("search_multicast_error category=${it.javaClass.simpleName}") }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        for (type in PrinterAdvertisement.serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { if (finished) runCatching { nsd.stopServiceDiscovery(this) } }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { log("search_discovery_error type=$type code=$errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { log("search_stop_error code=$errorCode") }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) { main.post { resolve(serviceInfo) } }
            }
            listeners += listener
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, request, executor, listener)
                else nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { log("search_start_error category=${it.javaClass.simpleName}") }
        }
        main.postDelayed(timeout, 15_000)
    }

    private fun resolve(service: NsdServiceInfo) {
        if (finished || seen.size >= 48) return
        val key = service.serviceType + "/" + service.serviceName + if (Build.VERSION.SDK_INT >= 33) "/${service.network}" else ""
        if (!seen.add(key)) return
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { log("search_resolve_error code=$errorCode") }
                override fun onServiceInfoCallbackUnregistered() {}
                override fun onServiceLost() {}
                override fun onServiceUpdated(info: NsdServiceInfo) { inspect(info) }
            }
            infoCallbacks += callback
            runCatching { nsd.registerServiceInfoCallback(service, executor, callback) }
                .onFailure { log("search_resolve_error category=${it.javaClass.simpleName}") }
        } else {
            legacy.addLast(service); resolveNext()
        }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (finished || resolving || legacy.isEmpty()) return
        resolving = true
        runCatching { nsd.resolveService(legacy.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post { resolving = false; inspect(serviceInfo); resolveNext() }
            }
        }) }.onFailure {
            resolving = false; log("search_resolve_error category=${it.javaClass.simpleName}"); resolveNext()
        }
    }
    @Suppress("DEPRECATION")
    private fun inspect(info: NsdServiceInfo) {
        if (finished) return
        val attributes = info.attributes.mapValues { (_, v) -> v.take(512).toByteArray().toString(Charsets.UTF_8) }
        if (!PrinterAdvertisement.supported(info.serviceName.take(256), attributes)) return
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        for (address in addresses.filterIsInstance<Inet4Address>().take(4)) {
            val endpoint = runCatching { PrinterAdvertisement.rawEndpoint(address.hostAddress!!, info.serviceType, info.port) }.getOrNull() ?: continue
            val key = "${endpoint.host}:${endpoint.port}"
            if (!tested.add(key) || tested.size > 24) continue
            worker.execute {
                val available = runCatching {
                    cancel.check()
                    val routes = LocalNetwork.routes(context, endpoint, log)
                    RawTcpTransport(connectTimeoutMs = 1500, diagnostic = log).probe(endpoint, cancel, routes)
                }.isSuccess
                main.post {
                    if (!finished && available) {
                        found[key] = Found(PrinterConfig.NAME, endpoint)
                        ReachabilityCache.markOnline(context, endpoint)
                        log("search_found host=${endpoint.host} raw_port=${endpoint.port}")
                        update(found.values.toList(), false)
                    }
                }
            }
        }
    }
    fun finish() {
        if (finished) return
        finished = true
        cancel.cancel(); main.removeCallbacks(timeout)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear()
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        infoCallbacks.clear(); legacy.clear()
        runCatching { multicast?.let { if (it.isHeld) it.release() } }; multicast = null
        worker.shutdownNow()
        log("search_end found=${found.size}")
        update(found.values.toList(), true)
    }
}
