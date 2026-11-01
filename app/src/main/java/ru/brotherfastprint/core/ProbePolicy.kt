package ru.brotherfastprint.core

object ProbePolicy {
    // Background/VPN restrictions and timeouts are not proof the saved printer is offline.
    fun definitiveOffline(reason: TcpFailureReason) = reason == TcpFailureReason.REFUSED
}
