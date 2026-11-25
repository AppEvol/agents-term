package com.example

import com.example.data.findTailscaleAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class TailscaleStatusTest {
    private fun addresses(vararg literals: String): List<InetAddress> = literals.map { InetAddress.getByName(it) }

    @Test
    fun findsIpv4InTailscaleRange() {
        assertEquals("100.71.219.26", findTailscaleAddress(addresses("100.71.219.26")))
        assertEquals("100.64.0.1", findTailscaleAddress(addresses("100.64.0.1")))
        assertEquals("100.127.255.254", findTailscaleAddress(addresses("100.127.255.254")))
    }

    @Test
    fun prefersIpv4OverIpv6() {
        val found = findTailscaleAddress(addresses("fd7a:115c:a1e0::1234", "100.71.219.26"))
        assertEquals("100.71.219.26", found)
    }

    @Test
    fun fallsBackToTailscaleIpv6() {
        val expected = InetAddress.getByName("fd7a:115c:a1e0::1234").hostAddress
        assertEquals(expected, findTailscaleAddress(addresses("10.0.0.2", "fd7a:115c:a1e0::1234")))
    }

    @Test
    fun ignoresAddressesOutsideTailscaleRanges() {
        assertNull(findTailscaleAddress(emptyList()))
        // Just outside 100.64.0.0/10 on both sides, plus ordinary private and other-VPN addresses.
        assertNull(findTailscaleAddress(addresses("100.63.255.255", "100.128.0.1", "10.8.0.2", "192.168.1.5")))
        assertNull(findTailscaleAddress(addresses("fd7a:115c:a1e1::1", "fe80::1", "2001:db8::1")))
    }
}
