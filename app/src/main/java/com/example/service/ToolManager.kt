package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.JarvisApp
import com.example.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all built-in and user/AI-defined tools in JARVIS-HP.
 * Executes all tools on the actual Android device (Shell, Termux, Shizuku, Intent, Accessibility, HTTP, System Logic).
 */
object ToolManager {

    private const val TAG = "ToolManager"
    private const val PREFS_NAME = "jarvis_custom_tools_prefs"
    private const val KEY_TOOLS_JSON = "custom_tools_list"

    private var sharedPreferences: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Built-in tools definition
    val builtInTools: List<CustomTool> = listOf(
        CustomTool(
            id = "open_app",
            name = "Buka Aplikasi",
            description = "Membuka aplikasi target berdasarkan package name atau nama umum (contoh: youtube, chrome, settings, whatsapp)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "open_app",
            parametersSchema = """{"package_name": "com.google.android.youtube"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "type_text",
            name = "Ketik Teks",
            description = "Mengetikkan teks secara otomatis pada input field yang sedang aktif atau target element ID",
            category = "Input & Form",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "type_text",
            parametersSchema = """{"text": "Halo Dunia", "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "tap",
            name = "Tap Layar",
            description = "Melakukan klik/tap pada koordinat spesifik (x, y) atau identifier teks/ID elemen",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "tap",
            parametersSchema = """{"x": 500, "y": 800, "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "swipe",
            name = "Swipe / Scroll",
            description = "Mengusap layar dari koordinat awal (x1, y1) ke koordinat akhir (x2, y2)",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "swipe",
            parametersSchema = """{"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration_ms": 300}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "press_key",
            name = "Tekan Tombol Sistem",
            description = "Menekan tombol navigasi sistem seperti BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "press_key",
            parametersSchema = """{"keycode": "BACK"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "read_screen",
            name = "Baca Layar (UI Hierarchy)",
            description = "Membaca semua elemen UI yang tampil di layar beserta teks, id, dan posisinya",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "read_screen",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screenshot",
            name = "Screenshot Layar",
            description = "Menangkap tampilan layar perangkat (PNG / Base64)",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "screenshot",
            parametersSchema = """{"format": "base64"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "ocr_screenshot",
            name = "OCR Screenshot (Baca Teks Layar)",
            description = "Satu langkah: tangkap layar lalu langsung membaca SEMUA teksnya (OCR on-device). Cara termudah membaca soal/chat/pesan yang tampil di layar — termasuk konten TANPA elemen UI (remote desktop, game, WebView, video). Gunakan ini alih-alih screenshot+decode_image bila yang dibutuhkan hanya teksnya",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "ocr_screenshot",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "battery",
            name = "Info Baterai",
            description = "Memeriksa persentase baterai, voltase, status pengisian, dan temperatur",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "battery",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "shell",
            name = "Shell Command",
            description = "Mengeksekusi perintah shell Android via Shizuku / Termux / Linux Runtime",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "pm list packages -3"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_command",
            name = "Termux Terminal Command",
            description = "Mengeksekusi perintah terminal / shell langsung (contoh: ls, df -h, cat, top, dumpsys, python script)",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "df -h"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_storage",
            name = "Info Storage HP",
            description = "Mendapatkan informasi kapasitas penyimpanan internal HP (Total, Digunakan, dan Sisa dalam GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_storage",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "cek_ram",
            name = "Info RAM HP",
            description = "Mengecek penggunaan memori RAM perangkat (Total, Tersedia, dan Terpakai dalam MB & GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "cek_ram",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_read",
            name = "Baca Clipboard",
            description = "Membaca teks yang sedang disalin di clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_read",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_write",
            name = "Tulis Clipboard",
            description = "Menyalin teks baru ke clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_write",
            parametersSchema = """{"text": "Teks yang ingin disalin"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "flashlight_toggle",
            name = "Senter / Flashlight",
            description = "Menyalakan atau mematikan lampu senter kamera HP",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "flashlight_toggle",
            parametersSchema = """{"enable": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "send_notification",
            name = "Kirim Notifikasi",
            description = "Mengirimkan banner notifikasi lokal ke status bar Android",
            category = "Sistem & Notifikasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "send_notification",
            parametersSchema = """{"title": "Pesan JARVIS", "message": "Isi notifikasi"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "http_request",
            name = "HTTP API Request",
            description = "Melakukan request HTTP GET / POST ke web API eksternal dan mengambil responnya",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{"url": "https://api.ipify.org?format=json", "method": "GET"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_service",
            name = "Termux Service Control",
            description = "Mengontrol background service di Termux & daemon JARVIS (action: status, start, stop, restart, run_agent, list; service: nama_service)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_service",
            parametersSchema = """{"action": "status", "service": "jarvis_agent"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_api",
            name = "Termux API Runner",
            description = "Menjalankan perintah Termux:API (command: battery, wifi, tts, vibrate, torch, notification, toast, location, volume, clipboard-get, clipboard-set, sensor, sms; args: opsi)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_api",
            parametersSchema = """{"command": "battery", "args": ""}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_pkg",
            name = "Termux Package Manager",
            description = "Menginstal, mengupdate, atau mencari package di Termux (action: install, update, list, search; package: nama_package)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_pkg",
            parametersSchema = """{"action": "list", "package": "python"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_python",
            name = "Termux Python Runner",
            description = "Mengeksekusi kode atau script Python 3 secara langsung dalam runtime Termux Linux",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_python",
            parametersSchema = """{"code": "import platform, sys; print('Python', sys.version)"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_file",
            name = "Termux File Operations",
            description = "Membaca, menulis, mendaftar, atau menghapus file di direktori Termux / Storage (action: read, write, list, delete, mkdir; path: direktori/file; content: teks)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_file",
            parametersSchema = """{"action": "list", "path": "."}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_tool",
            name = "Buat Tool Baru (AI Dynamic)",
            description = "Membuat dan mendaftarkan tool otomatis baru ke dalam sistem",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_tool",
            parametersSchema = """{"name": "Nama Tool", "description": "Deskripsi", "script_type": "shell", "command": "perintah", "parameters_schema": "{}"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_python_tool",
            name = "Buat Tool Python Termux (@tool)",
            description = "Membuat modul custom tool Python baru dengan decorator @tool untuk disimpan di tools/custom/",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_python_tool",
            parametersSchema = """{"filename": "nama_tool.py", "tool_name": "nama_tool", "description": "Deskripsi tool", "code": "kode python lengkap"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "save_memory",
            name = "Simpan Memori (.md)",
            description = "Menyimpan informasi penting, preferensi user, atau catatan permanen ke bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "save_memory",
            parametersSchema = """{"category": "Preferensi User", "content": "Rincian informasi yang perlu diingat"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "recall_memory",
            name = "Akses Memori (.md)",
            description = "Membaca dan mencari memori tersimpan dari bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "recall_memory",
            parametersSchema = """{"query": "kata kunci pencarian"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "summarize_memory",
            name = "Arsip & Padatkan Memori (.md)",
            description = "Memadatkan dan mengarsipkan catatan memori lama jika ukuran file memori melebihi batas untuk menjaga kecepatan & efisiensi token AI",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "summarize_memory",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "open_file",
            name = "Buka Berkas",
            description = "Membuka berkas atau dokumen di perangkat via Intent Android",
            category = "Sistem & File",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "open_file",
            parametersSchema = """{"path": "/sdcard/Download/dokumen.pdf", "mime_type": "*/*"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_current_app",
            name = "Dapatkan Aplikasi Aktif",
            description = "Mengetahui aplikasi yang sedang terbuka di layar depan (Package Name & Activity)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_current_app",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_device_resolution",
            name = "Cek Resolusi & Layar Perangkat",
            description = "Mendapatkan dimensi resolusi layar (Width x Height px), kepadatan piksel (DPI), dan orientasi layar agar koordinat gesture/klik akurat 100%",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_device_resolution",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "list_apps",
            name = "Daftar Aplikasi Terinstal",
            description = "Mengambil daftar aplikasi terinstal pada perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "list_apps",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "kill_app",
            name = "Hentikan Paket Aplikasi",
            description = "Menghentikan paksa aplikasi latar belakang via am force-stop",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "kill_app",
            parametersSchema = """{"package_name": "com.example.app"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "decode_image",
            name = "Dekode Gambar (Base64 ke Teks)",
            description = "Mendekode gambar base64/file/uri/screenshot terakhir menjadi deskripsi tekstual lengkap (dimensi, warna dominan, kecerahan, tingkat detail, peta bentuk ASCII, dan OCR teks) sehingga AI tanpa kemampuan vision pun dapat membaca isi gambar",
            category = "Media & Analisis",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "decode_image",
            parametersSchema = """{"source": "last_screenshot", "base64": "opsional", "path": "opsional", "with_ocr": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screen_orientation",
            name = "Cek Orientasi Layar",
            description = "Membaca rotasi layar saat ini (0/90/180/270 derajat, portrait/landscape) beserta dimensi piksel. Panggil ini SEBELUM tap/screenshot agar koordinat tidak meleset saat layar berputar",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "screen_orientation",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "find_by_text",
            name = "Cari Elemen berdasarkan Teks",
            description = "Mencari elemen UI di layar yang cocok dengan teks (label tombol, judul, dst) dan mengembalikan koordinat pusat + ukurannya. Bisa menunggu elemen muncul via timeout_ms",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "find_by_text",
            parametersSchema = """{"text": "Login", "timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "tap_by_text",
            name = "Tap berdasarkan Teks",
            description = "Mengetuk elemen UI langsung berdasarkan teksnya (mis. tombol 'Izinkan' atau 'OK') tanpa menghitung koordinat manual. Jauh lebih akurat daripada tap koordinat, apalagi di mode landscape",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "tap_by_text",
            parametersSchema = """{"text": "Izinkan", "timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "wait_for_element",
            name = "Tunggu Elemen Muncul",
            description = "Menunggu hingga elemen dengan teks tertentu muncul di layar (dengan timeout). Dipakai setelah aksi (tap/open_app) untuk memastikan layar sudah termuat sebelum aksi berikutnya",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "wait_for_element",
            parametersSchema = """{"text": "Berhasil", "timeout_ms": 5000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "dumpsys_window",
            name = "Info Window Aktif (dumpsys)",
            description = "Menampilkan window yang sedang fokus, rotasi display, dan dimensi layar via dumpsys window — untuk diagnosa orientasi dan memastikan app target benar-benar di depan",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mRotation|mDisplayWidth|mDisplayHeight' | head -20",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "scroll_to_text",
            name = "Scroll sampai Teks Ditemukan",
            description = "Scroll otomatis layar sampai elemen dengan teks tertentu terlihat (untuk list panjang) lalu kembalikan koordinatnya — lebih baik daripada swipe buta berulang",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "scroll_to_text",
            parametersSchema = """{"text": "Setelan", "max_swipes": 6}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "wait_stable",
            name = "Tunggu Layar Stabil",
            description = "Menunggu hingga layar tidak berubah lagi (animasi/loading selesai) sebelum aksi atau screenshot berikutnya",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "wait_stable",
            parametersSchema = """{"timeout_ms": 3000}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "diff_screen",
            name = "Bandingkan Layar Before/After",
            description = "Membandingkan layar saat ini dengan snapshot pemanggilan sebelumnya. Panggil sebelum aksi (baseline) lalu setelah aksi: 'tidak ada perubahan' berarti aksi kemungkinan gagal",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "diff_screen",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "accessibility_click",
            name = "Klik Elemen via Accessibility",
            description = "Klik node UI langsung lewat AccessibilityNodeInfo berdasarkan teks/view-id — bypass koordinat sama sekali, paling akurat untuk tombol standar",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "accessibility_click",
            parametersSchema = """{"element_id": "com.whatsapp:id/send", "text": "opsional"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "ocr_region",
            name = "OCR Area Tertentu",
            description = "OCR hanya pada AREA tertentu dari screenshot/gambar (crop dulu) — jauh lebih hemat token & akurat daripada decode_image satu layar penuh. Region bisa piksel (left/top/right/bottom) atau persen (x/y/w/h_percent). Tanpa region = satu layar penuh",
            category = "Media & Analisis",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "ocr_region",
            parametersSchema = """{"x_percent": 0, "y_percent": 0, "w_percent": 100, "h_percent": 100, "source": "last_screenshot", "base64": "opsional", "path": "opsional"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "record_screen",
            name = "Rekam Layar (Video MP4)",
            description = "Merekam layar perangkat menjadi video MP4 — untuk debugging multi-step atau dokumentasi otomasi. Params {\"action\": \"start\"|\"stop\"|\"status\"}; otomatis berhenti maksimal 3 menit; hasil berupa path file video",
            category = "Media & Analisis",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "record_screen",
            parametersSchema = """{"action": "start"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "adb_via_shizuku",
            name = "ADB Shell via Shizuku",
            description = "Menjalankan perintah shell level ADB (uid shell) via Shizuku — membuka akses pm grant, am force-stop, uiautomator dump, dumpsys penuh, dll yang diblokir bagi uid aplikasi biasa. Butuh app Shizuku aktif + izin diberikan",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "adb_via_shizuku",
            parametersSchema = """{"command": "pm list packages -3", "action": "exec"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "input_swipe_bezier",
            name = "Swipe Kurva Bezier (Manusiawi)",
            description = "Swipe mengikuti kurva Bezier — menyerupai gerakan jari manusia, berguna untuk UI yang mengabaikan swipe garis lurus (carousel, map, drawer). Param bend (-1.0..1.0) mengatur kelengkungan",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "input_swipe_bezier",
            parametersSchema = """{"x1": 500, "y1": 800, "x2": 500, "y2": 300, "duration_ms": 600, "bend": 0.35}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        )
    )

    private val _tools = MutableStateFlow<List<CustomTool>>(builtInTools)
    val tools: StateFlow<List<CustomTool>> = _tools.asStateFlow()

    // Permission Mode State
    private val _permissionMode = MutableStateFlow(AiPermissionMode.FULL_ACCESS)
    val permissionMode: StateFlow<AiPermissionMode> = _permissionMode.asStateFlow()

    private val _customPermissions = MutableStateFlow(CustomPermissionSettings())
    val customPermissions: StateFlow<CustomPermissionSettings> = _customPermissions.asStateFlow()

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Permission settings: baca prefs kecil, cepat — aman di main thread.
        loadPermissionSettings()
        // Parsing JSON custom tools bisa besar (tools buatan AI) — jangan blokir
        // main thread saat startup; hasil di-update lewat StateFlow saat siap.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadCustomTools()
        }
    }

    fun setPermissionMode(mode: AiPermissionMode) {
        _permissionMode.value = mode
        sharedPreferences?.edit()?.putString("ai_permission_mode", mode.name)?.apply()
    }

    fun updateCustomPermissions(settings: CustomPermissionSettings) {
        _customPermissions.value = settings
        sharedPreferences?.edit()?.apply {
            putBoolean("perm_read_screen", settings.allowReadScreen)
            putBoolean("perm_tap_swipe", settings.allowTapSwipe)
            putBoolean("perm_type_text", settings.allowTypeText)
            putBoolean("perm_open_app", settings.allowOpenApp)
            putBoolean("perm_shell", settings.allowShellCommands)
            putBoolean("perm_create_tool", settings.allowCreateTools)
            putBoolean("perm_system_keys", settings.allowSystemKeys)
            apply()
        }
    }

    private fun loadPermissionSettings() {
        val prefs = sharedPreferences ?: return
        val modeStr = prefs.getString("ai_permission_mode", AiPermissionMode.FULL_ACCESS.name)
        _permissionMode.value = try {
            AiPermissionMode.valueOf(modeStr ?: AiPermissionMode.FULL_ACCESS.name)
        } catch (_: Exception) {
            AiPermissionMode.FULL_ACCESS
        }

        _customPermissions.value = CustomPermissionSettings(
            allowReadScreen = prefs.getBoolean("perm_read_screen", true),
            allowTapSwipe = prefs.getBoolean("perm_tap_swipe", true),
            allowTypeText = prefs.getBoolean("perm_type_text", true),
            allowOpenApp = prefs.getBoolean("perm_open_app", true),
            allowShellCommands = prefs.getBoolean("perm_shell", true),
            allowCreateTools = prefs.getBoolean("perm_create_tool", true),
            allowSystemKeys = prefs.getBoolean("perm_system_keys", true)
        )
    }

    private fun loadCustomTools() {
        val prefs = sharedPreferences ?: return
        val jsonStr = prefs.getString(KEY_TOOLS_JSON, null)
        val customList = mutableListOf<CustomTool>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArr = JSONArray(jsonStr)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    customList.add(
                        CustomTool(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            description = obj.optString("description"),
                            category = obj.optString("category", "Custom"),
                            scriptType = try {
                                ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                            } catch (_: Exception) {
                                ToolScriptType.SHELL
                            },
                            command = obj.optString("command"),
                            parametersSchema = obj.optString("parametersSchema", "{}"),
                            riskLevel = try {
                                ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                            } catch (_: Exception) {
                                ToolRiskLevel.LOW
                            },
                            isEnabled = obj.optBoolean("isEnabled", true),
                            isBuiltIn = false,
                            createdByAi = obj.optBoolean("createdByAi", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved custom tools", e)
            }
        }

        _tools.value = builtInTools + customList
    }

    private fun saveCustomTools() {
        val prefs = sharedPreferences ?: return
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
                put("createdAt", tool.createdAt)
            }
            jsonArr.put(obj)
        }
        prefs.edit().putString(KEY_TOOLS_JSON, jsonArr.toString()).apply()
    }

    fun addOrUpdateTool(tool: CustomTool) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == tool.id }
        if (index != -1) {
            val existing = current[index]
            if (existing.isBuiltIn) {
                current[index] = existing.copy(isEnabled = tool.isEnabled)
            } else {
                current[index] = tool
            }
        } else {
            current.add(tool)
        }
        _tools.value = current
        saveCustomTools()
    }

    fun deleteTool(toolId: String): Boolean {
        val current = _tools.value.toMutableList()
        val target = current.firstOrNull { it.id == toolId } ?: return false
        if (target.isBuiltIn) {
            return false
        }
        current.removeAll { it.id == toolId }
        _tools.value = current
        saveCustomTools()
        return true
    }

    fun toggleTool(toolId: String) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == toolId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(isEnabled = !item.isEnabled)
            _tools.value = current
            saveCustomTools()
        }
    }

    fun getTool(toolId: String): CustomTool? {
        val q = toolId.trim()
        // Cocokkan berdasarkan ID dulu, lalu berdasarkan NAMA (AI sering memanggil
        // tool custom pakai nama yang diberikannya, bukan id yang di-generate sistem).
        return _tools.value.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: _tools.value.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    fun isToolEnabled(toolId: String): Boolean {
        val tool = getTool(toolId) ?: return false
        return tool.isEnabled
    }

    private val _pendingDeletionRequest = MutableStateFlow<DeletionRequest?>(null)
    val pendingDeletionRequest: StateFlow<DeletionRequest?> = _pendingDeletionRequest.asStateFlow()

    fun requestDeletionApproval(title: String, details: String, onConfirm: () -> Unit, onDeny: () -> Unit) {
        if (_permissionMode.value == AiPermissionMode.FULL_ACCESS) {
            onConfirm()
        } else {
            _pendingDeletionRequest.value = DeletionRequest(
                title = title,
                details = details,
                onConfirm = {
                    _pendingDeletionRequest.value = null
                    onConfirm()
                },
                onDeny = {
                    _pendingDeletionRequest.value = null
                    onDeny()
                }
            )
        }
    }

    fun confirmPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onConfirm()
    }

    fun denyPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onDeny()
    }

    fun isDeletionAction(toolName: String, command: String, params: JSONObject): Boolean {
        val nameLower = toolName.lowercase()
        val cmdLower = command.lowercase()
        val actParam = params.optString("action", params.optString("act", "")).lowercase()

        return nameLower.contains("delete") || nameLower.contains("remove") || nameLower.contains("hapus") ||
               cmdLower.contains("rm ") || cmdLower.contains("rm -rf") || cmdLower.contains("delete") ||
               cmdLower.contains("unlink") || actParam in listOf("delete", "remove", "hapus", "unlink", "clear")
    }

    /**
     * Checks if executing this action is allowed under the current AI Permission Mode.
     */
    fun checkPermission(toolName: String, riskLevel: ToolRiskLevel = ToolRiskLevel.LOW): Pair<Boolean, String?> {
        val mode = _permissionMode.value
        val lower = toolName.lowercase()

        when (mode) {
            AiPermissionMode.SANDBOXED -> {
                val isReadOnly = lower in listOf(
                    "read_screen", "battery", "get_telemetry", "list_tools", "get_storage",
                    "cek_ram", "clipboard_read", "screen_orientation", "find_by_text",
                    "wait_for_element", "dumpsys_window", "get_device_resolution",
                    "get_current_app", "list_apps", "recall_memory", "wait_stable", "ocr_region",
                    "diff_screen", "scroll_to_text"
                )
                if (!isReadOnly) {
                    return Pair(false, "Aksi '$toolName' diblokir oleh AI Permission Mode: SANDBOXED (Hanya baca yang diizinkan).")
                }
                return Pair(true, null)
            }

            AiPermissionMode.LOW_RISK, AiPermissionMode.FULL_ACCESS -> {
                return Pair(true, null)
            }

            AiPermissionMode.CUSTOM -> {
                val custom = _customPermissions.value
                when (lower) {
                    "read_screen", "screenshot" -> if (!custom.allowReadScreen) return Pair(false, "Izin Baca Layar dinonaktifkan di setelan kustom.")
                    "tap", "swipe" -> if (!custom.allowTapSwipe) return Pair(false, "Izin Tap & Swipe dinonaktifkan di setelan kustom.")
                    "type_text", "send_text" -> if (!custom.allowTypeText) return Pair(false, "Izin Ketik Teks dinonaktifkan di setelan kustom.")
                    "open_app" -> if (!custom.allowOpenApp) return Pair(false, "Izin Buka Aplikasi dinonaktifkan di setelan kustom.")
                    "shell", "termux_command" -> if (!custom.allowShellCommands) return Pair(false, "Izin Shell Command dinonaktifkan di setelan kustom.")
                    "create_tool", "create_python_tool" -> if (!custom.allowCreateTools) return Pair(false, "Izin AI Membuat Tool dinonaktifkan di setelan kustom.")
                    "press_key" -> if (!custom.allowSystemKeys) return Pair(false, "Izin Tombol Navigasi dinonaktifkan di setelan kustom.")
                }
                return Pair(true, null)
            }
        }
    }

    /**
     * AI Dynamic Tool Creation: registers a new tool directly generated by AI assistant or user.
     */
    fun registerAiGeneratedTool(
        name: String,
        description: String,
        scriptTypeStr: String,
        command: String,
        paramsSchema: String = "{}"
    ): CustomTool {
        val cleanId = "tool_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_") + "_" + System.currentTimeMillis() % 10000
        val scriptType = try {
            ToolScriptType.valueOf(scriptTypeStr.uppercase())
        } catch (_: Exception) {
            ToolScriptType.SHELL
        }

        val newTool = CustomTool(
            id = cleanId,
            name = name,
            description = description,
            category = "AI Generated",
            scriptType = scriptType,
            command = command,
            parametersSchema = paramsSchema.ifBlank { "{}" },
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = false,
            createdByAi = true,
            createdAt = System.currentTimeMillis()
        )
        addOrUpdateTool(newTool)
        Log.i(TAG, "Registered new tool: ${newTool.id} ($name)")
        return newTool
    }

    /**
     * Executes custom tools directly on Android device with parameter interpolation.
     */
    suspend fun executeCustomTool(tool: CustomTool, params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (!tool.isEnabled) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = "Tool '${tool.name}' sedang dalam status non-aktif."
            )
        }

        val (allowed, reason) = checkPermission(tool.id, tool.riskLevel)
        if (!allowed) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = reason ?: "Permission denied"
            )
        }

        val context = JarvisApp.instance

        return@withContext when (tool.scriptType) {
            ToolScriptType.SHELL -> {
                var finalCommand = tool.command.trim()
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = params.optString(key, "")
                    finalCommand = finalCommand.replace("{$key}", value)
                }

                // If command is generic or wrapped in {command}, check params
                if (finalCommand == "{command}" || finalCommand.isBlank()) {
                    finalCommand = params.optString("command", params.optString("cmd", ""))
                }

                // Save multi-line scripts to filesDir/scripts for proper execution
                if (finalCommand.contains("\n") || finalCommand.startsWith("#!")) {
                    val scriptsDir = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
                    val scriptExt = if (finalCommand.contains("python") || finalCommand.startsWith("#!/usr/bin/env python")) "py" else "sh"
                    val scriptFile = File(scriptsDir, "${tool.id}.$scriptExt")
                    scriptFile.writeText(finalCommand)
                    scriptFile.setExecutable(true)

                    val runnerCmd = if (scriptExt == "py") "python3 \"${scriptFile.absolutePath}\"" else "sh \"${scriptFile.absolutePath}\""
                    AdbShizukuManager.executeShell(runnerCmd, scriptsDir)
                } else {
                    AdbShizukuManager.executeShell(finalCommand)
                }
            }

            ToolScriptType.HTTP -> {
                executeHttpTool(tool, params)
            }

            ToolScriptType.INTENT -> {
                executeIntentTool(context, tool, params)
            }

            ToolScriptType.ACCESSIBILITY -> {
                val service = JarvisAccessibilityService.instance
                    ?: return@withContext ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")

                val cmdLower = tool.command.lowercase().trim()
                if (cmdLower in listOf("screenshot", "take_screenshot", "screencap")) {
                    val (base64, err) = ScreenshotManager.captureBase64(context)
                    if (base64 != null) {
                        ToolResult(
                            status = "ok",
                            result = "🖼️ Tangkapan layar OK - ${ScreenshotManager.lastCaptureInfo()}. Dikirim ke analisis visi AI Anda.",
                            extra = mapOf("screenshot_b64" to base64)
                        )
                    } else {
                        ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                    }
                } else if (cmdLower == "ocr_screenshot") {
                    val (base64, err) = ScreenshotManager.captureBase64(context)
                    if (base64 != null) {
                        val text = runCatching {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp == null) null
                            else {
                                val r = com.example.quiz.OcrEngine.recognize(bmp)
                                bmp.recycle()
                                r.getOrNull()
                            }
                        }.getOrNull()
                        if (text.isNullOrBlank()) {
                            ToolResult("ok", result = "OCR tidak menemukan teks pada tangkapan. Layar mungkin berisi gambar tanpa teks, atau tangkapan kosong/hitam (coba ulangi, atau gunakan tool screenshot lalu decode_image).")
                        } else {
                            val shown = text.take(4000) + if (text.length > 4000) "\n...(+${text.length - 4000} karakter lagi)" else ""
                            ToolResult("ok", result = "🔤 Teks di layar (OCR satu langkah):\n$shown")
                        }
                    } else {
                        ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                    }
                } else if (cmdLower in listOf("read_screen", "get_ui_tree", "dump_ui")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val elements = service.getScreenElements()
                    val summary = elements.joinToString("\n") { el ->
                        "- [${el.id}] '${el.text.ifBlank { el.contentDescription }}' (${el.className}) @ (${el.bounds.centerX}, ${el.bounds.centerY})"
                    }
                    ToolResult("ok", result = "📱 Resolusi Layar: ${metrics.widthPixels}x${metrics.heightPixels} px (${metrics.densityDpi} dpi)\n📱 Elemen UI di Layar saat ini (${elements.size} elemen):\n$summary")
                } else if (params.has("x") || params.has("y") || params.has("x_percent") || params.has("y_percent")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val screenW = metrics.widthPixels.toFloat()
                    val screenH = metrics.heightPixels.toFloat()

                    val targetX = when {
                        params.has("x_percent") -> (params.getDouble("x_percent") / 100.0 * screenW).toFloat()
                        params.has("x") -> {
                            val rx = params.getDouble("x").toFloat()
                            if (rx > 0.0f && rx <= 1.0f) rx * screenW else rx.coerceIn(0f, screenW)
                        }
                        else -> screenW / 2f
                    }

                    val targetY = when {
                        params.has("y_percent") -> (params.getDouble("y_percent") / 100.0 * screenH).toFloat()
                        params.has("y") -> {
                            val ry = params.getDouble("y").toFloat()
                            if (ry > 0.0f && ry <= 1.0f) ry * screenH else ry.coerceIn(0f, screenH)
                        }
                        else -> screenH / 2f
                    }

                    service.tapCoordinates(targetX, targetY)
                } else if (params.has("text") || params.has("value")) {
                    val text = params.optString("text", params.optString("value", ""))
                    val elId = if (params.has("element_id") && !params.isNull("element_id")) params.getString("element_id") else null
                    service.typeText(elId, text)
                } else if (params.has("x1") || params.has("y1")) {
                    val metrics = ScreenshotManager.getScreenMetrics(context)
                    val screenW = metrics.widthPixels.toFloat()
                    val screenH = metrics.heightPixels.toFloat()

                    val x1 = params.optDouble("x1", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                    val y1 = params.optDouble("y1", (screenH * 0.8).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                    val x2 = params.optDouble("x2", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                    val y2 = params.optDouble("y2", (screenH * 0.2).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }

                    service.swipeCoordinates(
                        x1, y1, x2, y2,
                        params.optLong("duration_ms", 300L)
                    )
                } else if (tool.command.equals("open_app", ignoreCase = true) || params.has("package_name") || params.has("app")) {
                    val pkg = params.optString("package_name", params.optString("app", tool.command))
                    service.openApp(pkg)
                } else {
                    val targetId = params.optString("element_id", tool.command)
                    service.tapElement(targetId)
                }
            }

            ToolScriptType.CUSTOM_LOGIC -> {
                executeCustomLogic(context, tool.command, params)
            }
        }
    }

    private fun executeHttpTool(tool: CustomTool, params: JSONObject): ToolResult {
        var rawUrl = tool.command.trim()
        var method = "GET"

        if (rawUrl.startsWith("GET ", ignoreCase = true)) {
            method = "GET"
            rawUrl = rawUrl.substring(4).trim()
        } else if (rawUrl.startsWith("POST ", ignoreCase = true)) {
            method = "POST"
            rawUrl = rawUrl.substring(5).trim()
        }

        // Interpolate {param} placeholders in URL
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = params.optString(key, "")
            rawUrl = rawUrl.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8"))
        }

        if (params.has("url")) {
            rawUrl = params.getString("url")
        }
        if (params.has("method")) {
            method = params.getString("method").uppercase()
        }

        if (rawUrl.isBlank() || (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))) {
            return ToolResult("error", message = "URL HTTP tidak valid: '$rawUrl'")
        }

        return try {
            val reqBuilder = Request.Builder().url(rawUrl)
            if (method == "POST") {
                val bodyStr = params.optString("body", params.optString("json", "{}"))
                reqBuilder.post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                reqBuilder.get()
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ToolResult("ok", result = "HTTP $code OK:\n$body")
            } else {
                ToolResult("error", message = "HTTP Error $code:\n$body")
            }
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal melakukan HTTP request ke '$rawUrl': ${e.message}")
        }
    }

    private fun executeIntentTool(context: Context, tool: CustomTool, params: JSONObject): ToolResult {
        return try {
            var raw = tool.command.trim()
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.optString(key, "")
                raw = raw.replace("{$key}", value)
            }

            val intent = when {
                raw.startsWith("am start", ignoreCase = true) -> {
                    // Execute via shell for full ADB am intent capability
                    return AdbShizukuManager.executeShell(raw)
                }
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
                raw.startsWith("tel:", ignoreCase = true) -> {
                    Intent(Intent.ACTION_DIAL, Uri.parse(raw))
                }
                raw.startsWith("package:", ignoreCase = true) -> {
                    val pkgName = raw.removePrefix("package:").trim()
                    context.packageManager.getLaunchIntentForPackage(pkgName) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkgName"))
                }
                raw.startsWith("android.settings", ignoreCase = true) -> {
                    Intent(raw)
                }
                else -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(raw)
                    launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult("ok", result = "Berhasil menjalankan Intent / membuka aksi: $raw")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menjalankan Intent '${tool.command}': ${e.message}")
        }
    }

    suspend fun executeCustomLogic(context: Context, command: String, params: JSONObject): ToolResult {
        return when (command.lowercase().trim()) {
            "save_memory" -> {
                val category = params.optString("category", params.optString("title", "Catatan"))
                val content = params.optString("content", params.optString("text", ""))
                if (content.isBlank()) {
                    ToolResult("error", message = "Parameter 'content' wajib diisi untuk menyimpan memori.")
                } else {
                    val result = com.example.data.JarvisMemoryManager.saveMemory(category, content)
                    ToolResult("ok", result = result)
                }
            }

            "recall_memory" -> {
                val query = params.optString("query", params.optString("keyword", ""))
                val result = com.example.data.JarvisMemoryManager.recallMemory(query)
                ToolResult("ok", result = result)
            }

            "summarize_memory" -> {
                val result = com.example.data.JarvisMemoryManager.checkAndAutoArchiveMemory()
                ToolResult("ok", result = result)
            }

            "get_device_resolution", "device_resolution", "screen_resolution" -> {
                val metrics = ScreenshotManager.getScreenMetrics(context)
                ToolResult(
                    status = "ok",
                    result = "📱 Resolusi & Matriks Layar Perangkat:\n- Width: ${metrics.widthPixels} px\n- Height: ${metrics.heightPixels} px\n- Density: ${metrics.densityDpi} dpi (${metrics.density}x)\n- Orientasi: ${if (metrics.widthPixels < metrics.heightPixels) "PORTRAIT" else "LANDSCAPE"}"
                )
            }

            "screenshot", "take_screenshot", "screencap" -> {
                val (base64, err) = ScreenshotManager.captureBase64(context)
                if (base64 != null) {
                    ToolResult(
                        status = "ok",
                        result = "🖼️ Screenshot tangkapan layar berhasil diambil dan dikirim langsung ke analisis visi AI Anda.",
                        extra = mapOf("screenshot_b64" to base64)
                    )
                } else {
                    ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                }
            }

            "open_file" -> {
                try {
                    val pathOrUri = params.optString("path", params.optString("uri", ""))
                    val uri = if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                        Uri.parse(pathOrUri)
                    } else {
                        val file = File(pathOrUri)
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, params.optString("mime_type", "*/*"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult("ok", result = "📂 Berhasil membuka berkas: $pathOrUri")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membuka berkas: ${e.message}")
                }
            }

            "get_current_app" -> {
                val currentPkg = JarvisAccessibilityService.currentApp.value.ifBlank { "com.example" }
                ToolResult("ok", result = "📱 Aplikasi aktif saat ini: $currentPkg")
            }

            "list_apps" -> {
                try {
                    val pm = context.packageManager
                    val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    val appList = packages.take(50).joinToString("\n") { app ->
                        "- ${pm.getApplicationLabel(app)} (${app.packageName})"
                    }
                    ToolResult("ok", result = "📦 Daftar Aplikasi Terinstal (50 teratas):\n$appList")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengambil daftar aplikasi: ${e.message}")
                }
            }

            "kill_app" -> {
                val pkg = params.optString("package_name", params.optString("package", ""))
                if (pkg.isBlank()) {
                    ToolResult("error", message = "Parameter 'package_name' wajib diisi.")
                } else {
                    val res = AdbShizukuManager.executeShell("am force-stop $pkg")
                    ToolResult("ok", result = "Menghentikan paket '$pkg':\n${res.result ?: res.message}")
                }
            }

            "get_storage" -> {
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val totalBytes = stat.blockCountLong * stat.blockSizeLong
                    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                    val usedBytes = totalBytes - freeBytes
                    val totalGb = String.format(java.util.Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0))
                    val usedGb = String.format(java.util.Locale.US, "%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0))
                    val freeGb = String.format(java.util.Locale.US, "%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0))
                    ToolResult("ok", result = "📊 Informasi Penyimpanan HP:\n- Total: $totalGb GB\n- Digunakan: $usedGb GB\n- Sisa: $freeGb GB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca storage: ${e.message}")
                }
            }

            "cek_ram" -> {
                try {
                    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val totalMb = memInfo.totalMem / (1024 * 1024)
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val usedMb = totalMb - availMb
                    val totalGb = String.format(java.util.Locale.US, "%.1f", totalMb / 1024.0)
                    ToolResult("ok", result = "⚡ Informasi RAM HP:\n- Total: $totalMb MB ($totalGb GB)\n- Tersedia: $availMb MB\n- Terpakai: $usedMb MB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca RAM: ${e.message}")
                }
            }

            "battery" -> {
                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    } else false
                    val chargingStr = if (isCharging) "Sedang di-charge ⚡" else "Menggunakan baterai"
                    ToolResult("ok", result = "🔋 Status Baterai: $level% ($chargingStr)")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca baterai: ${e.message}")
                }
            }

            "clipboard_read" -> {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(Clipboard kosong)"
                    ToolResult("ok", result = "📋 Isi Clipboard:\n$text")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca clipboard: ${e.message}")
                }
            }

            "clipboard_write" -> {
                try {
                    val text = params.optString("text", params.optString("content", ""))
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
                    ToolResult("ok", result = "📋 Berhasil menyalin teks ke clipboard: \"$text\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal menulis clipboard: ${e.message}")
                }
            }

            "flashlight_toggle" -> {
                try {
                    val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = camManager.cameraIdList.firstOrNull() ?: return ToolResult("error", message = "Kamera/Senter tidak ditemukan")
                    val enable = params.optBoolean("enable", true)
                    camManager.setTorchMode(cameraId, enable)
                    ToolResult("ok", result = if (enable) "🔦 Senter dinyalakan" else "🔦 Senter dimatikan")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengatur senter: ${e.message}")
                }
            }

            "send_notification" -> {
                try {
                    val title = params.optString("title", "Andra Control")
                    val message = params.optString("message", params.optString("text", "Notifikasi dari JARVIS"))
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = "jarvis_tools_channel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(channelId, "Notifikasi Aplikasi", NotificationManager.IMPORTANCE_DEFAULT)
                        nm.createNotificationChannel(channel)
                    }
                    val notification = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notification)
                    ToolResult("ok", result = "🔔 Notifikasi terkirim: \"$title\" - \"$message\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengirim notifikasi: ${e.message}")
                }
            }

            "termux_service" -> {
                val action = params.optString("action", params.optString("act", "status"))
                val service = params.optString("service", params.optString("name", "jarvis_agent"))
                AdbShizukuManager.manageTermuxService(action, service)
            }

            "termux_api" -> {
                val command = params.optString("command", params.optString("api", params.optString("cmd", "battery")))
                val args = params.optString("args", params.optString("arguments", params.optString("options", "")))
                AdbShizukuManager.executeTermuxApi(command, args)
            }

            "termux_pkg" -> {
                val action = params.optString("action", "list")
                val pkgName = params.optString("package", params.optString("pkg", params.optString("name", "")))
                AdbShizukuManager.executeTermuxPkg(action, pkgName)
            }

            "termux_python" -> {
                val code = params.optString("code", params.optString("script", params.optString("py", "")))
                val filename = params.optString("filename", "script.py")
                AdbShizukuManager.executeTermuxPython(code, filename)
            }

            "termux_file" -> {
                val action = params.optString("action", "list")
                val path = params.optString("path", params.optString("file", "."))
                val content = params.optString("content", params.optString("text", ""))
                AdbShizukuManager.executeTermuxFile(action, path, content)
            }

            "screen_orientation", "get_orientation", "rotation" -> {
                val metrics = ScreenshotManager.getScreenMetrics(context)
                val rot = ScreenshotManager.currentRotation(context)
                val rotName = when (rot) {
                    0 -> "0° — PORTRAIT"
                    1 -> "90° — LANDSCAPE"
                    2 -> "180° — PORTRAIT (terbalik)"
                    3 -> "270° — LANDSCAPE (terbalik)"
                    else -> "$rot°"
                }
                val orient = if (metrics.widthPixels > metrics.heightPixels) "LANDSCAPE" else "PORTRAIT"
                ToolResult(
                    status = "ok",
                    result = "🔄 Rotasi layar saat ini: $rot ($rotName)\n" +
                            "📐 Dimensi display: ${metrics.widthPixels}x${metrics.heightPixels} px @ ${metrics.densityDpi} dpi\n" +
                            "🧭 Orientasi: $orient\n" +
                            "Tip: pakai koordinat dalam ruang ${metrics.widthPixels}x${metrics.heightPixels} ini untuk tap/swipe, atau lebih aman pakai 'tap_by_text'."
                )
            }

            "find_by_text", "wait_for_element" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", params.optString("element_text", "")))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks elemen yang dicari).")
                }
                val defaultTimeout = if (command.equals("wait_for_element", true)) 5000L else 0L
                val timeout = params.optLong("timeout_ms", defaultTimeout).coerceIn(0L, 20000L)
                val deadline = System.currentTimeMillis() + timeout
                var matched = service.findElementsByText(query)
                while (matched.isEmpty() && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(250)
                    matched = service.findElementsByText(query)
                }
                if (matched.isEmpty()) {
                    ToolResult(
                        "error",
                        message = "Tidak ada elemen yang cocok dengan teks '$query'${if (timeout > 0) " setelah menunggu ${timeout}ms" else ""}."
                    )
                } else {
                    val list = matched.joinToString("\n") { el ->
                        "- '${el.text.ifBlank { el.contentDescription }}' (${el.className}) @ center=(${el.bounds.centerX}, ${el.bounds.centerY}) size=${el.bounds.width}x${el.bounds.height}"
                    }
                    ToolResult("ok", result = "🔎 Ditemukan ${matched.size} elemen untuk '$query':\n$list")
                }
            }

            "tap_by_text" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", params.optString("element_text", "")))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks elemen yang akan ditap).")
                }
                val timeout = params.optLong("timeout_ms", 3000L).coerceIn(0L, 20000L)
                service.tapByText(query, timeout)
            }

            "scroll_to_text" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val query = params.optString("text", params.optString("query", ""))
                if (query.isBlank()) {
                    return ToolResult("error", message = "Parameter 'text' wajib diisi (teks yang dicari saat scroll).")
                }
                val maxSwipes = params.optInt("max_swipes", 6).coerceIn(1, 15)
                service.scrollToText(query, maxSwipes)
            }

            "wait_stable" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                service.waitForStableScreen(params.optLong("timeout_ms", 3000L))
            }

            "diff_screen" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                service.diffScreen()
            }

            "accessibility_click" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val ident = params.optString("element_id", params.optString("text", params.optString("id", "")))
                if (ident.isBlank()) {
                    return ToolResult("error", message = "Parameter 'element_id' atau 'text' wajib diisi.")
                }
                service.tapElement(ident)
            }

            "record_screen" -> {
                val action = params.optString("action", params.optString("act", "start")).lowercase().trim()
                when (action) {
                    "stop" -> ScreenRecordManager.stop()
                    "status" -> ScreenRecordManager.status()
                    "start" -> ScreenRecordManager.start(context)
                    else -> ToolResult("error", message = "Aksi '$action' tidak dikenali. Pilih: start, stop, status")
                }
            }

            "adb_via_shizuku", "shizuku_shell" -> {
                val action = params.optString("action", params.optString("act", "exec")).lowercase().trim()
                when (action) {
                    "status", "check" -> {
                        val alive = AdbShizukuManager.isShizukuBinderAlive()
                        val granted = AdbShizukuManager.shizukuPermissionGranted()
                        ToolResult(
                            status = "ok",
                            result = "🩸 Shizuku binder: ${if (alive) "AKTIF" else "TIDAK AKTIF"} | Izin app: ${if (granted) "DIBERIKAN" else "BELUM"}\n" +
                                    (if (!alive) "Buka app Shizuku → Start (Wireless Debugging), lalu ulangi." else if (!granted) "Panggil {\"action\":\"permission\"} untuk meminta izin." else "Siap dipakai: {\"command\":\"<perintah adb>\"}")
                        )
                    }
                    "permission", "grant" -> AdbShizukuManager.requestShizukuPermission()
                    "exec", "run", "shell" -> {
                        val cmd = params.optString("command", params.optString("cmd", ""))
                        if (cmd.isBlank()) {
                            ToolResult("error", message = "Parameter 'command' wajib diisi untuk action=exec.")
                        } else {
                            AdbShizukuManager.executeShizukuShell(cmd)
                        }
                    }
                    else -> ToolResult("error", message = "Aksi '$action' tidak dikenali. Pilih: exec, status, permission")
                }
            }

            "ocr_region" -> {
                val source = params.optString("source", params.optString("src", "")).trim().lowercase()
                val b64 = params.optString("base64", params.optString("image_base64", params.optString("image", "")))
                val filePath = params.optString("path", params.optString("file", ""))
                val useLast = source == "last_screenshot" || source == "screenshot" || (b64.isBlank() && filePath.isBlank())
                com.example.service.ImageDecodeManager.analyzeRegion(
                    base64 = if (useLast && b64.isBlank()) null else b64.ifBlank { null },
                    path = filePath.ifBlank { null },
                    left = if (params.has("left")) params.optInt("left") else null,
                    top = if (params.has("top")) params.optInt("top") else null,
                    right = if (params.has("right")) params.optInt("right") else null,
                    bottom = if (params.has("bottom")) params.optInt("bottom") else null,
                    xPct = if (params.has("x_percent")) params.optDouble("x_percent") else null,
                    yPct = if (params.has("y_percent")) params.optDouble("y_percent") else null,
                    wPct = if (params.has("w_percent")) params.optDouble("w_percent") else null,
                    hPct = if (params.has("h_percent")) params.optDouble("h_percent") else null
                )
            }

            "input_swipe_bezier", "swipe_bezier", "bezier_swipe" -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")
                val metrics = ScreenshotManager.getScreenMetrics(context)
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()
                val x1 = params.optDouble("x1", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                val y1 = params.optDouble("y1", (screenH * 0.8).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                val x2 = params.optDouble("x2", (screenW * 0.5).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenW else it }
                val y2 = params.optDouble("y2", (screenH * 0.2).toDouble()).toFloat().let { if (it in 0.001f..1.0f) it * screenH else it }
                val duration = params.optLong("duration_ms", 600L)
                val bend = params.optDouble("bend", params.optDouble("curve", 0.35)).toFloat()
                service.bezierSwipe(x1, y1, x2, y2, duration, bend)
            }

            else -> {
                ToolResult("ok", result = "Perintah custom logic '$command' selesai dijalankan.")
            }
        }
    }

    /**
     * Export all custom tools to JSON string
     */
    fun exportToolsToJson(): String {
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
            }
            jsonArr.put(obj)
        }
        return jsonArr.toString(2)
    }

    /**
     * Import tools from JSON string
     */
    fun importToolsFromJson(jsonString: String): Result<Int> {
        return try {
            val jsonArr = JSONArray(jsonString.trim())
            var count = 0
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val rawId = obj.optString("id").ifBlank { "custom_" + System.currentTimeMillis() + "_$i" }
                val tool = CustomTool(
                    id = rawId,
                    name = obj.optString("name", "Imported Tool"),
                    description = obj.optString("description", ""),
                    category = obj.optString("category", "Imported"),
                    scriptType = try {
                        ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                    } catch (_: Exception) {
                        ToolScriptType.SHELL
                    },
                    command = obj.optString("command", ""),
                    parametersSchema = obj.optString("parametersSchema", "{}"),
                    riskLevel = try {
                        ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                    } catch (_: Exception) {
                        ToolRiskLevel.LOW
                    },
                    isEnabled = obj.optBoolean("isEnabled", true),
                    isBuiltIn = false,
                    createdByAi = obj.optBoolean("createdByAi", false),
                    createdAt = System.currentTimeMillis()
                )
                addOrUpdateTool(tool)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Ready-made Real Functional Templates for users & guide
    val toolTemplates = listOf(
        CustomTool(
            id = "template_shell_battery_temp",
            name = "Suhu Baterai (Dumpsys)",
            description = "Membaca temperatur baterai perangkat secara akurat via Android dumpsys",
            category = "Hardware & Status",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys battery | grep -i temperature",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_disk_free",
            name = "Cek Disk & Partisi (df -h)",
            description = "Mengecek penggunaan ruang penyimpanan semua partisi internal & SD card",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "df -h",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_screen_lock",
            name = "Kunci Layar (Power Key)",
            description = "Menekan tombol power virtual untuk mematikan/mengunci layar",
            category = "Sistem & Power",
            scriptType = ToolScriptType.SHELL,
            command = "input keyevent 26",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_volume_control",
            name = "Atur Volume Media",
            description = "Menaikkan atau menurunkan level volume suara Android",
            category = "Multimedia",
            scriptType = ToolScriptType.SHELL,
            command = "media volume --show --set {level}",
            parametersSchema = """{"level": "10"}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_url_browser",
            name = "Buka Link Website",
            description = "Membuka link alamat website langsung di browser default",
            category = "Web & Internet",
            scriptType = ToolScriptType.INTENT,
            command = "https://google.com",
            parametersSchema = """{"url": "https://google.com"}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_list_installed_apps",
            name = "Daftar Aplikasi Pihak Ketiga",
            description = "Melihat daftar package aplikasi pengguna yang terpasang di HP",
            category = "Aplikasi",
            scriptType = ToolScriptType.SHELL,
            command = "pm list packages -3",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_settings",
            name = "Buka Pengaturan Android",
            description = "Membuka menu utama Pengaturan (Settings) perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.INTENT,
            command = "android.settings.SETTINGS",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_ping_test",
            name = "Tes Koneksi Ping Internet",
            description = "Mengirim 3 paket ping ke Google DNS untuk menguji latency internet",
            category = "Jaringan",
            scriptType = ToolScriptType.SHELL,
            command = "ping -c 3 8.8.8.8",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_http_public_ip",
            name = "Cek Alamat IP Publik",
            description = "Memeriksa IP publik perangkat menggunakan API ipify",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        )
    )
}
