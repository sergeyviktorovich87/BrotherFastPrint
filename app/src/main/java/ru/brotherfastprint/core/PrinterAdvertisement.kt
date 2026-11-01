package ru.brotherfastprint.core

/** DNS-SD ports describe their own protocol. IPP 631 and LPR 515 are never treated as RAW. */
object PrinterAdvertisement {
    const val RAW = "_pdl-datastream._tcp"
    val serviceTypes = listOf(RAW, "_ipp._tcp", "_ipps._tcp", "_printer._tcp")
    fun supported(name: String, attributes: Map<String, String>): Boolean {
        val text = (listOf(name) + listOf("ty", "product", "usb_MDL", "note").mapNotNull(attributes::get))
            .joinToString(" ").lowercase().filter { it.isLetterOrDigit() }
        return text.contains("hll2340d")
    }
    fun rawEndpoint(host: String, type: String, advertisedPort: Int): Endpoint {
        val port = if (type.trimEnd('.') == RAW) advertisedPort else 9100
        return Endpoint.parse(host, port.toString())
    }
}
