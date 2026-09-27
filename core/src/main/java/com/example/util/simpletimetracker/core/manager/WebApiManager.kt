package com.example.util.simpletimetracker.core.manager

/**
 * Controls the local Web API server.
 * Only the play flavor has an implementation, the base flavor binds a no-op.
 */
interface WebApiManager {

    val isAvailable: Boolean

    fun start()

    fun stop()
}
