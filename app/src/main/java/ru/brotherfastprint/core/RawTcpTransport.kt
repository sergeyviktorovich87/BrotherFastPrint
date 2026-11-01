package ru.brotherfastprint.core

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

class TransmissionException(val mayHavePrinted: Boolean, val acceptedBytes: Long, cause: Throwable, val phase: String = "unknown") :
    IOException("RAW transmission failed; mayHavePrinted=$mayHavePrinted; acceptedBytes=$acceptedBytes", cause)

private class TcpConnectionException(val phase: String, cause: Throwable) : IOException("TCP failed at $phase", cause)

/** One connection per job. Retries connect only; never replays after write was attempted. */
class RawTcpTransport(
    private val connectTimeoutMs: Long = 5_000,
    private val idleWriteTimeoutMs: Long = 15_000,
    private val totalTimeoutMs: Long = 120_000,
    private val diagnostic: (String) -> Unit = {},
) {
    private fun deadline(ms: Long) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

    private fun waitReady(selector: Selector, until: Long, cancel: CancelScope) {
        cancel.check()
        val left = until - System.nanoTime()
        if (left <= 0) throw SocketTimeoutException()
        selector.select(minOf(250L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(left))))
        selector.selectedKeys().clear()
    }

    private fun connect(endpoint: Endpoint, cancel: CancelScope, routes: List<TcpRoute>): SocketChannel {
        require(routes.size in 1..2)
        var last: Exception? = null
        repeat(2) { attempt ->
            val route = routes[minOf(attempt, routes.lastIndex)]
            cancel.check()
            var channel: SocketChannel? = null
            var remove: () -> Unit = {}
            var phase = "open"
            try {
                diagnostic("tcp_attempt=${attempt + 1} route=${route.label} phase=$phase")
                val opened = SocketChannel.open().also { channel = it }
                remove = cancel.onCancel { opened.close() }
                phase = "bind"
                diagnostic("tcp_attempt=${attempt + 1} phase=$phase")
                route.bind(opened.socket())
                phase = "configure"
                diagnostic("tcp_attempt=${attempt + 1} phase=$phase")
                opened.socket().tcpNoDelay = true
                opened.configureBlocking(false)
                Selector.open().use { selector ->
                    opened.register(selector, SelectionKey.OP_CONNECT)
                    val until = deadline(connectTimeoutMs)
                    phase = "connect"
                    diagnostic("tcp_attempt=${attempt + 1} phase=$phase")
                    if (!opened.connect(endpoint.socketAddress())) {
                        while (!opened.finishConnect()) waitReady(selector, until, cancel)
                    }
                }
                cancel.check()
                diagnostic("tcp_connected attempt=${attempt + 1} route=${route.label}")
                return opened
            } catch (e: Exception) {
                runCatching { channel?.close() }
                cancel.check()
                val info = TcpDiagnostics.describe(e)
                diagnostic("tcp_attempt_error attempt=${attempt + 1} route=${route.label} phase=$phase reason=${info.reason} category=${info.category} chain=${info.chain} detail=${info.detail}")
                last = TcpConnectionException(phase, e)
            } finally { remove() }
        }
        throw last ?: IOException("Connection failed")
    }

    fun probe(endpoint: Endpoint, cancel: CancelScope, bind: (Socket) -> Unit) {
        probe(endpoint, cancel, listOf(TcpRoute("provided", bind)))
    }

    fun probe(endpoint: Endpoint, cancel: CancelScope, routes: List<TcpRoute>) {
        try {
            connect(endpoint, cancel, routes).use { /* No print bytes. A listening port is not paper/toner status. */ }
        } catch (e: Exception) {
            throw TransmissionException(false, 0, e, (e as? TcpConnectionException)?.phase ?: "connect")
        }
    }

    fun send(file: File, endpoint: Endpoint, cancel: CancelScope, bind: (Socket) -> Unit, progress: (Long) -> Unit): Long {
        return send(file, endpoint, cancel, listOf(TcpRoute("provided", bind)), progress)
    }

    fun send(file: File, endpoint: Endpoint, cancel: CancelScope, routes: List<TcpRoute>, progress: (Long) -> Unit): Long {
        require(file.length() > 0)
        var written = 0L
        var attempted = false
        var phase = "connect"
        try {
            connect(endpoint, cancel, routes).use { channel ->
                val remove = cancel.onCancel { channel.close() }
                try {
                    phase = "configure_write"
                    Selector.open().use { selector ->
                        channel.register(selector, SelectionKey.OP_WRITE)
                        val totalEnd = deadline(totalTimeoutMs)
                        var idleEnd = deadline(idleWriteTimeoutMs)
                        phase = "file_read"
                        FileInputStream(file).use { input ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                cancel.check()
                                phase = "file_read"
                                val count = input.read(bytes)
                                if (count < 0) break
                                val buffer = ByteBuffer.wrap(bytes, 0, count)
                                while (buffer.hasRemaining()) {
                                    cancel.check()
                                    if (System.nanoTime() >= minOf(totalEnd, idleEnd)) throw SocketTimeoutException()
                                    attempted = true // Even a throwing write may have sent bytes.
                                    phase = "write"
                                    val n = channel.write(buffer)
                                    if (n > 0) { written += n; idleEnd = deadline(idleWriteTimeoutMs) }
                                    else waitReady(selector, minOf(totalEnd, idleEnd), cancel)
                                }
                                progress(written)
                            }
                        }
                        cancel.check()
                        phase = "shutdown"
                        channel.shutdownOutput()
                    }
                } finally { remove() }
            }
            return written // OS accepted bytes; NOT confirmation of physical printing.
        } catch (e: Exception) {
            throw TransmissionException(attempted, written, e, (e as? TcpConnectionException)?.phase ?: phase)
        }
    }
}
