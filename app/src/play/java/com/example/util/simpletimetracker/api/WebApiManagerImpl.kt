package com.example.util.simpletimetracker.api

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.util.simpletimetracker.core.manager.WebApiManager
import com.example.util.simpletimetracker.domain.prefs.repo.PrefsRepo
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

class WebApiManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepo: PrefsRepo,
) : WebApiManager {

    override val isAvailable: Boolean = true

    override fun start() {
        if (!prefsRepo.webApiEnabled) return

        try {
            ContextCompat.startForegroundService(context, WebApiService.getStartIntent(context))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException when called from the background.
            Timber.e(e, "Android rejected the Web API foreground service start")
        }
    }

    override fun stop() {
        context.stopService(WebApiService.getStartIntent(context))
    }
}
