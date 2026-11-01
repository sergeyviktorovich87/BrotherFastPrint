package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun privateAddressesHaveNoDnsDependency() {
        listOf("192.168.1.41", "10.2.0.4", "172.16.0.1", "172.31.255.1", "169.254.2.2").forEach {
            assertEquals(it, Endpoint.parse(it, "9100").address.hostAddress)
        }
    }
    @Test fun rejectsPublicLoopbackMulticastHostnamesAndInvalidPorts() {
        listOf("8.8.8.8", "127.0.0.1", "0.0.0.0", "172.32.0.1", "224.0.0.1", "printer.local", "192.168.1.999", "192.168.1.1/24", "192.168.1.-1").forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(it, "9100") }
        }
        listOf("0", "65536", "bad").forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("192.168.1.41", it) }
        }
    }
}
