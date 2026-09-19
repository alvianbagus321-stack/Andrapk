package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.JarvisApp
import com.example.model.ToolResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Handles ADB, Shizuku, Termux Services, Termux:API, and System Shell execution.
 * Enables JARVIS AI to autonomously leverage Termux environment & background services.
 */
object AdbShizukuManager {

    private const val TAG = "AdbShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_API_PACKAGE = "com.termux.api"

    /**
     * Checks if Shizuku Manager is installed on device.
     */
    fun isShizukuInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val packages = listOf(SHIZUKU_PACKAGE, SHIZUKU_MANAGER_PACKAGE)
        return packages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Checks if Termux is installed on device.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Checks if Termux:API addon is installed on device.
     */
    fun isTermuxApiInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_API_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    fun openShizukuManager(context: Context) {
        val packages = listOf(SHIZUKU_MANAGER_PACKAGE, SHIZUKU_PACKAGE)
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        try {
            val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_MANAGER_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    fun openTermux(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            try {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TERMUX_PACKAGE")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(playIntent)
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
        }
    }

    /**
     * Executes shell command on Android with Termux / Linux environment.
     */
    fun executeShell(command: String, workingDir: File? = null): ToolResult {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            return ToolResult("error", message = "Perintah shell tidak boleh kosong")
        }

        return try {
            val context = JarvisApp.instance
            val scriptsDir = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
            val workDir = workingDir ?: scriptsDir

            val termuxPy3 = File("/data/data/com.termux/files/usr/bin/python3")
            val termuxPy = File("/data/data/com.termux/files/usr/bin/python")

            var processedCmd = cleanCmd
            if (processedCmd.startsWith("python3 ") || processedCmd == "python3" || processedCmd.startsWith("python ") || processedCmd == "python") {
                val pyBinaryPath = when {
                    termuxPy3.exists() -> termuxPy3.absolutePath
                    termuxPy.exists() -> termuxPy.absolutePath
                    else -> null
                }
                if (pyBinaryPath != null) {
                    processedCmd = if (processedCmd.startsWith("python3")) {
                        processedCmd.replaceFirst("python3", pyBinaryPath)
                    } else {
                        processedCmd.replaceFirst("python", pyBinaryPath)
                    }
                }
            }

            val pathEnv = listOf(
                "/data/data/com.termux/files/usr/bin",
                "/data/data/com.termux/files/usr/bin/applets",
                "/system/bin",
                "/system/xbin",
                "/vendor/bin",
                "/sbin",
                "/product/bin",
                "/apex/com.android.runtime/bin",
                "/apex/com.android.art/bin"
            ).joinToString(":")

            val builder = ProcessBuilder("sh", "-c", processedCmd)
                .directory(workDir)
                .redirectErrorStream(true)

            val env = builder.environment()
            env["PATH"] = pathEnv + ":" + (env["PATH"] ?: "")
            env["HOME"] = "/data/data/com.termux/files/home"
            env["PREFIX"] = "/data/data/com.termux/files/usr"
            env["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["TERM"] = "xterm-256color"

            val process = builder.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > 20000) {
                    output.appendLine("... [Output terminal dipotong untuk kestabilan]")
                    break
                }
            }

            val finished = process.waitFor(25, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ToolResult("error", message = "Perintah timeout setelah 25 detik.")
            }

            val exitCode = process.exitValue()
            val resultText = output.toString().trim()

            if (exitCode == 0) {
                val finalOutput = if (resultText.isNotBlank()) resultText else "(Perintah sukses dieksekusi tanpa output, exit code: 0)"
                ToolResult(status = "ok", result = finalOutput)
            } else {
                val isPyNotFound = resultText.contains("python: inaccessible or not found", ignoreCase = true) ||
                        resultText.contains("python3: inaccessible or not found", ignoreCase = true) ||
                        (exitCode == 127 && cleanCmd.contains("python"))

                val customErrMsg = if (isPyNotFound) {
                    "❌ PERINTAH PYTHON TIDAK DITEMUKAN / DITOLAK SISTEM (Exit Code $exitCode)\n\n" +
                    "Cara Mengatasi:\n" +
                    "1. Buka aplikasi Termux lalu ketik: pkg update && pkg install python\n" +
                    "2. Atau gunakan tool 'termux_python' / 'termux_command' bawaan JARVIS.\n\n" +
                    "Detail Error: $resultText"
                } else {
                    "Perintah selesai dengan kode $exitCode:\n$resultText"
                }

                ToolResult(
                    status = if (resultText.isNotBlank()) "ok" else "error",
                    result = if (resultText.isNotBlank()) resultText else null,
                    errorCode = "SHELL_EXIT_$exitCode",
                    message = customErrMsg
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution exception: ${e.message}", e)
            ToolResult(
                status = "error",
                errorCode = "SHELL_EXCEPTION",
                message = "Gagal menjalankan shell: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * Termux Service Manager: Controls background daemon, services, and Python agent process.
     */
    fun manageTermuxService(action: String, serviceName: String = "jarvis_agent"): ToolResult {
        val context = JarvisApp.instance
        val act = action.lowercase().trim()
        val sName = serviceName.trim().ifBlank { "jarvis_agent" }

        return when (act) {
            "status", "check" -> {
                val statusCmd = "ps aux | grep -E 'python|node|termux|ssh|agent' | grep -v grep || ps | grep -E 'python|agent'"
                val res = executeShell(statusCmd)
                if (res.status == "ok" && !res.result.isNullOrBlank() && !res.result.contains("Exit code")) {
                    ToolResult("ok", result = "🟢 Termux Service Status:\n${res.result}")
                } else {
                    ToolResult("ok", result = "ℹ️ Status Termux Service:\nTidak ada background service python/agent yang sedang aktif saat ini.")
                }
            }

            "start", "run_agent", "up" -> {
                // If Termux app is installed, also send RunCommand Intent for background execution
                val commandStr = "cd /data/data/com.termux/files/home 2>/dev/null || true; nohup python3 agent.py > agent.log 2>&1 & echo 'JARVIS Agent Service started in background (PID: '$!')'"
                val shellRes = executeShell(commandStr)
                if (isTermuxInstalled(context)) {
                    sendTermuxRunCommandIntent(context, "/data/data/com.termux/files/usr/bin/python3", arrayOf("agent.py"))
                }
                ToolResult("ok", result = "🚀 Memulai Termux Service '$sName':\n${shellRes.result ?: shellRes.message ?: "Service diluncurkan di background."}")
            }

            "stop", "down", "kill" -> {
                val killCmd = "pkill -f '$sName' || pkill -f 'agent.py' || echo 'No active service found matching $sName'"
                val res = executeShell(killCmd)
                ToolResult("ok", result = "🛑 Menghentikan Termux Service '$sName':\n${res.result ?: res.message ?: "Service dihentikan."}")
            }

            "restart" -> {
                val restartCmd = "pkill -f '$sName' 2>/dev/null || true; sleep 1; nohup python3 agent.py > agent.log 2>&1 & echo 'Service restarted'"
                val res = executeShell(restartCmd)
                ToolResult("ok", result = "🔄 Restart Termux Service '$sName':\n${res.result ?: res.message ?: "Service berhasil di-restart."}")
            }

            "list" -> {
                val listCmd = "ls -la /data/data/com.termux/files/usr/var/service 2>/dev/null || echo 'Direktori service: Tidak ada service runit khusus terdaftar. Anda dapat menjalankan service custom via start/run_agent.'"
                val res = executeShell(listCmd)
                ToolResult("ok", result = "📋 Daftar Termux Services:\n${res.result ?: res.message}")
            }

            else -> {
                ToolResult("error", message = "Aksi service '$action' tidak dikenali. Pilih: status, start, stop, restart, list, run_agent")
            }
        }
    }

    /**
     * Termux:API Unified Runner
     */
    fun executeTermuxApi(command: String, args: String = ""): ToolResult {
        val cleanCmd = command.lowercase().trim().removePrefix("termux-")
        val fullApiCmd = if (args.isNotBlank()) "termux-$cleanCmd $args" else "termux-$cleanCmd"

        val res = executeShell(fullApiCmd)
        if (res.status == "ok" && !res.result.isNullOrBlank() && !res.result.contains("not found")) {
            return ToolResult("ok", result = "📱 Termux:API ($cleanCmd):\n${res.result}")
        }

        // Graceful fallback for essential features if termux-api binary is not in PATH
        val context = JarvisApp.instance
        return kotlinx.coroutines.runBlocking {
            when (cleanCmd) {
                "battery", "battery-status" -> ToolManager.executeCustomLogic(context, "battery", org.json.JSONObject())
                "vibrate" -> {
                    val duration = try { args.trim().split(" ").last().toLong() } catch (_: Exception) { 300L }
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    vibrator?.vibrate(duration)
                    ToolResult("ok", result = "📳 Perangkat digetarkan selama ${duration}ms")
                }
                "torch" -> {
                    val enable = args.contains("on", ignoreCase = true) || args.contains("1")
                    val params = org.json.JSONObject().put("enable", enable)
                    ToolManager.executeCustomLogic(context, "flashlight_toggle", params)
                }
                "toast" -> {
                    val message = args.ifBlank { "Pesan dari JARVIS Termux" }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    ToolResult("ok", result = "💬 Toast ditampilkan: \"$message\"")
                }
                "clipboard-get" -> ToolManager.executeCustomLogic(context, "clipboard_read", org.json.JSONObject())
                "clipboard-set" -> {
                    val params = org.json.JSONObject().put("text", args)
                    ToolManager.executeCustomLogic(context, "clipboard_write", params)
                }
                "notification" -> {
                    val params = org.json.JSONObject().put("title", "Termux API").put("message", args)
                    ToolManager.executeCustomLogic(context, "send_notification", params)
                }
                else -> res
            }
        }
    }

    /**
     * Termux Package Manager Tool (pkg install, update, list, search)
     */
    fun executeTermuxPkg(action: String, packageName: String = ""): ToolResult {
        val act = action.lowercase().trim()
        val pkg = packageName.trim()

        val cmd = when (act) {
            "install", "add" -> {
                if (pkg.isBlank()) return ToolResult("error", message = "Nama package tidak boleh kosong untuk install")
                "pkg install -y $pkg"
            }
            "update", "upgrade" -> "pkg update -y"
            "list", "list-installed" -> "pkg list-installed"
            "search", "find" -> {
                if (pkg.isBlank()) return ToolResult("error", message = "Keyword pencarian package tidak boleh kosong")
                "pkg search $pkg"
            }
            "uninstall", "remove" -> {
                if (pkg.isBlank()) return ToolResult("error", message = "Nama package tidak boleh kosong untuk uninstall")
                "pkg uninstall -y $pkg"
            }
            else -> "pkg $act $pkg"
        }

        return executeShell(cmd)
    }

    /**
     * Direct Python Execution in Termux
     */
    fun executeTermuxPython(code: String, filename: String = "script.py"): ToolResult {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            return ToolResult("error", message = "Kode Python tidak boleh kosong")
        }

        val context = JarvisApp.instance
        val scriptsDir = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
        val scriptFile = File(scriptsDir, filename.ifBlank { "inline_${System.currentTimeMillis()}.py" })

        return try {
            scriptFile.writeText(cleanCode)
            scriptFile.setExecutable(true)
            executeShell("python3 \"${scriptFile.absolutePath}\"", scriptsDir)
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menjalankan script Python: ${e.message}")
        }
    }

    /**
     * Termux File Operations (read, write, list, delete, mkdir)
     */
    fun executeTermuxFile(action: String, path: String, content: String = ""): ToolResult {
        val act = action.lowercase().trim()
        val targetPath = path.trim()
        if (targetPath.isBlank()) {
            return ToolResult("error", message = "Path file/direktori tidak boleh kosong")
        }

        return when (act) {
            "read", "cat" -> executeShell("cat \"$targetPath\"")
            "list", "ls" -> executeShell("ls -la \"$targetPath\"")
            "mkdir" -> executeShell("mkdir -p \"$targetPath\" && echo 'Direktori dibuat: $targetPath'")
            "delete", "rm" -> executeShell("rm -rf \"$targetPath\" && echo 'File/direktori dihapus: $targetPath'")
            "write", "save" -> {
                val context = JarvisApp.instance
                try {
                    val file = if (targetPath.startsWith("/")) File(targetPath) else File(context.filesDir, targetPath)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    ToolResult("ok", result = "📁 Berhasil menyimpan konten (${content.length} karakter) ke file: ${file.absolutePath}")
                } catch (e: Exception) {
                    executeShell("cat << 'EOF' > \"$targetPath\"\n$content\nEOF\necho 'File disimpan via shell'")
                }
            }
            else -> ToolResult("error", message = "Aksi file '$action' tidak dikenali. Pilih: read, write, list, delete, mkdir")
        }
    }

    /**
     * Sends execution request directly to Termux via Intent broadcast
     */
    fun sendTermuxRunCommandIntent(context: Context, executablePath: String, arguments: Array<String> = emptyArray()) {
        try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", executablePath)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send Termux RUN_COMMAND intent: ${e.message}")
        }
    }
}
