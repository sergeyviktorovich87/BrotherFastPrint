package ru.brotherfastprint.core

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RawTcpTransportTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun endpoint(port: Int) = Endpoint("127.0.0.1", port, loopback)

    @Test fun routeFallbackOccursBeforeAnyPrintBytesAndSendsOnlyOnce() {
        ServerSocket(0, 1, loopback).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val file = File.createTempFile("bfp-routes-", ".bin")
            try {
                val bytes = ByteArray(200_001) { (it % 251).toByte() }; file.writeBytes(bytes)
                val received = executor.submit<ByteArray> { server.accept().use { it.getInputStream().readBytes() } }
                val attempts = mutableListOf<String>()
                val diagnostics = mutableListOf<String>()
                val routes = listOf(
                    TcpRoute("system_default") { attempts += "default"; throw java.net.SocketException("EACCES (Permission denied)") },
                    TcpRoute("wifi_direct") { attempts += "wifi" },
                )
                assertEquals(bytes.size.toLong(), RawTcpTransport(diagnostic = diagnostics::add).send(file, endpoint(server.localPort), CancelScope(), routes) {})
                assertArrayEquals(bytes, received.get(5, TimeUnit.SECONDS))
                assertEquals(listOf("default", "wifi"), attempts)
                assertTrue(diagnostics.any { "tcp_connected" in it && "route=wifi_direct" in it })
                server.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept() }
            } finally { file.delete(); executor.shutdownNow() }
        }
    }

    @Test fun cancellationDuringRouteSelectionDoesNotTryBackup() {
        val scope = CancelScope()
        var backup = false
        val routes = listOf(
            TcpRoute("system_default") { scope.cancel(); scope.check() },
            TcpRoute("wifi_direct") { backup = true },
        )
        val error = assertThrows(TransmissionException::class.java) {
            RawTcpTransport().probe(endpoint(12345), scope, routes)
        }
        assertFalse(backup)
        assertFalse(error.mayHavePrinted)
        assertEquals(0L, error.acceptedBytes)
    }

    @Test fun refusedProbeReportsAConnectErrorWithZeroPrintBytes() {
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val diagnostics = mutableListOf<String>()
        val error = assertThrows(TransmissionException::class.java) {
            RawTcpTransport(diagnostic = diagnostics::add).probe(endpoint(closedPort), CancelScope(), {})
        }
        assertEquals("connect", error.phase)
        assertEquals(TcpFailureReason.REFUSED, TcpDiagnostics.describe(error).reason)
        assertFalse(error.mayHavePrinted)
        assertEquals(0L, error.acceptedBytes)
        assertTrue(diagnostics.any { "phase=connect" in it && "reason=REFUSED" in it })
    }

    @Test fun networkBindingErrorIsNotMisreportedAsPrinterRefusal() {
        val file = File.createTempFile("bfp-bind-", ".bin")
        val diagnostics = mutableListOf<String>()
        val original = java.net.SocketException("getpeername failed: EBADF (Bad file descriptor)")
        try {
            file.writeText("not sent")
            val error = assertThrows(TransmissionException::class.java) {
                RawTcpTransport(diagnostic = diagnostics::add).send(file, endpoint(12345), CancelScope(), { throw original }) {}
            }
            assertEquals("bind", error.phase)
            assertEquals(TcpFailureReason.SOCKET_API, TcpDiagnostics.describe(error).reason)
            assertFalse(error.mayHavePrinted)
            assertEquals(0L, error.acceptedBytes)
            assertTrue(generateSequence(error as Throwable) { it.cause }.any { it === original })
            assertTrue(diagnostics.any { "phase=bind" in it && "reason=SOCKET_API" in it })
            assertFalse(diagnostics.any { "phase=connect" in it })
        } finally { file.delete() }
    }

    @Test fun entireJobUsesOneConnectionAndEndsWithEof() {
        ServerSocket(0, 1, loopback).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val file = File.createTempFile("bfp-test-", ".bin")
            try {
                val data = ByteArray(700_001) { ((it * 37) % 251).toByte() }; file.writeBytes(data)
                val received = executor.submit<ByteArray> {
                    server.accept().use { it.getInputStream().readBytes() }
                }
                val sent = RawTcpTransport().send(file, endpoint(server.localPort), CancelScope(), {}) {}
                assertEquals(data.size.toLong(), sent)
                assertArrayEquals(data, received.get(5, TimeUnit.SECONDS))
                server.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept() }
            } finally { file.delete(); executor.shutdownNow() }
        }
    }

    @Test fun peerResetNeverReplaysAnUncertainJob() {
        ServerSocket(0, 5, loopback).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val file = File.createTempFile("bfp-reset-", ".bin")
            try {
                file.outputStream().use { out -> repeat(256) { out.write(ByteArray(64 * 1024)) } }
                val peer = executor.submit {
                    server.accept().use { client ->
                        client.getInputStream().read()
                        client.setSoLinger(true, 0)
                    }
                }
                var backup = false
                val routes = listOf(TcpRoute("system_default") {}, TcpRoute("wifi_direct") { backup = true })
                val error = assertThrows(TransmissionException::class.java) {
                    RawTcpTransport().send(file, endpoint(server.localPort), CancelScope(), routes) {}
                }
                assertTrue(error.mayHavePrinted)
                assertFalse(backup)
                peer.get(5, TimeUnit.SECONDS)
                server.soTimeout = 200
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept() }
            } finally { file.delete(); executor.shutdownNow() }
        }
    }

    @Test fun stalledReaderHitsAnActualWriteDeadline() {
        ServerSocket().use { server ->
            server.receiveBufferSize = 1024
            server.bind(java.net.InetSocketAddress(loopback, 0))
            val executor = Executors.newSingleThreadExecutor()
            val stop = java.util.concurrent.CountDownLatch(1)
            val file = File.createTempFile("bfp-stall-", ".bin")
            try {
                java.io.RandomAccessFile(file, "rw").use { it.setLength(32L * 1024 * 1024) }
                val peer = executor.submit { server.accept().use { stop.await(4, TimeUnit.SECONDS) } }
                val begin = System.nanoTime()
                val error = assertThrows(TransmissionException::class.java) {
                    RawTcpTransport(idleWriteTimeoutMs = 250, totalTimeoutMs = 2000).send(file, endpoint(server.localPort), CancelScope(), {}) {}
                }
                assertTrue(error.mayHavePrinted)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 3000)
                stop.countDown(); peer.get(5, TimeUnit.SECONDS)
            } finally { stop.countDown(); file.delete(); executor.shutdownNow() }
        }
    }

    @Test fun cancellationBeforeConnectSendsNothing() {
        val file = File.createTempFile("bfp-cancel-", ".bin")
        try {
            file.writeText("test")
            val scope = CancelScope().apply { cancel() }
            val e = assertThrows(TransmissionException::class.java) {
                RawTcpTransport().send(file, endpoint(12345), scope, {}) {}
            }
            assertFalse(e.mayHavePrinted)
            assertEquals(0, e.acceptedBytes)
        } finally { file.delete() }
    }
}
