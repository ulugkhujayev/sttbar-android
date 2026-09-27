package com.example.util.simpletimetracker.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.util.simpletimetracker.core.manager.WebApiManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restarts the Web API after a reboot when the user has it enabled.
 * Starting a foreground service is allowed from this broadcast.
 */
@AndroidEntryPoint
class WebApiBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var webApiManager: WebApiManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> webApiManager.start()
        }
    }
}
