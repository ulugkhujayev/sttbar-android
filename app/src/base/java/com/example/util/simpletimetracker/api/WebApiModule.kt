package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.core.manager.WebApiManager
import com.example.util.simpletimetracker.core.manager.AppUpdateManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
interface WebApiModule {

    @Binds
    fun bindWebApiManager(impl: NoOpWebApiManager): WebApiManager

    @Binds
    fun bindAppUpdateManager(impl: NoOpAppUpdateManager): AppUpdateManager
}

class NoOpWebApiManager @Inject constructor() : WebApiManager {

    override val isAvailable: Boolean = false

    override fun start() = Unit

    override fun stop() = Unit
}

class NoOpAppUpdateManager @Inject constructor() : AppUpdateManager {

    override val isAvailable: Boolean = false

    override fun openUpdateScreen() = Unit
}
