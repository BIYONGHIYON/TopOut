package com.topout.cam

import android.app.Application
import com.topout.cam.localization.AppLanguageManager
import com.topout.cam.localization.TopOutStrings

class TopOutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TopOutStrings.initialize(this)
        AppLanguageManager.restoreManualSelection(this)
    }
}
