package com.example.screencolortrigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenColorMonitorService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var targetX = 0
    private var targetY = 0
    private var redWasPresent = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startAsForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        targetX = intent.getIntExtra(EXTRA_X, 0)
        targetY = intent.getIntExtra(EXTRA_Y, 0)
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        if (targetX !in 0 until metrics.widthPixels ||
            targetY !in 0 until metrics.heightPixels
        ) {
            updateNotification("坐标超出屏幕范围")
            stopSelf()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (error: SecurityException) {
            Log.e(TAG, "Screen capture permission was rejected", error)
            updateNotification("屏幕录制授权无效")
            stopSelf()
            return
        }
        if (projection == null) {
            updateNotification("无法启动屏幕捕获")
            stopSelf()
            return
        }

        imageThread = HandlerThread("screen-color-capture").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        ).apply {
            setOnImageAvailableListener({ reader -> inspectLatestImage(reader) }, imageHandler)
        }

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, imageHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenColorMonitor",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            imageHandler
        )
        if (virtualDisplay == null) {
            updateNotification("无法创建屏幕捕获")
            stopSelf()
            return
        }
        updateNotification("监测坐标 ($targetX, $targetY)")
    }

    private fun inspectLatestImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to acquire screen image", error)
            updateNotification("读取屏幕图像失败")
            stopSelf()
            null
        } ?: return

        image.use {
            val isRed = try {
                isTargetPixelRed(it, targetX, targetY)
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Unable to read target pixel", error)
                updateNotification("读取目标像素失败")
                stopSelf()
                false
            }

            if (isRed && !redWasPresent) {
                redWasPresent = true
                updateNotification("检测到红色，正在发送请求")
                sendPostRequest()
            } else if (!isRed) {
                redWasPresent = false
            }
        }
    }

    private fun isTargetPixelRed(image: Image, x: Int, y: Int): Boolean {
        val crop = image.cropRect
        require(x >= crop.left && x < crop.right && y >= crop.top && y < crop.bottom) {
            "Target coordinate is outside the captured image"
        }
        val plane = image.planes.firstOrNull()
            ?: throw IllegalArgumentException("Screen image has no pixel plane")
        val buffer = plane.buffer
        val pixelOffset = (y - crop.top) * plane.rowStride +
            (x - crop.left) * plane.pixelStride
        require(pixelOffset >= 0 && pixelOffset + 2 < buffer.limit()) {
            "Target pixel is outside the image buffer"
        }
        val red = buffer.get(pixelOffset).toInt() and 0xFF
        val green = buffer.get(pixelOffset + 1).toInt() and 0xFF
        val blue = buffer.get(pixelOffset + 2).toInt() and 0xFF
        return red == 255 && green == 0 && blue == 0
    }

    private fun sendPostRequest() {
        networkExecutor.execute {
            val connection = try {
                (URL(POST_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to create POST request", error)
                updateNotification("创建 HTTP 请求失败")
                return@execute
            }

            try {
                connection.outputStream.use { }
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    connection.inputStream?.close()
                    updateNotification("请求成功 (HTTP $responseCode)")
                } else {
                    connection.errorStream?.close()
                    Log.e(TAG, "POST request failed with HTTP $responseCode")
                    updateNotification("请求失败 (HTTP $responseCode)")
                }
            } catch (error: Exception) {
                Log.e(TAG, "POST request failed", error)
                updateNotification("请求失败：${error.localizedMessage ?: "网络错误"}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("正在启动屏幕监测")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenColorMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("屏幕颜色触发器")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "停止",
                    stopService
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕颜色监测",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        imageThread?.quitSafely()
        networkExecutor.shutdown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val POST_URL = "http://192.168.100.10/api/touch_event_sequence_1"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_X = "target_x"
        const val EXTRA_Y = "target_y"

        private const val ACTION_STOP = "com.example.screencolortrigger.STOP"
        private const val CHANNEL_ID = "screen_color_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenColorMonitor"
    }
}
