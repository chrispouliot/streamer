package dev.streamer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.streamer.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/** Whether the server can be used right now, and if not, why. */
enum class Connection {
    Online,

    /** The phone has no network (e.g. airplane mode). */
    NoNetwork,

    /** There is a network, but the server isn't answering (e.g. Tailscale/VPN off). */
    ServerUnreachable,

    /** The user turned on offline mode. */
    OfflineMode,
}

/**
 * Network-related preferences and the current [connection], readable
 * synchronously from any thread. The system network state is a hint; server
 * reachability comes from actual requests (reported via [reportServerReachable]).
 */
class NetworkPolicy(context: Context, settings: SettingsRepository, scope: CoroutineScope) {
    val offlineOnly: StateFlow<Boolean> = settings.offlineOnly.stateIn(scope, SharingStarted.Eagerly, false)
    val wifiOnlyDownloads: StateFlow<Boolean> = settings.wifiOnlyDownloads.stateIn(scope, SharingStarted.Eagerly, true)

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Physical networks (Wi-Fi, mobile, Ethernet) with internet. VPNs are
     * excluded: a VPN such as Tailscale stays "connected" in airplane mode even
     * though nothing underneath it can carry traffic.
     */
    private val physicalNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val _networkAvailable = MutableStateFlow(initialNetworkAvailable())

    /** True while a physical network with internet exists. */
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    private val serverReachable = MutableStateFlow(true)

    val connection: StateFlow<Connection> = combine(offlineOnly, _networkAvailable, serverReachable, ::connectionOf)
        // Start from the current state, so the first frame is already right (e.g. in airplane mode).
        .stateIn(scope, SharingStarted.Eagerly, connectionOf(offlineOnly.value, _networkAvailable.value, serverReachable.value))

    private fun connectionOf(offline: Boolean, network: Boolean, server: Boolean) = when {
        offline -> Connection.OfflineMode
        !network -> Connection.NoNetwork
        !server -> Connection.ServerUnreachable
        else -> Connection.Online
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    physicalNetworks += network
                    update()
                }

                override fun onLost(network: Network) {
                    physicalNetworks -= network
                    update()
                }
            },
        )
    }

    private fun update() {
        val available = physicalNetworks.isNotEmpty()
        // A new network may reach the server again; a probe or request will confirm.
        if (available && !_networkAvailable.value) serverReachable.value = true
        _networkAvailable.value = available
    }

    @Suppress("DEPRECATION") // allNetworks: a synchronous snapshot before the callback reports.
    private fun initialNetworkAvailable(): Boolean = connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }

    fun reportServerReachable(reachable: Boolean) {
        serverReachable.value = reachable
    }
}
