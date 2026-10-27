package app.qingjian.inputmethod.solve

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.qingjian.inputmethod.R

/** 截屏链路的日志标签；出问题时 adb logcat -s SolveShot 一眼定位。 */
private const val TAG = "SolveShot"

/**
 * 截屏搜题的接力站。输入法弹不了系统的录屏授权框（同 voice/MicrophonePermissionActivity 的代弹思路），
 * 拆成三截：[SolveShot] 是交接站，[SolveCaptureActivity] 是透明授权页只拿授权，
 * [SolveCaptureService] 是挂着 mediaProjection 前台服务的截屏服务。
 *
 * 授权是平台强制的，绕不过去；但**同意一次后投影挂在前台服务里待命**，
 * 之后每次截屏直接复用——不再弹授权框，键盘也不收起。状态栏常驻通知，点按可停止共享；
 * 进程被杀或重启后投影没了，下次会重新弹一次授权。
 */
object SolveShot {
    /** 授权结果在 activity 与 service 之间传递的键。 */
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"

    /** 截好的一帧回调（主线程）；服务在发起截屏时挂上，截到图（或失败）后由服务处理。 */
    @Volatile var onResult: ((Bitmap) -> Unit)? = null

    /**
     * 发起截屏那刻键盘（输入法窗口）的高度：把截图底部键盘那截裁掉，只留题目。
     * 复用授权直接截时键盘在屏上，照实传；首次授权那趟键盘会被收起，传 0（不裁）。
     */
    @Volatile var imeHeight: Int = 0
}

/** 透明授权页：弹系统的「将开始截取屏幕内容」框，授权信息交给前台服务；不进最近任务，不带出任务栈。 */
class SolveCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "这台设备不支持截屏", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult code=$resultCode data=${data != null}")
        if (requestCode != REQUEST) return
        if (resultCode == RESULT_OK && data != null) {
            // 前台服务要趁页面还在前台时启（Android 12 起不让后台应用启前台服务）；授权信息由 intent 带给服务
            startForegroundService(
                Intent(this, SolveCaptureService::class.java)
                    .putExtra(SolveShot.EXTRA_CODE, resultCode)
                    .putExtra(SolveShot.EXTRA_DATA, data),
            )
        } else {
            Toast.makeText(this, "没有拿到截屏授权", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}

/**
 * 截屏的前台服务。MediaProjection 从 Android 9 起要求挂着 mediaProjection 类型的前台服务才能建虚拟屏；
 * 这里把投影**挂住待命**：授权一次后每次截屏只是临时建虚拟屏取一帧，不再走授权页。
 * 状态栏常驻通知（点按=停止共享）；投影被系统/用户停掉时跟着撤，下次重新授权。
 */
class SolveCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameTimeout: Runnable? = null
    private var pending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHolding()
            return START_NOT_STICKY
        }
        // 通知渠道与前台服务先就位：mediaProjection 投影挂着期间系统要求一直有这个前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "AI 搜题截屏", NotificationManager.IMPORTANCE_LOW))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, holdingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, holdingNotification())
        }
        if (projection == null) {
            // 第一次：授权页带来的授权信息，建投影挂住待命
            val data = intent?.getParcelableExtra<Intent>(SolveShot.EXTRA_DATA)
            val code = intent?.getIntExtra(SolveShot.EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            if (data == null || !hold(code, data)) {
                deliver(null)
                stopHolding()
                return START_NOT_STICKY
            }
        }
        captureNow()
        return START_NOT_STICKY
    }

    /** 拿授权建投影挂住；false 是授权失效（极少：授权信息只认一次或已过期）。 */
    private fun hold(code: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return false
        val held = try {
            manager.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.d(TAG, "getMediaProjection failed", e)
            null
        } ?: return false
        held.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // 投影在别处被停了（系统清理、用户从投屏通知停）：跟着撤，下次重新授权
                    if (ready) stopHolding()
                }
            },
            main,
        )
        projection = held
        ready = true
        Log.d(TAG, "projection held, ready for reuse")
        return true
    }

    /** 从挂住的投影里截一帧：临时建虚拟屏，首帧到手就撤（投影本身留着，下次免授权）。 */
    private fun captureNow() {
        if (pending || projection == null) return
        pending = true
        val metrics = resources.displayMetrics
        val newReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        reader = newReader
        frameTimeout = Runnable {
            if (!pending) return@Runnable
            pending = false
            Log.d(TAG, "capture timeout, no frame in 3s")
            deliver(null)
            releaseFrame()
        }
        newReader.setOnImageAvailableListener({
            if (!pending) return@setOnImageAvailableListener
            val image = runCatching { newReader.acquireLatestImage() }.getOrNull()
            if (image == null) return@setOnImageAvailableListener
            pending = false
            frameTimeout?.let(main::removeCallbacks)
            Log.d(TAG, "frame ${image.width}x${image.height}")
            val bitmap = runCatching { image.toBitmap() }
                .onFailure { Log.d(TAG, "toBitmap failed", it) }
                .getOrNull()
            image.close()
            deliver(bitmap?.let(::cropIme))
            releaseFrame()
        }, main)
        display = projection?.createVirtualDisplay(
            "qingjian-solve", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, main,
        )
        // 虚拟屏没出帧（镜像失败之类）就 3 秒收摊，别把通知吊死在状态栏
        main.postDelayed(frameTimeout!!, 3000)
    }

    /** 只撤这一帧的虚拟屏与 reader；投影还挂着待命。 */
    private fun releaseFrame() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
    }

    /** 停止共享：投影撤掉、通知收掉；下次截屏会重新走一次授权页。 */
    private fun stopHolding() {
        Log.d(TAG, "stop holding")
        ready = false
        pending = false
        frameTimeout?.let(main::removeCallbacks)
        frameTimeout = null
        releaseFrame()
        runCatching { projection?.stop() }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** [bitmap] 为 null 是没截到。 */
    private fun deliver(bitmap: Bitmap?) {
        Log.d(TAG, "deliver ${bitmap?.width}x${bitmap?.height} listener=${SolveShot.onResult != null}")
        Handler(Looper.getMainLooper()).post {
            if (bitmap != null) SolveShot.onResult?.invoke(bitmap)
            else Toast.makeText(applicationContext, "没截到屏", Toast.LENGTH_SHORT).show()
        }
    }

    /** 裁掉底部键盘那截（高度是发起截屏时记的），最少留五成半，防裁过头。 */
    private fun cropIme(screen: Bitmap): Bitmap {
        val cut = SolveShot.imeHeight
        if (cut <= 0) return screen
        val keep = (screen.height - cut).coerceAtLeast(screen.height * 55 / 100)
        return Bitmap.createBitmap(screen, 0, 0, screen.width, keep)
    }

    /** Image 的行距可能比像素宽（内存对齐），照 rowStride 建图再裁齐。 */
    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmap = Bitmap.createBitmap(
            if (rowPadding == 0) width else width + rowPadding / plane.pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    /** 待命期间的通知：点按本体或「停止共享」都停掉投影。 */
    private fun holdingNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, SolveCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getText(R.string.app_name))
            .setContentText("截屏搜题待命中：屏幕共享已授权，点按停止")
            .setContentIntent(stop)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, applicationInfo.icon),
                    "停止共享",
                    stop,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // 进程要没了：投影必须撤，不然系统投屏状态吊死
        if (projection != null) stopHolding()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "solve-shot"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "app.qingjian.inputmethod.solve.STOP"

        /** 授权是否还挂着（挂着的投影能直接截，不弹授权框也不收键盘）。 */
        @Volatile
        var ready = false
            private set
    }
}
