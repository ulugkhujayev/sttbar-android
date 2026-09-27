package com.example.util.simpletimetracker.api

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advertises the Web API over mDNS (DNS-SD) so that clients on the same network
 * can find the phone without typing its address.
 */
@Singleton
class WebApiNsdAdvertiser @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registrationRequested = false
    private var retryAttempted = false
    private var retryScheduled = false

    private val retryRunnable = Runnable {
        synchronized(this) {
            retryScheduled = false
            if (registrationRequested && registrationListener == null) {
                registerService()
            }
        }
    }

    @Synchronized
    fun register() {
        if (registrationRequested || registrationListener != null || retryScheduled) return

        registrationRequested = true
        retryAttempted = false
        registerService()
    }

    @Synchronized
    fun unregister() {
        registrationRequested = false
        retryAttempted = false
        if (retryScheduled) {
            retryScheduled = false
            handler.removeCallbacks(retryRunnable)
        }

        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "mDNS service was already unregistered")
        }
    }

    @Synchronized
    private fun registerService() {
        if (!registrationRequested || registrationListener != null) return

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Timber.d("mDNS service registered as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@WebApiNsdAdvertiser) {
                    if (registrationListener !== this) return

                    registrationListener = null
                    Timber.w("mDNS service registration failed, error code $errorCode")
                    scheduleRetry()
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.w("mDNS service unregistration failed, error code $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Timber.d("mDNS service unregistered")
            }
        }
        registrationListener = listener

        try {
            nsdManager.registerService(
                createServiceInfo(),
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        } catch (e: RuntimeException) {
            if (registrationListener === listener) {
                registrationListener = null
            }
            Timber.e(e, "Failed to request mDNS service registration")
            scheduleRetry()
        }
    }

    @Synchronized
    private fun scheduleRetry() {
        if (!registrationRequested || retryAttempted) {
            registrationRequested = false
            return
        }

        retryAttempted = true
        retryScheduled = true
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun createServiceInfo(): NsdServiceInfo {
        return NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = WebApiAdapter.PORT
        }
    }

    companion object {
        // The system appends a suffix when several phones advertise the same name.
        private const val SERVICE_NAME = "STT"
        private const val SERVICE_TYPE = "_stt-webapi._tcp."
        private const val RETRY_DELAY_MS = 1_000L
    }
}
