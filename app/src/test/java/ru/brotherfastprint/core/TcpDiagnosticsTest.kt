package ru.brotherfastprint.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class TcpDiagnosticsTest {
    @Test fun refusalPermissionsRoutesAndLocalSocketErrorsStayDistinct() {
        val cases = listOf(
            ConnectException("failed: ECONNREFUSED (Connection refused)") to TcpFailureReason.REFUSED,
            SocketTimeoutException() to TcpFailureReason.TIMEOUT,
            SocketException("failed: ENETUNREACH (Network is unreachable)") to TcpFailureReason.UNREACHABLE,
            SocketException("failed: EPERM (Operation not permitted)") to TcpFailureReason.PERMISSION,
            SecurityException("blocked by Android") to TcpFailureReason.PERMISSION,
            SocketException("getpeername failed: EBADF (Bad file descriptor)") to TcpFailureReason.SOCKET_API,
            UnsupportedOperationException() to TcpFailureReason.SOCKET_API,
        )
        cases.forEach { (cause, expected) ->
            val outer = TransmissionException(false, 0, IOException("wrapper", cause), "bind")
            assertEquals(expected, TcpDiagnostics.describe(outer).reason)
        }
    }

    @Test fun genericIoErrorsDoNotLeakDocumentPathsOrContents() {
        val error = IOException("/private/secret-document.pdf: PRIVATE DOCUMENT TEXT")
        val info = TcpDiagnostics.describe(error)
        assertEquals(TcpFailureReason.UNKNOWN, info.reason)
        assertEquals("", info.detail)
        assertFalse(info.chain.contains("private"))
    }

    @Test fun socketDetailsHaveBoundedLengthAndNoControlCharacters() {
        val info = TcpDiagnostics.describe(SocketException("Connection refused\n\r\u001b" + "x".repeat(500)))
        assertTrue(info.detail.length <= 240)
        assertFalse(info.detail.any { it.isISOControl() })
        assertEquals(TcpFailureReason.REFUSED, info.reason)
    }
}
