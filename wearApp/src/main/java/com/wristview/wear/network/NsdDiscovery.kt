package com.wristview.wear.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

import android.net.wifi.WifiManager

class NsdDiscovery(
    private val context: Context,
    private val onDeviceFound: (host: String, port: Int) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_wristview._tcp"
    }

    fun startDiscovery() {
        if (isDiscovering) return

        try {
            multicastLock = wifiManager?.createMulticastLock("wristview_multicast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Service discovery started for: $regType")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${service.serviceName}, type: ${service.serviceType}")
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: error $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: error $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${service.serviceName}: error $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress
                val port = resolved.port
                Log.i(TAG, "Resolved service: $host:$port")
                if (host != null && port > 0) {
                    onDeviceFound(host, port)
                }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering || discoveryListener == null) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            isDiscovering = false
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            multicastLock = null
            Log.i(TAG, "Discovery stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }
}
