package com.agmente.android

import android.app.Application

class AgmenteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.applyStored(this)
    }
}
