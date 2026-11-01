package ru.brotherfastprint

import android.content.Context
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.brotherfastprint.core.Duplex
import ru.brotherfastprint.printing.ServiceDispatcher
import ru.brotherfastprint.service.BrotherDiscoverySession
import ru.brotherfastprint.service.PrinterPublisher
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Actual framework cache behavior; no printer, hidden API, reflection or system binding needed. */
class PrinterPublicationTest {
    private class IdProvider(context: Context) : PrintService() {
        init { attachBaseContext(context) }
        override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()
        override fun onPrintJobQueued(printJob: PrintJob) {}
        override fun onRequestCancelPrintJob(printJob: PrintJob) {}
    }
    private class Session : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {}
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }
    private fun onMain(test: (PrinterId, Session) -> Unit) {
        val task = FutureTask {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val id = IdProvider(context).generatePrinterId("publication_test")
            test(id, Session())
        }
        ServiceDispatcher.mainHandler().post(task)
        task.get(5, TimeUnit.SECONDS)
    }
    @Test fun initialSnapshotHasReadyStatusAndBothDuplexModesBeforeObserverAttachment() = onMain { id, session ->
        val printer = PrinterInfo.Builder(id, "Test printer", PrinterInfo.STATUS_IDLE)
            .setCapabilities(BrotherDiscoverySession.capabilities(id, Duplex.LONG_EDGE)).build()
        PrinterPublisher.publish(session, printer)
        assertFalse(session.isPrinterDiscoveryStarted)
        val saved = session.printers.single()
        assertEquals(PrinterInfo.STATUS_IDLE, saved.status)
        assertNotNull(saved.capabilities)
        assertEquals(7, saved.capabilities!!.duplexModes)
        assertEquals(Duplex.LONG_EDGE, saved.capabilities!!.defaults.duplexMode)
    }
    @Test fun cachedUnavailablePrinterIsReplacedWhileFullDiscoveryIsStopped() = onMain { id, session ->
        val stale = PrinterInfo.Builder(id, "Test printer", PrinterInfo.STATUS_UNAVAILABLE).build()
        PrinterPublisher.publish(session, stale)
        val ready = PrinterInfo.Builder(id, "Test printer", PrinterInfo.STATUS_IDLE)
            .setCapabilities(BrotherDiscoverySession.capabilities(id, Duplex.SHORT_EDGE)).build()
        PrinterPublisher.publish(session, ready)
        val saved = session.printers.single()
        assertEquals(ready, saved)
        assertEquals(Duplex.SHORT_EDGE, saved.capabilities!!.defaults.duplexMode)
    }
}
