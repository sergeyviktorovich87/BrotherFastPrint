package ru.brotherfastprint.core

import java.net.InetAddress
import java.net.InetSocketAddress

/** Numeric RFC1918/link-local IPv4 only. Never resolves a hostname. */
class Endpoint internal constructor(val host: String, val port: Int, val address: InetAddress) {
    fun socketAddress() = InetSocketAddress(address, port)

    companion object {
        fun parse(ip: String, port: String): Endpoint {
            val chunks = ip.trim().split('.')
            require(chunks.size == 4) { "Введите локальный IPv4, например 192.168.1.41" }
            val octets = chunks.map {
                require(it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' })
                it.toInt().also { n -> require(n in 0..255) }
            }
            val a = octets[0]
            val b = octets[1]
            require(a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254) {
                "Допустим только локальный IPv4-адрес"
            }
            val p = port.toIntOrNull()
            require(p != null && p in 1..65535) { "Порт: от 1 до 65535" }
            return Endpoint(octets.joinToString("."), p, InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()))
        }
    }
}
