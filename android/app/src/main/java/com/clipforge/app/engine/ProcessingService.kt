package com.clipforge.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clipforge.app.ClipforgeApp
import com.clipforge.app.MainActivity
import com.clipforge.app.R
import com.clipforge.app.data.JobRow

/**
 * Foreground service that keeps the process alive while projects render, with a progress
 * notification. The work itself runs on the engine's worker thread.
 */
class ProcessingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as ClipforgeApp).engine
        val job = engine.currentJobId?.let { engine.db.getJob(it) }
        val notification = buildNotification(this, job)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            // The system refused foreground status (e.g. started from the background). Work
            // continues on the engine thread while the app process is alive.
            stopSelf()
            return START_NOT_STICKY
        }
        // Also covers a start request that arrives after very short work already finished.
        if (!engine.isBusy) stopNow()
        return START_NOT_STICKY
    }

    /** Android 15+: the system's daily limit for this service type was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val engine = (application as ClipforgeApp).engine
        engine.currentJobId?.let { engine.cancel(it) }
        stopNow()
    }

    companion object {
        const val CHANNEL_ID = "processing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.clipforge.app.STOP"

        @Volatile private var running: ProcessingService? = null

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_processing),
                        NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        fun buildNotification(context: Context, job: JobRow?): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(job?.name ?: context.getString(R.string.notification_preparing))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (job != null) {
                b.setContentText(stageLabel(job.stage) + " · " + job.progress.toInt() + "%")
                b.setProgress(100, job.progress.toInt(), false)
            } else {
                b.setProgress(0, 0, true)
            }
            return b.build()
        }

        fun stageLabel(stage: String): String = when (stage) {
            "queued" -> "Queued"
            "inspecting" -> "Inspecting"
            "detecting_scenes" -> "Detecting scenes"
            "planning" -> "Planning clips"
            "rendering" -> "Rendering clips"
            "signing" -> "Signing manifest"
            "completed" -> "Completed"
            "failed" -> "Failed"
            "cancelled" -> "Cancelled"
            else -> stage.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** Connects the engine to this service. */
    class Controller(private val context: Context) : JobEngine.ServiceController {
        override fun onWorkStarted() {
            ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
        }

        override fun onProgress(job: JobRow) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) {
                nm.notify(NOTIFICATION_ID, buildNotification(context, job))
            }
        }

        override fun onWorkFinished() {
            Handler(Looper.getMainLooper()).post {
                val service = running
                if (service != null) {
                    val engine = (service.application as ClipforgeApp).engine
                    if (!engine.isBusy) service.stopNow()
                }
            }
        }
    }
}
