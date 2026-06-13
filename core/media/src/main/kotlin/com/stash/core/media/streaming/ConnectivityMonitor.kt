package com.stash.core.media.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface ConnectivityMonitor {
    val isConnectedFlow: StateFlow<Boolean>
    val isCellularFlow: StateFlow<Boolean>
    fun isConnectedSnapshot(): Boolean
    fun isCellularSnapshot(): Boolean
    fun isConnected(): Boolean
    fun isCellular(): Boolean
}

@Singleton
class RealConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityMonitor {
    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkConnected())
    private val _isCellular = MutableStateFlow(checkCellular())

    /** Reactive connectivity state. */
    override val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Reactive cellular state. */
    override val isCellularFlow: StateFlow<Boolean> = _isCellular.asStateFlow()

    /** Synchronous snapshot for legacy call-sites. */
    override fun isConnectedSnapshot(): Boolean = _isConnected.value

    /** Synchronous snapshot for legacy call-sites. */
    override fun isCellularSnapshot(): Boolean = _isCellular.value

    // Compat shims — keep the old method signatures working so we don't
    // have to update every callsite in this commit.
    override fun isConnected(): Boolean = _isConnected.value
    override fun isCellular(): Boolean = _isCellular.value

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update()
            }

            override fun onLost(network: Network) {
                update()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                update()
            }
        })
    }

    private fun update() {
        val connected = checkConnected()
        val cellular = checkCellular()
        if (_isConnected.value != connected) {
            Log.d(TAG, "connectivity changed: connected=$connected")
        }
        _isConnected.value = connected
        _isCellular.value = cellular
    }

    private fun checkConnected(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun checkCellular(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private companion object {
        const val TAG = "ConnectivityMonitor"
    }
}
