package ru.brotherfastprint.core

import org.junit.Assert.*
import org.junit.Test

class LanRoutingTest {
    @Test fun vpnUsesItsLanExclusionsBeforeAnExplicitPhysicalBind() {
        assertEquals(listOf(LanRouteKind.SYSTEM_DEFAULT, LanRouteKind.PHYSICAL), LanRouting.order(true, true, true))
    }
    @Test fun noVpnKeepsTheWorkingPhysicalWifiRoute() {
        assertEquals(listOf(LanRouteKind.PHYSICAL), LanRouting.order(false, true, false))
    }
    @Test fun vpnCanUseWifiEvenIfUnderlyingNetworkIsNotExposed() {
        assertEquals(listOf(LanRouteKind.SYSTEM_DEFAULT), LanRouting.order(true, false, true))
    }
    @Test fun cellularOnlyDoesNotBecomeALanFallback() {
        assertTrue(LanRouting.order(false, false, false).isEmpty())
        assertTrue(LanRouting.order(true, false, false).isEmpty())
    }
    @Test fun matchingPhysicalSubnetHandlesNonOctetPrefixesAndIpv6() {
        fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
        val printer = ip(192, 168, 1, 41)
        assertTrue(LanRouting.sameIpv4Subnet(printer, ip(192, 168, 1, 46), 24))
        assertFalse(LanRouting.sameIpv4Subnet(printer, ip(192, 168, 2, 46), 24))
        assertTrue(LanRouting.sameIpv4Subnet(printer, ip(192, 168, 1, 46), 29))
        assertFalse(LanRouting.sameIpv4Subnet(printer, ip(192, 168, 1, 48), 29))
        assertTrue(LanRouting.sameIpv4Subnet(printer, printer, 32))
        assertFalse(LanRouting.sameIpv4Subnet(printer, ip(192, 168, 1, 42), 32))
        assertFalse(LanRouting.sameIpv4Subnet(printer, ByteArray(16), 24))
    }
}
