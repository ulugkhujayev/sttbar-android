package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.core.manager.WebApiManager
import com.example.util.simpletimetracker.core.manager.AppUpdateManager
import com.example.util.simpletimetracker.update.AppUpdateManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface WebApiModule {

    @Binds
    fun bindWebApiManager(impl: WebApiManagerImpl): WebApiManager

    @Binds
    fun bindWebApiRecordsInteractor(impl: WebApiRecordsInteractorImpl): WebApiRecordsInteractor

    @Binds
    fun bindAppUpdateManager(impl: AppUpdateManagerImpl): AppUpdateManager
}
