package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Whether this phone currently has a Tailscale address, as far as the app can see. */
sealed interface TailscaleStatus {
    /** A VPN on this phone holds [address], which is in a Tailscale range. */
    data class Active(val address: String) : TailscaleStatus

    /** No VPN that applies to this app holds a Tailscale address. */
    data object NotDetected : TailscaleStatus
}

private const val IPV4_FIRST_OCTET = 100
private const val IPV4_SECOND_OCTET_MASK = 0xC0
private const val IPV4_SECOND_OCTET_CGNAT = 0x40
private const val BYTE_MASK = 0xFF

/** Tailscale's IPv6 ULA prefix, `fd7a:115c:a1e0::/48`. */
private val TAILSCALE_IPV6_PREFIX = listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)

/**
 * How long to wait for the system to report existing VPNs before concluding there is none.
 * A network callback never fires when no network matches, so "not detected" needs a timeout.
 */
private const val INITIAL_SETTLE_MS = 400L

/** True for the CGNAT range `100.64.0.0/10` that Tailscale assigns IPv4 addresses from. */
private fun Inet4Address.isTailscaleRange(): Boolean {
    val bytes = address
    val first = bytes[0].toInt() and BYTE_MASK
    val second = bytes[1].toInt() and BYTE_MASK
    return first == IPV4_FIRST_OCTET && (second and IPV4_SECOND_OCTET_MASK) == IPV4_SECOND_OCTET_CGNAT
}

private fun Inet6Address.isTailscaleRange(): Boolean {
    val bytes = address
    return TAILSCALE_IPV6_PREFIX.indices.all { (bytes[it].toInt() and BYTE_MASK) == TAILSCALE_IPV6_PREFIX[it] }
}

/**
 * Picks the Tailscale address out of a VPN interface's [addresses], preferring IPv4 because that
 * is what the VM daemon binds to. Returns null when none of them is in a Tailscale range.
 *
 * Only pass addresses of VPN interfaces: mobile carriers hand out the same `100.64.0.0/10` range.
 */
fun findTailscaleAddress(addresses: Iterable<InetAddress>): String? {
    val v4 = addresses.firstOrNull { it is Inet4Address && it.isTailscaleRange() }
    val match = v4 ?: addresses.firstOrNull { it is Inet6Address && it.isTailscaleRange() }
    return match?.hostAddress
}

/**
 * Emits this phone's [TailscaleStatus] and keeps it current as VPNs connect and disconnect.
 * Needs only `ACCESS_NETWORK_STATE`.
 */
fun tailscaleStatusFlow(context: Context): Flow<TailscaleStatus> = callbackFlow {
    val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    if (connectivity == null) {
        trySend(TailscaleStatus.NotDetected)
        awaitClose()
        return@callbackFlow
    }
    val vpnAddresses = ConcurrentHashMap<Network, List<InetAddress>>()

    fun publish() {
        val address = vpnAddresses.values.firstNotNullOfOrNull { findTailscaleAddress(it) }
        trySend(if (address != null) TailscaleStatus.Active(address) else TailscaleStatus.NotDetected)
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            vpnAddresses[network] = linkProperties.linkAddresses.map { it.address }
            publish()
        }

        override fun onLost(network: Network) {
            vpnAddresses.remove(network)
            publish()
        }
    }
    // Default requests exclude VPNs, so ask for the VPN transport explicitly.
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    connectivity.registerNetworkCallback(request, callback)
    launch {
        delay(INITIAL_SETTLE_MS)
        publish()
    }
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
