package com.example.util.simpletimetracker.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.util.simpletimetracker.R
import com.example.util.simpletimetracker.core.utils.PendingIntents
import com.example.util.simpletimetracker.domain.prefs.repo.PrefsRepo
import com.example.util.simpletimetracker.navigation.Router
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Keeps the Web API server alive as a foreground service, so it keeps answering
 * when the app is in the background.
 */
@AndroidEntryPoint
class WebApiService : Service() {

    @Inject
    lateinit var webApiAdapter: WebApiAdapter

    @Inject
    lateinit var prefsRepo: PrefsRepo

    @Inject
    lateinit var webApiNsdAdvertiser: WebApiNsdAdvertiser

    @Inject
    lateinit var router: Router

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefsRepo.webApiEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        acquireWifiLock()
        ensureServerStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        startJob?.cancel()
        serviceScope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        webApiNsdAdvertiser.unregister()
        // Closing sockets counts as network on the main thread for StrictMode.
        thread(name = "WebApiStop") { webApiAdapter.stopWebApi() }
        removeForegroundNotification()
        Timber.d("Web API stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Wi-Fi power save drops inbound TCP when the screen is off, which makes the API
     * unreachable exactly when another device polls it.
     * FULL_HIGH_PERF is the only mode that works with the screen off (API 29 to 33).
     * From API 34 the system maps it to FULL_LOW_LATENCY, which is screen-on only.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            WIFI_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun ensureServerStarted() {
        if (webApiAdapter.isAlive || startJob?.isActive == true) return

        startJob = serviceScope.launch {
            repeat(WEB_API_START_ATTEMPTS) { attempt ->
                try {
                    webApiAdapter.startWebApi()
                    webApiNsdAdvertiser.register()
                    Timber.d("Web API started on port ${WebApiAdapter.PORT}")
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    webApiAdapter.stopWebApi()
                    if (attempt == WEB_API_START_ATTEMPTS - 1) {
                        Timber.e(e, "Failed to start Web API after $WEB_API_START_ATTEMPTS attempts")
                        stopSelf()
                    } else {
                        Timber.w(e, "Web API start attempt ${attempt + 1} failed, retrying")
                        delay(WEB_API_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val startIntent = router.getMainStartIntent().apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            startIntent,
            PendingIntents.getFlags(),
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.web_api_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.web_api_notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 8080
        private const val NOTIFICATION_CHANNEL_ID = "WEB_API"
        private const val WIFI_LOCK_TAG = "simpletimetracker:webApi"
        private const val WEB_API_START_ATTEMPTS = 3
        private const val WEB_API_RETRY_DELAY_MS = 1_000L

        fun getStartIntent(context: Context): Intent {
            return Intent(context, WebApiService::class.java)
        }
    }
}
