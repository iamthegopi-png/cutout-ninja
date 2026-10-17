package com.example.cutoutninja

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sin
import kotlin.random.Random

private const val PREFS_NAME = "moto_edge60_ninja_prefs"
private const val KEY_Y_OFFSET = "y_offset_px"
private const val KEY_MIN_SEC = "min_interval_sec"
private const val ACTION_TEST_NOW = "com.example.cutoutninja.ACTION_TEST_NOW"

// ============================================================================
// 1. MAIN ACTIVITY: Permission Setup + Calibration UI
// ============================================================================
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Moto Cutout Ninja (Gyro Tilt Physics)"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }

        val subtitle = TextView(this).apply {
            text = "Tilt your phone left or right to make the ninja swing on screen!"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 0, 0, dp(28))
        }

        val savedYOffset = prefs.getInt(KEY_Y_OFFSET, 0)
        val yLabel = TextView(this).apply {
            text = "Cutout Vertical Offset: ${savedYOffset}px (0 = Auto Punch-Hole)"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }

        val ySeek = SeekBar(this).apply {
            max = 80
            progress = savedYOffset + 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val offset = p - 30
                    yLabel.text = "Cutout Vertical Offset: ${offset}px (0 = Auto Punch-Hole)"
                    prefs.edit().putInt(KEY_Y_OFFSET, offset).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val savedMinSec = prefs.getInt(KEY_MIN_SEC, 60)
        val intervalLabel = TextView(this).apply {
            text = "Drop Interval: Every $savedMinSec to ${savedMinSec + 60} seconds"
            setTextColor(Color.WHITE)
            setPadding(0, dp(20), 0, dp(4))
        }

        val intervalSeek = SeekBar(this).apply {
            max = 90
            progress = (savedMinSec - 30).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val minSec = p + 30
                    intervalLabel.text = "Drop Interval: Every $minSec to ${minSec + 60} seconds"
                    prefs.edit().putInt(KEY_MIN_SEC, minSec).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val startBtn = Button(this).apply {
            text = "Enable & Start Background Service"
            setOnClickListener { ensureOverlayPermissionAndStart(testImmediately = false) }
        }

        val testBtn = Button(this).apply {
            text = "Trigger Ninja Right Now"
            setOnClickListener { ensureOverlayPermissionAndStart(testImmediately = true) }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, NinjaOverlayService::class.java))
                Toast.makeText(this@MainActivity, "Cutout Ninja stopped", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(yLabel)
        root.addView(ySeek)
        root.addView(intervalLabel)
        root.addView(intervalSeek)
        root.addView(startBtn)
        root.addView(testBtn)
        root.addView(stopBtn)
        setContentView(root)
    }

    private fun ensureOverlayPermissionAndStart(testImmediately: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val intent = Intent(this, NinjaOverlayService::class.java).apply {
            if (testImmediately) action = ACTION_TEST_NOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!testImmediately) {
            Toast.makeText(this, "Active! Tilt phone or wait 1-2 mins.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

// ============================================================================
// 2. OVERLAY SERVICE: Sensor Management & Lifecycle
// ============================================================================
class NinjaOverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeNinjaView: NinjaAnimationView? = null
    private var currentTiltX = 0f

    private val triggerRunnable = Runnable {
        if (isScreenOn()) spawnCutoutNinja()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    mainHandler.removeCallbacksAndMessages(null)
                    unregisterSensor()
                    detachNinjaView()
                }
                Intent.ACTION_SCREEN_ON -> {
                    registerSensor()
                    scheduleNextDrop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        startSilentForegroundNotification()
        registerSensor()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        spawnCutoutNinja()
    }

    private fun registerSensor() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY || event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            currentTiltX = -event.values[0]
            activeNinjaView?.updateTilt(currentTiltX)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_NOW) {
            if (activeNinjaView != null) {
                activeNinjaView?.triggerRetract()
            } else {
                spawnCutoutNinja()
            }
        }
        return START_STICKY
    }

    private fun scheduleNextDrop() {
        mainHandler.removeCallbacks(triggerRunnable)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val minSec = prefs.getInt(KEY_MIN_SEC, 60).toLong()
        val delayMs = Random.nextLong(minSec * 1000L, (minSec + 60L) * 1000L)
        mainHandler.postDelayed(triggerRunnable, delayMs)
    }

    private fun spawnCutoutNinja() {
        if (activeNinjaView != null) return

        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userYOffsetPx = prefs.getInt(KEY_Y_OFFSET, 0)

        var cutoutTopY = (10 * density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutout = windowManager.currentWindowMetrics.windowInsets.displayCutout
            val rect = cutout?.boundingRects?.firstOrNull()
            if (rect != null) cutoutTopY = rect.centerY()
        }

        val overlayWidth = (160 * density).toInt()
        val overlayHeight = (260 * density).toInt()

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (cutoutTopY + userYOffsetPx).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val view = NinjaAnimationView(this) {
            detachNinjaView()
            if (isScreenOn()) scheduleNextDrop()
        }

        activeNinjaView = view
        windowManager.addView(view, params)
        view.startAnimationCycle()
    }

    private fun detachNinjaView() {
        activeNinjaView?.let { v ->
            v.stopAnimationCycle()
            try { windowManager.removeView(v) } catch (_: Exception) {}
            activeNinjaView = null
        }
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun startSilentForegroundNotification() {
        val channelId = "edge60_cutout_ninja"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Cutout Ninja Background", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Moto Cutout Ninja Active")
            .setContentText("Tilt phone to swing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        unregisterSensor()
        detachNinjaView()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================================
// 3. ANIMATION VIEW: Gyroscope Tilt Physics + Pendulum Swing
// ============================================================================
class NinjaAnimationView(
    context: Context,
    private val onFinished: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val strokeBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val headbandRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2E2E"); style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }

    private val headbandPath = Path()
    private val islandRect = RectF()
    private var progress = 0f
    private var lastFrameMs = 0L
    private var animator: ValueAnimator? = null
    private var isRetractingEarly = false
    private var targetTiltX = 0f
    private var smoothedTiltX = 0f

    fun startAnimationCycle() {
        isRetractingEarly = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7200L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameMs >= 16L || it.animatedFraction == 1f) {
                    lastFrameMs = now
                    progress = if (isRetractingEarly) {
                        progress + 0.08f
                    } else {
                        it.animatedValue as Float
                    }

                    smoothedTiltX += (targetTiltX - smoothedTiltX) * 0.2f

                    if (progress >= 1f) {
                        animator?.cancel()
                        onFinished()
                    } else {
                        invalidate()
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    fun updateTilt(tiltX: Float) {
        targetTiltX = tiltX * dp(12f)
    }

    fun triggerRetract() {
        if (progress < 0.85f) {
            isRetractingEarly = true
            progress = maxOf(progress, 0.82f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            triggerRetract()
            return true
        }
        return super.onTouchEvent(event)
    }

    fun stopAnimationCycle() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val maxRopeY = height * 0.82f

        val collarScale = when {
            progress < 0.08f -> progress / 0.08f
            progress > 0.92f -> (1f - progress) / 0.08f
            else -> 1f
        }.coerceIn(0f, 1f)

        val pillHalfW = dp(14f + 8f * collarScale)
        val pillH = dp(14f + 6f * collarScale)
        islandRect.set(cx - pillHalfW, 0f, cx + pillHalfW, pillH)
        canvas.drawRoundRect(islandRect, dp(12f), dp(12f), fillBlack)

        val ropeFraction = when {
            progress < 0.16f -> progress / 0.16f
            progress > 0.88f -> (1f - progress) / 0.12f
            else -> 1f
        }.coerceIn(0f, 1f)

        val naturalSway = if (progress in 0.16f..0.88f) sin(progress * 16f) * dp(3f) else 0f
        val totalSwayX = naturalSway + smoothedTiltX

        val ropeTipX = cx + totalSwayX
        val ropeTipY = pillH / 2f + (maxRopeY * ropeFraction)

        strokeBlack.strokeWidth = dp(2.4f)
        canvas.drawLine(cx, pillH / 2f, ropeTipX, ropeTipY, strokeBlack)

        if (ropeFraction > 0.05f) {
            canvas.drawCircle(ropeTipX, ropeTipY, dp(3f), fillBlack)
        }

        if (progress in 0.16f..0.88f) {
            val slideFrac = when {
                progress < 0.38f -> (progress - 0.16f) / 0.22f
                progress > 0.74f -> 1f - ((progress - 0.74f) / 0.14f)
                else -> 1f
            }.coerceIn(0f, 1f)

            val ninjaY = pillH + (maxRopeY * 0.72f) * slideFrac
            drawStickNinja(canvas, ropeTipX, ninjaY, progress, smoothedTiltX)
        }
    }

    private fun drawStickNinja(canvas: Canvas, ropeX: Float, y: Float, t: Float, tiltX: Float) {
        val headR = dp(9.5f)
        val side = if (tiltX > dp(1.5f)) 1f else if (tiltX < -dp(1.5f)) -1f else -1f
        val headX = ropeX + side * dp(8.5f)
        val headY = y - dp(22f)

        canvas.drawCircle(headX, headY, headR, fillBlack)

        val tiltVelocityEffect = tiltX * 0.5f
        val wave = sin(t * 50f) * dp(4.5f) - tiltVelocityEffect
        headbandPath.reset()
        headbandPath.moveTo(headX - headR, headY - dp(3f))
        headbandPath.lineTo(headX + headR, headY - dp(3f))
        val tailDir = -side
        headbandPath.quadTo(
            headX + tailDir * (headR + dp(9f)),
            headY - dp(6f) + wave,
            headX + tailDir * (headR + dp(18f) - tiltVelocityEffect),
            headY - dp(1f) - wave
        )
        canvas.drawPath(headbandPath, headbandRed)

        canvas.drawCircle(headX - dp(2.5f), headY, dp(1.8f), eyeWhite)
        canvas.drawCircle(headX + dp(3.0f), headY, dp(1.8f), eyeWhite)

        strokeBlack.strokeWidth = dp(4.5f)
        val shoulderY = headY + headR
        val hipX = headX + side * dp(2f)
        val hipY = shoulderY + dp(19f)

        canvas.drawLine(headX, shoulderY, hipX, hipY, strokeBlack)

        strokeBlack.strokeWidth = dp(3.5f)
        canvas.drawLine(headX, shoulderY + dp(3f), ropeX, shoulderY - dp(3f), strokeBlack)
        canvas.drawLine(headX, shoulderY + dp(8f), ropeX, shoulderY + dp(7f), strokeBlack)

        val legSway = tiltX * 0.4f
        canvas.drawLine(hipX, hipY, ropeX, hipY + dp(11f), strokeBlack)
        canvas.drawLine(hipX, hipY, hipX + side * dp(8f) + legSway, hipY + dp(12f), strokeBlack)
    }
}
