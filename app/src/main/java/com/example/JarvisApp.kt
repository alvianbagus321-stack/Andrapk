package com.example

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.example.data.CompanionRepository
import com.example.service.JarvisCompanionService

class JarvisApp : Application() {

    companion object {
        lateinit var instance: JarvisApp
            private set

        val repository: CompanionRepository
            get() = instance._repository

        private const val START_SERVICE_DELAY_MS = 1500L
    }

    private lateinit var _repository: CompanionRepository

    // Handler utama untuk menunda pekerjaan non-kritis setelah frame pertama ter-render,
    // agar startup tetap mulus 60fps dan tidak "frozen" saat aplikasi dibuka.
    private val startupHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Perekam crash global — agar penyebab crash overlay bisa dibaca user
        // langsung dari Dashboard tanpa adb (penting utk ROM pembatas spt Tecno/HiOS).
        com.example.data.CrashReporter.install()

        // Ringan & dibutuhkan UI segera — tetap sinkron.
        com.example.data.AiConfigManager.init(this)
        _repository = CompanionRepository(this)

        // Init tool registry: prefs ringan disinkron, parsing JSON custom tools
        // dipindah ke background thread agar tidak memblokir main thread.
        com.example.service.ToolManager.init(this)

        // Hotword manager: hanya membaca prefs sinkron; SpeechRecognizer (berat)
        // baru dinyalakan terjadwal belakangan — lihat init() di managernya.

        // Jangan start Foreground Service + HTTP Server di tengah startup render.
        // Tunda sampai UI selesai menampilkan frame pertama.
        startupHandler.postDelayed({
            try {
                JarvisCompanionService.start(this)
            } catch (_: Exception) {
            }
        }, START_SERVICE_DELAY_MS)
    }
}
