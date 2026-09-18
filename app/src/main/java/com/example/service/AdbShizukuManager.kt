package com.example.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.example.model.ToolResult
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Handles ADB, Shizuku, and System Shell execution and permission verifications.
 */
object AdbShizukuManager {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"

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
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        // Fallback: open Play Store or browser
        try {
            val playIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SHIZUKU_MANAGER_PACKAGE")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playIntent)
        } catch (e: Exception) {
            val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    /**
     * Executes shell command locally via Runtime.exec (fallback for local Termux/ADB commands).
     */
    fun executeShell(command: String): ToolResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            process.waitFor()
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                ToolResult(
                    status = "ok",
                    result = output.toString().trim()
                )
            } else {
                ToolResult(
                    status = "error",
                    errorCode = "SHELL_EXEC_ERROR",
                    message = "Exit code $exitCode: ${errorOutput.toString().trim()}",
                    retryable = false
                )
            }
        } catch (e: Exception) {
            ToolResult(
                status = "error",
                errorCode = "SHELL_EXCEPTION",
                message = e.message ?: "Failed to execute shell command",
                retryable = false
            )
        }
    }
}
