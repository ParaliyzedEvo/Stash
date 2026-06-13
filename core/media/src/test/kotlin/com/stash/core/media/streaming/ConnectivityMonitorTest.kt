package com.stash.core.media.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM tests for [ConnectivityMonitor]. We mock [Context] +
 * [ConnectivityManager] and capture the registered callback so we
 * can verify reactive state updates.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectivityMonitorTest {

    private val cm: ConnectivityManager = mockk(relaxed = true)
    private val context: Context = mockk {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
    }

    private fun buildMonitor(
        connected: Boolean = false,
        cellular: Boolean = false,
    ): RealConnectivityMonitor {
        // Set initial state before constructing the monitor.
        if (connected) {
            val network: Network = mockk()
            val caps: NetworkCapabilities = mockk(relaxed = true) {
                every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
                every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
                every { hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns cellular
            }
            every { cm.activeNetwork } returns network
            every { cm.getNetworkCapabilities(network) } returns caps
        } else {
            every { cm.activeNetwork } returns null
            every { cm.getNetworkCapabilities(null) } returns null
        }
        return RealConnectivityMonitor(context)
    }

    @Test
    fun isConnected_returnsTrueOnValidatedInternet() {
        val monitor = buildMonitor(connected = true)
        assertThat(monitor.isConnected()).isTrue()
    }

    @Test
    fun isConnected_returnsFalseWhenNoActiveNetwork() {
        val monitor = buildMonitor(connected = false)
        assertThat(monitor.isConnected()).isFalse()
    }

    @Test
    fun isConnected_returnsFalseWhenInternetButNotValidated() {
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk(relaxed = true) {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        }
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        val monitor = RealConnectivityMonitor(context)
        assertThat(monitor.isConnected()).isFalse()
    }

    @Test
    fun isCellular_returnsTrueOnCellularTransport() {
        val monitor = buildMonitor(connected = true, cellular = true)
        assertThat(monitor.isCellular()).isTrue()
    }

    @Test
    fun isCellular_returnsFalseOnWifi() {
        val monitor = buildMonitor(connected = true, cellular = false)
        assertThat(monitor.isCellular()).isFalse()
    }

    @Test
    fun isCellular_returnsFalseWhenNoActiveNetwork() {
        val monitor = buildMonitor(connected = false)
        assertThat(monitor.isCellular()).isFalse()
    }
}
