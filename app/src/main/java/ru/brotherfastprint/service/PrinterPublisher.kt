package ru.brotherfastprint.service

import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession

/** Keep a complete snapshot even on framework versions that ignore updates outside discovery. */
object PrinterPublisher {
    fun publish(session: PrinterDiscoverySession, printer: PrinterInfo) {
        session.addPrinters(listOf(printer))
        if (!session.isPrinterDiscoveryStarted && session.printers.firstOrNull { it.id == printer.id } != printer) {
            // AOSP buffers this remove/add; fixed vendor implementations need no workaround.
            session.removePrinters(listOf(printer.id))
            session.addPrinters(listOf(printer))
        }
    }
}
