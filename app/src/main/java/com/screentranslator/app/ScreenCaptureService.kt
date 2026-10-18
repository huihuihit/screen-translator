package com.screentranslator.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 화면 캡처를 유지하면서 떠 있는 '번역' 버튼을 보여주는 포그라운드 서비스.
 * 버튼을 누르면 그 순간의 화면을 번역해 위에 겹쳐 보여준다.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val ACTION_START = "com.screentranslator.app.START"
        private const val ACTION_STOP = "com.screentranslator.app.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private val engine by lazy { TranslationEngine(applicationContext) }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var reader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    // 가장 최근 화면 프레임. 이미지 스레드와 메인 스레드가 함께 쓰므로 frameLock으로 보호한다.
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var latestImageReader: ImageReader? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultView: View? = null
    private var busy = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 사용자가 상태 표시줄에서 화면 공유를 끝낸 경우 등
            mainHandler.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> if (projection == null) startCapture(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        // 안드로이드 14부터는 화면 캡처 권한을 쓰기 전에 포그라운드 서비스가 먼저 시작되어 있어야 한다.
        startInForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = data?.let {
            runCatching { manager.getMediaProjection(resultCode, it) }.getOrNull()
        }
        if (mediaProjection == null) {
            toast("화면 캡처 권한을 받지 못했어요. 앱에서 다시 시작해 주세요.")
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        val thread = HandlerThread("screen-capture").also { it.start() }
        imageThread = thread
        imageHandler = Handler(thread.looper)

        val metrics = screenMetrics()
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val imageReader = createReader(captureWidth, captureHeight)
        reader = imageReader
        // 안드로이드 14부터 한 번 받은 권한으로 가상 화면은 한 번만 만들 수 있어서, 계속 켜 둔다.
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenTranslator",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            imageHandler,
        )

        showBubble()
        isRunning = true
    }

    private fun startInForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "화면 번역", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("화면 번역 대기 중")
            .setContentText("떠 있는 '번역' 버튼을 누르면 지금 화면을 번역해요.")
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                latestImage?.close()
                latestImage = image
                latestImageReader = r
            }
        }, imageHandler)
        return imageReader
    }

    /** 가장 최근 프레임을 비트맵으로 복사한다. */
    private fun grabBitmap(): Bitmap? = synchronized(frameLock) {
        val image = latestImage ?: return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // 마지막 줄 여백이 없는 버퍼도 있어서, 모자라면 넉넉한 버퍼로 옮겨 담는다.
        val source = plane.buffer.duplicate().apply { rewind() }
        val needed = rowStride * image.height
        val buffer = if (source.remaining() >= needed) {
            source
        } else {
            ByteBuffer.allocate(needed).apply {
                put(source)
                rewind()
            }
        }

        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }
    }

    /** 캡처가 막힌 보안 화면(시크릿 모드, 금융 앱 등)은 온통 검은색으로 찍힌다. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val stepsX = 24
        val stepsY = 48
        for (i in 0 until stepsX) {
            for (j in 0 until stepsY) {
                val pixel = bitmap.getPixel(
                    (bitmap.width - 1) * i / (stepsX - 1),
                    (bitmap.height - 1) * j / (stepsY - 1),
                )
                if (Color.red(pixel) > 10 || Color.green(pixel) > 10 || Color.blue(pixel) > 10) return false
            }
        }
        return true
    }

    private fun onBubbleTapped() {
        val view = bubble ?: return
        if (busy) return
        busy = true
        removeResult()
        // 버튼이 번역 대상 화면에 찍히지 않게 잠깐 숨긴다.
        view.visibility = View.INVISIBLE
        scope.launch {
            try {
                delay(300)
                val bitmap = withContext(Dispatchers.Default) { grabBitmap() }
                view.visibility = View.VISIBLE
                setBubbleBusy(true)
                if (bitmap == null) {
                    toast("화면을 아직 못 가져왔어요. 잠시 후 다시 눌러 주세요.")
                    return@launch
                }
                if (withContext(Dispatchers.Default) { isBlank(bitmap) }) {
                    bitmap.recycle()
                    toast("보안 화면이라 번역할 수 없어요. 이 화면은 앱이 캡처를 막아 두었어요.")
                    return@launch
                }
                val result = engine.translateScreen(bitmap, Prefs.settings(this@ScreenCaptureService))
                bitmap.recycle()
                if (result.items.isEmpty()) {
                    toast("번역할 글자를 찾지 못했어요.")
                } else {
                    showResult(result)
                }
            } catch (e: ModelNotDownloadedException) {
                val name = Prefs.SOURCE_LANGUAGES[e.language] ?: "한국어"
                val online = if (e.onlineProblems.isEmpty()) "" else "온라인 번역 실패(${e.onlineProblems.joinToString(", ")}). "
                toast("$online$name 기기 안 번역 모델이 없어요. 앱을 열어 번역 모델을 받아 두면 인터넷 없이도 번역해요.")
            } catch (e: Exception) {
                toast("번역하지 못했어요: ${e.message}")
            } finally {
                view.visibility = View.VISIBLE
                setBubbleBusy(false)
                busy = false
            }
        }
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val view = TextView(this).apply {
            text = "번역"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(26, 115, 232))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 6 * density
        }
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = captureWidth - size - (12 * density).toInt()
            y = captureHeight / 3
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            toast("화면 번역을 끝냈어요.")
            stopSelf()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPress, longPressTimeout * 2)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!moved && !longPressed) onBubbleTapped()
                }
                MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPress)
            }
            true
        }

        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    private fun setBubbleBusy(isBusy: Boolean) {
        bubble?.apply {
            text = if (isBusy) "…" else "번역"
            alpha = if (isBusy) 0.7f else 1f
        }
    }

    private fun showResult(result: TranslationEngine.Result) {
        removeResult()
        val view = TranslationOverlayView(this, result.items, result.engine) { removeResult() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
            }
        }
        windowManager.addView(view, params)
        resultView = view
    }

    private fun removeResult() {
        resultView?.let { runCatching { windowManager.removeView(it) } }
        resultView = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        val metrics = screenMetrics()
        if (metrics.widthPixels == captureWidth && metrics.heightPixels == captureHeight) return

        // 화면이 회전하면 캡처 크기를 새 화면에 맞춘다.
        removeResult()
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val oldReader = reader
        val newReader = createReader(captureWidth, captureHeight)
        display.resize(captureWidth, captureHeight, metrics.densityDpi)
        display.surface = newReader.surface
        reader = newReader
        imageHandler?.post {
            synchronized(frameLock) {
                if (latestImageReader === oldReader) {
                    latestImage?.close()
                    latestImage = null
                    latestImageReader = null
                }
            }
            oldReader?.close()
        }

        val view = bubble ?: return
        val params = bubbleParams ?: return
        params.x = params.x.coerceIn(0, (captureWidth - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (captureHeight - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun screenMetrics(): DisplayMetrics {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        removeResult()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null

        virtualDisplay?.release()
        virtualDisplay = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null

        val handler = imageHandler
        val thread = imageThread
        val oldReader = reader
        reader = null
        if (handler != null) {
            handler.post {
                synchronized(frameLock) {
                    latestImage?.close()
                    latestImage = null
                    latestImageReader = null
                }
                oldReader?.close()
                thread?.quitSafely()
            }
        } else {
            oldReader?.close()
        }
        engine.close()
        super.onDestroy()
    }
}
