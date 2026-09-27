package com.example.util.simpletimetracker.core.manager

interface AppUpdateManager {

    val isAvailable: Boolean

    fun openUpdateScreen()
}
