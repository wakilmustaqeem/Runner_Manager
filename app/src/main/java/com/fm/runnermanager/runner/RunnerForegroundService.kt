package com.fm.runnermanager.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.fm.runnermanager.MainActivity

/**
 * Foreground-service lifecycle shell for the future local runner engine.
 *
 * The app currently has no production command provider: runtime signing config,
 * native PRoot payload, and GitHub registration flow are not yet wired. Therefore
 * START fails closed unless a provider has been explicitly installed in-process.
 * A process restart loses that provider and must not silently relaunch CI work.
 */
class RunnerForegroundService : Service() {
    interface CommandProvider {
        fun runtimeVerified(): Boolean
        fun commandFactory(): ProcessBuilder
    }

    companion object {
        const val ACTION_START = "com.fm.runnermanager.runner.START"
        const val ACTION_STOP = "com.fm.runnermanager.runner.STOP"
        const val ACTION_STATUS = "com.fm.runnermanager.runner.STATUS"
        const val EXTRA_STATUS = "runner_status"
        private const val EXTRA_USER_CONFIRMED = "explicit_user_confirmation"
        private const val CHANNEL_ID = "fm_runner_status"
        private const val NOTIFICATION_ID = 2401
        @Volatile private var commandProvider: CommandProvider? = null
        @Volatile private var supervisor: RunnerProcessSupervisor? = null

        /** Internal integration hook; callers must establish verified runtime and registration first. */
        fun installCommandProvider(provider: CommandProvider?) {
            commandProvider = provider
        }

        fun requestStart(context: Context, explicitUserConfirmation: Boolean) {
            require(explicitUserConfirmation) { "Explicit user confirmation is required" }
            val intent = Intent(context, RunnerForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_USER_CONFIRMED, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun requestStop(context: Context, explicitUserConfirmation: Boolean) {
            require(explicitUserConfirmation) { "Explicit user confirmation is required" }
            context.startService(
                Intent(context, RunnerForegroundService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_USER_CONFIRMED, true)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        supervisor = RunnerProcessSupervisor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (intent?.getBooleanExtra(EXTRA_USER_CONFIRMED, false) != true) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                runCatching { supervisor?.stop(explicitUserConfirmation = true) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_STATUS -> {
                val status = supervisor?.snapshot()?.status?.name ?: "UNAVAILABLE"
                intent?.putExtra(EXTRA_STATUS, status)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                // Android requires a timely foreground notification before longer work.
                startForeground(NOTIFICATION_ID, notification("Checking runner readiness…"))
                if (intent?.getBooleanExtra(EXTRA_USER_CONFIRMED, false) != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val provider = commandProvider
                if (provider == null) {
                    updateNotification("Blocked: runner runtime/registration integration is not configured")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val current = supervisor ?: RunnerProcessSupervisor().also { supervisor = it }
                val result = runCatching {
                    current.start(
                        explicitUserConfirmation = true,
                        runtimeVerified = provider::runtimeVerified,
                        commandFactory = provider::commandFactory
                    )
                }
                if (result.isFailure) {
                    updateNotification("Runner not started: readiness check failed")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                updateNotification("Runner process active")
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { supervisor?.stop(explicitUserConfirmation = true) }
        supervisor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Runner status", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FM Runner Manager")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("FM Runner Manager")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }
}
