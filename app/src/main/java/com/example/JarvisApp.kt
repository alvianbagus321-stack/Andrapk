package com.example

import android.app.Application
import com.example.data.CompanionRepository
import com.example.service.JarvisCompanionService

class JarvisApp : Application() {

    companion object {
        lateinit var instance: JarvisApp
            private set

        val repository: CompanionRepository
            get() = instance._repository
    }

    private lateinit var _repository: CompanionRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.data.AiConfigManager.init(this)
        _repository = CompanionRepository(this)
        com.example.service.ToolManager.init(this)
        com.example.service.JarvisHotwordManager.init(this)

        // Automatically start the companion background service & HTTP server
        JarvisCompanionService.start(this)
    }
}
