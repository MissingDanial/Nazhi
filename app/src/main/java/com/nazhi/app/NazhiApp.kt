package com.nazhi.app

import android.app.Application
import com.nazhi.app.core.data.AppContainer

class NazhiApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
