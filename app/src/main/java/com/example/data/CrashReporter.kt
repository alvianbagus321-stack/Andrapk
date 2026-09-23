package com.example.data

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Perekam crash in-app: menangkap SEMUA exception/fatal (termasuk yang terjadi di
 * jendela overlay) dan menyimpannya ke /sdcard/JARVIS/diagnostics/crash_log.txt
 * (tahan uninstall) + mirror internal.
 *
 * Tujuan: di HP dengan ROM pembatas (Tecno/HiOS, Infinix, dll) user bisa membaca
 * penyebab crash TANPA adb — cukup buka Dashboard → salin laporan crash.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val REL = "diagnostics/crash_log.txt"
    private const val MAX_CHARS = 60_000
    private const val MAX_ENTRY_CHARS = 8_000

    @Volatile
    private var cachedSummary: String? = null

    /** Pasang handler crash global — panggil sekali di Application.onCreate. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("FATAL (thread ${thread.name})", throwable)
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {}
        }
        cachedSummary = readLastSummary()
        Log.i(TAG, "CrashReporter terpasang")
    }

    /** Catat exception non-fatal (mis. kegagalan membuat jendela overlay). */
    @Synchronized
    fun log(header: String, throwable: Throwable?) {
        try {
            val sw = StringWriter()
            throwable?.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                appendLine("==== $header @ $stamp ====")
                append(sw.toString().take(MAX_ENTRY_CHARS))
                appendLine()
            }
            val old = PersistentStore.read(REL) ?: ""
            PersistentStore.write(REL, (old + entry).takeLast(MAX_CHARS))
            cachedSummary = "Crash @ $stamp: ${throwable?.javaClass?.simpleName ?: "?"}: ${throwable?.message ?: ""}".take(200)
            Log.e(TAG, entry)
        } catch (_: Throwable) {
            // jangan pernah melempar dari perekam crash
        }
    }

    /** Baris ringkas crash terakhir (untuk Dashboard). */
    fun lastCrashSummary(): String? = cachedSummary ?: readLastSummary()

    /** Isi log penuh (untuk disalin ke developer). */
    fun fullLog(): String = try {
        PersistentStore.read(REL) ?: ""
    } catch (_: Throwable) {
        ""
    }

    @Synchronized
    fun clear() {
        try {
            PersistentStore.delete(REL)
        } catch (_: Throwable) {}
        cachedSummary = null
    }

    private fun readLastSummary(): String? = try {
        val text = PersistentStore.read(REL) ?: return null
        val lastHeader = text.lines().lastOrNull { it.startsWith("==== ") }
        val fatalLine = text.lines().lastOrNull { it.contains("FATAL EXCEPTION") || it.startsWith("FATAL (") }
        fatalLine ?: lastHeader
    } catch (_: Throwable) {
        null
    }
}
