package ru.brotherfastprint.printing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ru.brotherfastprint.core.Endpoint
import ru.brotherfastprint.core.LanRouteKind
import ru.brotherfastprint.core.LanRouting
import ru.brotherfastprint.core.TcpRoute
import java.io.IOException

class NoLocalNetworkException : IOException()

object LocalNetwork {
    private fun NetworkCapabilities.isLan() = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    @Suppress("DEPRECATION")
    fun routes(context: Context, endpoint: Endpoint, log: (String) -> Unit = {}): List<TcpRoute> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val activeCaps = active?.let(manager::getNetworkCapabilities)
        val vpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val physical = manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isLan() || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = manager.getLinkProperties(network)
            val match = link?.linkAddresses?.any { address ->
                address.address is java.net.Inet4Address &&
                    LanRouting.sameIpv4Subnet(endpoint.address.address, address.address.address, address.prefixLength)
            } == true
            Triple(network, link, match)
        }.sortedByDescending { it.third }.firstOrNull()
        val order = LanRouting.order(vpn, physical != null, activeCaps?.isLan() == true)
        val link = physical?.second
        val ipv4 = link?.linkAddresses?.filter { it.address is java.net.Inet4Address }
            ?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" } ?: "unknown"
        log("network_plan vpn_active=$vpn default_id=${active ?: "none"} physical_id=${physical?.first ?: "none"} iface=${link?.interfaceName ?: "unknown"} local_ipv4=$ipv4 order=${order.joinToString(",")}")
        if (order.isEmpty()) throw NoLocalNetworkException()
        return order.map { kind -> when (kind) {
            LanRouteKind.SYSTEM_DEFAULT -> TcpRoute("system_default") { /* Honour Android/VPN LAN routing. */ }
            LanRouteKind.PHYSICAL -> {
                val network: Network = physical!!.first
                TcpRoute("wifi_direct") { network.bindSocket(it) }
            }
        } }
    }
}
