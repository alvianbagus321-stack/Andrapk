package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.JarvisApp
import com.example.MainActivity

class JarvisCompanionService : Service() {

    companion object {
        private const val TAG = "JarvisCompanionService"
        const val CHANNEL_ID = "jarvis_companion_service_channel"
        const val NOTIFICATION_ID = 4242

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_START_MEDIA_PROJECTION = "com.example.action.START_MEDIA_PROJECTION"
        const val ACTION_STOP_MEDIA_PROJECTION = "com.example.action.STOP_MEDIA_PROJECTION"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.action.UPDATE_NOTIFICATION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context) {
            val intent = Intent(context, JarvisCompanionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateNotification(context: Context) {
            val intent = Intent(context, JarvisCompanionService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisCompanionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startMediaProjection(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, JarvisCompanionService::class.java).apply {
                action = ACTION_START_MEDIA_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopMediaProjection(context: Context) {
            val intent = Intent(context, JarvisCompanionService::class.java).apply {
                action = ACTION_STOP_MEDIA_PROJECTION
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "JarvisCompanionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                JarvisApp.repository.stopServer()
                ScreenshotManager.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_MEDIA_PROJECTION -> {
                ScreenshotManager.release()
                if (!JarvisApp.repository.isServerRunning.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                } else {
                    val notification = buildForegroundNotification("Local HTTP Server Running • Standby")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        try {
                            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        } catch (e: Exception) {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    return START_STICKY
                }
            }

            ACTION_START_MEDIA_PROJECTION -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                val notification = buildForegroundNotification("Screen Share & Vision Active • Agent Ready")

                // Step 1: Start FGS with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION BEFORE calling getMediaProjection
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    try {
                        startForeground(NOTIFICATION_ID, notification, fgsType)
                    } catch (e: Exception) {
                        Log.w(TAG, "startForeground with MEDIA_PROJECTION failed, falling back", e)
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // Step 2: Acquire MediaProjection within the active FGS context
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    try {
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val projection = projectionManager.getMediaProjection(resultCode, resultData)
                        if (projection != null) {
                            ScreenshotManager.setMediaProjection(this, projection)
                            Log.i(TAG, "MediaProjection acquired and initialized in FGS")
                        } else {
                            Log.e(TAG, "getMediaProjection returned null")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get MediaProjection in service: ${e.message}", e)
                    }
                }

                JarvisApp.repository.startServer()
                return START_STICKY
            }

            ACTION_UPDATE_NOTIFICATION -> {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildForegroundNotification())
                return START_STICKY
            }

            else -> {
                // ACTION_START
                val isHotwordOn = com.example.service.JarvisHotwordManager.isHotwordEnabled.value
                val notification = buildForegroundNotification(
                    if (isHotwordOn) "Asisten Suara 'Jarvis' Aktif di Latar Belakang • Siap Menerima Perintah"
                    else "Local HTTP Server Running on 127.0.0.1:8765"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    var fgsType = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        if (ScreenshotManager.isMediaProjectionActive.value) {
                            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                        if (isHotwordOn) {
                            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                    } else {
                        if (ScreenshotManager.isMediaProjectionActive.value) {
                            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                    }
                    try {
                        startForeground(NOTIFICATION_ID, notification, fgsType)
                    } catch (e: Exception) {
                        Log.w(TAG, "startForeground with type failed, falling back", e)
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                JarvisApp.repository.startServer()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        JarvisApp.repository.stopServer()
        ScreenshotManager.release()
        com.example.service.JarvisHotwordManager.stop(this)
        Log.i(TAG, "JarvisCompanionService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Companion Background Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local HTTP server, voice assistant, and accessibility agent alive in background"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isHotwordOn = com.example.service.JarvisHotwordManager.isHotwordEnabled.value
        val actualText = contentText ?: if (isHotwordOn) {
            "Asisten Suara 'Jarvis' Aktif di Latar Belakang • Katakan 'Jarvis [perintah]'"
        } else {
            "Local server running on 127.0.0.1:8765 • Ready for Termux Agent"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("JARVIS AI Companion Online")
            .setContentText(actualText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
