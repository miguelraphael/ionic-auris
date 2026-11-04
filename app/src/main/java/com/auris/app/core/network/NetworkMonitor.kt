package com.auris.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide connectivity state.
 *
 * The app is local-first, so a missing network is a modifier on the UI rather than a failure:
 * Room keeps rendering, and the shell only needs to say why new content is not arriving. That is
 * also why this exposes raw connectivity instead of probing a server — a captive portal counts as
 * connected here, and the request itself reports the real failure.
 *
 * Registered once and never unregistered: it lives for the process, and release would only be
 * meaningful on teardown that never happens in this app.
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(read())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        // A missing ConnectivityManager is not a crash-worthy condition; the default stays
        // "online" so the UI does not claim an outage it cannot verify.
        runCatching {
            manager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _isOnline.value = read()
                    }

                    override fun onLost(network: Network) {
                        _isOnline.value = read()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        _isOnline.value = networkCapabilities
                            .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    }
                },
            )
        }
    }

    private fun read(): Boolean {
        val connectivity = manager ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
