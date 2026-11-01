package ru.brotherfastprint.core

import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException
import java.nio.channels.IllegalBlockingModeException
import java.util.Locale

enum class TcpFailureReason { REFUSED, TIMEOUT, UNREACHABLE, PERMISSION, SOCKET_API, UNKNOWN }

data class TcpErrorInfo(val reason: TcpFailureReason, val category: String, val chain: String, val detail: String)

object TcpDiagnostics {
    fun describe(error: Throwable): TcpErrorInfo {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        // Only socket/platform messages are allowed. General IO errors can contain file paths.
        val networkCauses = causes.filter {
            it is SocketException || it is SocketTimeoutException || it is SecurityException ||
                it is UnsupportedOperationException || it is IllegalBlockingModeException ||
                it is ClosedChannelException || it.javaClass.name == "android.system.ErrnoException"
        }
        val text = networkCauses.mapNotNull { it.message }.joinToString("; ").lowercase(Locale.ROOT)
        val reason = when {
            networkCauses.any { it is SecurityException } ||
                listOf("eacces", "eperm", "permission denied", "not permitted").any { it in text } -> TcpFailureReason.PERMISSION
            "econnrefused" in text || "connection refused" in text -> TcpFailureReason.REFUSED
            networkCauses.any { it is SocketTimeoutException } || "etimedout" in text -> TcpFailureReason.TIMEOUT
            listOf("enetunreach", "ehostunreach", "eaddrnotavail", "no route to host", "network is unreachable").any { it in text } -> TcpFailureReason.UNREACHABLE
            networkCauses.any { it is UnsupportedOperationException || it is IllegalBlockingModeException || it is ClosedChannelException } ||
                listOf("ebadf", "enotsock", "bad file descriptor", "socket is closed").any { it in text } -> TcpFailureReason.SOCKET_API
            else -> TcpFailureReason.UNKNOWN
        }
        val detail = networkCauses.mapNotNull { it.message }.distinct().joinToString("; ")
            .map { if (it.isISOControl()) ' ' else it }.joinToString("").take(240)
        return TcpErrorInfo(reason, causes.last().javaClass.simpleName,
            causes.joinToString(">") { it.javaClass.simpleName }, detail)
    }
}
