package net.njcbz.na2flac

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ConverterService : Service() {

    data class ProgressUpdate(
        val current: Int = 0,
        val total: Int = 0,
        val fileName: String = "",
        val isFinished: Boolean = false,
        val result: Converter.ConvertResult? = null
    )

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var convertJob: Job? = null

    companion object {
        const val CHANNEL_ID = "ConverterServiceChannel"
        const val NOTIFICATION_ID = 1

        private val _progressFlow = MutableStateFlow(ProgressUpdate())
        val progressFlow: StateFlow<ProgressUpdate> = _progressFlow.asStateFlow()

        var isRunning = false
            private set

        fun start(context: Context, inputUri: Uri, outputUri: Uri) {
            val intent = Intent(context, ConverterService::class.java).apply {
                putExtra("inputUri", inputUri)
                putExtra("outputUri", outputUri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ConverterService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Stop service when app is swiped away
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputUri = intent?.getParcelableExtra<Uri>("inputUri")
        val outputUri = intent?.getParcelableExtra<Uri>("outputUri")

        if (inputUri != null && outputUri != null) {
            val notification = createNotification("Initializing...", 0, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            convertJob = serviceScope.launch {
                try {
                    _progressFlow.value = ProgressUpdate()
                    val scanResult = Converter.scan(this@ConverterService, inputUri)
                    val binDir = BinaryManager.setup(this@ConverterService)
                    
                    var lastUpdate = 0L
                    val finalResult = Converter.convert(
                        context = this@ConverterService,
                        binDir = binDir,
                        scanResult = scanResult,
                        outputRootUri = outputUri,
                        onProgress = { current, total, fileName ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 100 || current == total) {
                                updateNotification("($current/$total) $fileName", current, total)
                                _progressFlow.value = ProgressUpdate(current, total, fileName)
                                lastUpdate = now
                            }
                        }
                    )
                    _progressFlow.value = ProgressUpdate(isFinished = true, result = finalResult)
                } catch (e: Exception) {
                    android.util.Log.e("NA2FLAC", "Service conversion error", e)
                } finally {
                    isRunning = false
                    stopForeground(true)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        convertJob?.cancel()
        serviceScope.cancel()
    }

    private fun createNotification(content: String, progress: Int, total: Int): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NA2FLAC Converting")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher_foreground) // Use foreground for notification icon
            .setContentIntent(pendingIntent)
            .setProgress(total, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(content: String, progress: Int, total: Int) {
        val notification = createNotification(content, progress, total)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Converter Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows file conversion progress"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
