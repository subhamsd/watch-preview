package com.app.hackaton2026.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdAdvertiser(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    companion object {
        private const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_wristview._tcp"
        const val SERVICE_NAME = "WristView-Camera"
    }

    fun startAdvertising(port: Int) {
        if (isRegistered) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName} on port ${info.port}")
                isRegistered = true
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: error $errorCode")
                isRegistered = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
                isRegistered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: error $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD service", e)
        }
    }

    fun stopAdvertising() {
        if (!isRegistered || registrationListener == null) return
        try {
            nsdManager.unregisterService(registrationListener)
            isRegistered = false
            Log.i(TAG, "NSD Advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        }
    }
}
