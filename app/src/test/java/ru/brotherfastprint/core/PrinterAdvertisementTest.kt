package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class PrinterAdvertisementTest {
    @Test fun modelIsIdentifiedByNameOrTxtBeforeConnecting() {
        assertTrue(PrinterAdvertisement.supported("Brother HL-L2340DWR", emptyMap()))
        assertTrue(PrinterAdvertisement.supported("BRN01234", mapOf("ty" to "Brother HL-L2340DW series")))
        assertTrue(PrinterAdvertisement.supported("Office", mapOf("product" to "(Brother HL-L2340D series)")))
        assertFalse(PrinterAdvertisement.supported("Brother HL-L2350DW", emptyMap()))
        assertFalse(PrinterAdvertisement.supported("Office printer", mapOf("pdl" to "application/pdf")))
    }
    @Test fun rawServiceAdvertisedPortIsRetained() {
        assertEquals(9102, PrinterAdvertisement.rawEndpoint("192.168.1.41", "_pdl-datastream._tcp.", 9102).port)
    }
    @Test fun ippAndLprPortsAreNeverUsedForBrotherRawData() {
        for ((type, port) in listOf("_ipp._tcp" to 631, "_ipps._tcp." to 443, "_printer._tcp" to 515)) {
            assertEquals(9100, PrinterAdvertisement.rawEndpoint("192.168.1.41", type, port).port)
        }
    }
    @Test fun discoveryCannotRedirectDocumentsOutsideTheLocalNetwork() {
        assertThrows(IllegalArgumentException::class.java) { PrinterAdvertisement.rawEndpoint("8.8.8.8", PrinterAdvertisement.RAW, 9100) }
        assertThrows(IllegalArgumentException::class.java) { PrinterAdvertisement.rawEndpoint("192.168.1.41", PrinterAdvertisement.RAW, 0) }
    }
}
