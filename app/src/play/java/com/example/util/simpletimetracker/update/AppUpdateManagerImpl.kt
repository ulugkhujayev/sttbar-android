package com.example.util.simpletimetracker.update

import android.content.Context
import android.content.Intent
import com.example.util.simpletimetracker.core.manager.AppUpdateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AppUpdateManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppUpdateManager {

    override val isAvailable: Boolean = true

    override fun openUpdateScreen() {
        context.startActivity(
            Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
