package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class CrashSummaryTest {
    @Test fun exceptionMessagesAndStackFilenamesCannotLeakDocumentData() {
        val cause = IllegalArgumentException("PRIVATE_PDF_BODY /storage/Customer-contract.pdf")
        cause.stackTrace = arrayOf(StackTraceElement("safe.Renderer", "open", "PRIVATE_FILENAME.pdf", 42))
        val error = IllegalStateException("content://PRIVATE_DOCUMENT_URI", cause)
        error.stackTrace = arrayOf(StackTraceElement("safe.Worker", "run", "PRIVATE_FILENAME.pdf", 12))
        val report = CrashSummary.format(error, 7)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("safe.Renderer.open line=42"))
        assertTrue(report.contains("thread_id=7"))
        assertFalse(report.contains("PRIVATE"))
        assertFalse(report.contains("Customer-contract"))
        assertFalse(report.contains("content://"))
    }

    @Test fun cyclicCausesAndHugeStacksStayBounded() {
        val error = object : RuntimeException("secret") { override val cause: Throwable get() = this }
        error.stackTrace = Array(2000) { StackTraceElement("long." + "X".repeat(1000), "method", "secret", it) }
        val report = CrashSummary.format(error, 1)
        assertTrue(report.length <= 8 * 1024)
        assertEquals(4, report.lineSequence().count { it.startsWith("cause=") })
        assertFalse(report.contains("secret"))
    }
}
