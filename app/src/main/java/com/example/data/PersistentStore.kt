package com.example.data

import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.JarvisApp
import java.io.File

/**
 * Penyimpanan yang TAHAN UNINSTALL.
 *
 * Data penting (chat sessions, memori AI) ditulis ke folder publik /sdcard/JARVIS/
 * yang TIDAK ikut terhapus saat aplikasi di-uninstall, plus cermin (mirror) di
 * storage internal agar fitur tetap jalan walau izin "Akses semua file" belum diberikan.
 *
 * Urutan baca: external (/sdcard/JARVIS) dulu → fallback internal.
 * Syarat external: izin MANAGE_EXTERNAL_STORAGE (All-Files-Access) sudah di-grant —
 * app sudah punya flow permintaannya (AdbShizukuManager.requestAllFilesAccess).
 */
object PersistentStore {
    private const val TAG = "PersistentStore"
    private const val ROOT_DIR_NAME = "JARVIS"
    private const val INTERNAL_SUBDIR = "persistent"

    private fun internalRoot(): File = File(JarvisApp.instance.filesDir, INTERNAL_SUBDIR)

    private fun externalRoot(): File? = try {
        val sd = Environment.getExternalStorageDirectory() ?: return null
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            sd.canWrite()
        }
        if (!granted) {
            null
        } else {
            val dir = File(sd, ROOT_DIR_NAME).apply { if (!exists()) mkdirs() }
            dir.takeIf { it.isDirectory && it.canWrite() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "externalRoot tidak tersedia: ${e.message}")
        null
    }

    /** true bila /sdcard/JARVIS aktif dipakai (data tahan uninstall). */
    fun isExternalActive(): Boolean = externalRoot() != null

    /** Path folder external untuk ditampilkan di UI. */
    fun externalPath(): String = externalRoot()?.absolutePath
        ?: "/sdcard/$ROOT_DIR_NAME (izin storage belum diberikan — data hanya tersimpan internal)"

    private fun clean(relPath: String): String = relPath.trim('/').replace("..", "")

    /** Baca file: external dulu, lalu internal. null bila tidak ada. */
    fun read(relPath: String): String? {
        val rel = clean(relPath)
        try {
            externalRoot()?.let { ext ->
                val f = File(ext, rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "read external gagal: ${e.message}")
        }
        return try {
            val f = File(internalRoot(), rel)
            if (f.isFile) f.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            Log.w(TAG, "read internal gagal: ${e.message}")
            null
        }
    }

    /** Tulis file ke internal SELALU + external bila tersedia. true bila ada yang berhasil. */
    fun write(relPath: String, content: String): Boolean {
        val rel = clean(relPath)
        var ok = false
        try {
            val f = File(internalRoot(), rel)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "write internal gagal: ${e.message}")
        }
        try {
            externalRoot()?.let { ext ->
                val f = File(ext, rel)
                f.parentFile?.mkdirs()
                f.writeText(content, Charsets.UTF_8)
                ok = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "write external gagal: ${e.message}")
        }
        return ok
    }

    /** Hapus dari internal + external. */
    fun delete(relPath: String) {
        val rel = clean(relPath)
        try { File(internalRoot(), rel).delete() } catch (_: Exception) {}
        try { externalRoot()?.let { File(it, rel).delete() } } catch (_: Exception) {}
    }

    /** Migrasi satu arah: file internal lama (format pra-PersistentStore) → PersistentStore. */
    fun migrateLegacyFile(legacyFile: File, relPath: String) {
        try {
            if (!legacyFile.isFile) return
            if (read(relPath) != null) return // sudah ada data baru — jangan timpa
            write(relPath, legacyFile.readText(Charsets.UTF_8))
            Log.i(TAG, "Migrasi ${legacyFile.name} → $relPath berhasil")
        } catch (e: Exception) {
            Log.w(TAG, "Migrasi ${legacyFile.name} gagal: ${e.message}")
        }
    }
}
