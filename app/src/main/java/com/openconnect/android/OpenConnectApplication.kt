package com.openconnect.android

import android.app.Application

class OpenConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.applyStored(this)
    }
}
