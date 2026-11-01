package ru.brotherfastprint.core

import java.net.Socket

class TcpRoute(val label: String, val bind: (Socket) -> Unit) {
    init { require(label.matches(Regex("[a-z_]{1,32}"))) }
}

enum class LanRouteKind { SYSTEM_DEFAULT, PHYSICAL }

object LanRouting {
    /** A VPN may implement LAN exclusions internally while forbidding an explicit Wi-Fi bind. */
    fun order(vpnActive: Boolean, physicalAvailable: Boolean, defaultOnLan: Boolean): List<LanRouteKind> = when {
        vpnActive && (physicalAvailable || defaultOnLan) ->
            listOf(LanRouteKind.SYSTEM_DEFAULT) + if (physicalAvailable) listOf(LanRouteKind.PHYSICAL) else emptyList()
        physicalAvailable -> listOf(LanRouteKind.PHYSICAL)
        defaultOnLan -> listOf(LanRouteKind.SYSTEM_DEFAULT)
        else -> emptyList() // Never intentionally choose a cellular-only connection for a LAN printer.
    }

    fun sameIpv4Subnet(target: ByteArray, local: ByteArray, prefix: Int): Boolean {
        require(prefix in 0..32)
        if (target.size != 4 || local.size != 4) return false
        for (i in 0..3) {
            val bits = (prefix - i * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            if ((target[i].toInt() and mask) != (local[i].toInt() and mask)) return false
        }
        return true
    }
}
