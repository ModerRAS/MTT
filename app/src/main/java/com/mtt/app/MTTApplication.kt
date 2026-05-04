package com.mtt.app

import android.app.Application
import com.mtt.app.core.logger.AppLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MTTApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
