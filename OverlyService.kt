package com.phpnano.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
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
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    /** État partagé avec la vue et le panel */
    object St {
        @Volatile var enabled = true
        @Volatile var trace = true
        @Volatile var box = true
        @Volatile var name = true
        @Volatile var aim = false
        @Volatile var autoFire = false
        @Volatile var boost = true
        @Volatile var sens = 6
        @Volatile var headOffset = 14f
        @Volatile var speed = 1.7f
    }

    companion object {
        const val ACTION_START = "com.phpnano.START"
        const val ACTION_STOP = "com.phpnano.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "phpnano"
        private const val NID = 7
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var dm: DisplayMetrics

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var capThread: HandlerThread? = null
    private var capHandler: Handler? = null

    private var capW = 0
    private var capH = 0
    private var wide: android.graphics.Bitmap? = null
    private var lastTs = 0L
    private var lastAimTs = 0L

    private val detector = Detector()

    private lateinit var overlayView: TargetView
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private val refreshers = ArrayList<() -> Unit>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dm = resources.displayMetrics
        startForegroundNotif()
        addViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code == android.app.Activity.RESULT_OK && data != null && projection == null) {
            startProjection(code, data)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        releaseDisplay()
        projection?.stop()
        projection = null
        capThread?.quitSafely()
        capThread = null
        wide = null
        try { wm.removeView(overlayView) } catch (_: Throwable) {}
        try { wm.removeView(bubble) } catch (_: Throwable) {}
        try { wm.removeView(panel) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "PHP NANO", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("PHP NANO")
            .setContentText("Overlay actif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NID, n)
        }
    }

    // ------------------------------------------------------------------ capture

    private fun startProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data) ?: return
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        capThread = HandlerThread("phpnano-cap").also { it.start() }
        capHandler = Handler(capThread!!.looper)
        buildDisplay()
    }

    private fun buildDisplay() {
        releaseDisplay()
        // Boost 1.7x => capture plus petite => téléphone plus réactif
        val div = (3.0f - 1.2f * (St.speed - 1f)).coerceIn(1.6f, 3.0f)
        capW = (((dm.widthPixels / div).toInt()) / 2) * 2
        capH = (((dm.heightPixels / div).toInt()) / 2) * 2
        if (capW < 240 || capH < 240) return

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ ir -> onFrame(ir) }, capHandler)
        vd = projection?.createVirtualDisplay(
            "phpnano", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, capHandler
        )
        wide = null
    }

    private fun releaseDisplay() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { vd?.release() } catch (_: Throwable) {}
        vd = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
    }

    private fun onFrame(ir: ImageReader) {
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (!St.enabled) return
            val now = SystemClock.elapsedRealtime()
            val fps = if (St.boost) 45f else 24f
            if (now - lastTs < (1000f / fps).toLong()) return
            lastTs = now

            val bmp = toBitmap(img) ?: return
            val list = detector.detect(bmp, capW, capH, St.sens, St.headOffset)
            main.post { render(list) }
            if (St.aim || St.autoFire) aimAssist(list)
        } catch (_: Throwable) {
        } finally {
            try { img.close() } catch (_: Throwable) {}
        }
    }

    private fun toBitmap(img: Image): android.graphics.Bitmap? {
        val plane = img.planes[0] ?: return null
        val w = img.width
        val h = img.height
        val ps = plane.pixelStride
        if (ps != 4) return null
        val padCols = (plane.rowStride - ps * w) / ps
        val needW = w + padCols
        var b = wide
        if (b == null || b.width != needW || b.height != h) {
            b = android.graphics.Bitmap.createBitmap(needW, h, android.graphics.Bitmap.Config.ARGB_8888)
            wide = b
        }
        plane.buffer.rewind()
        b.copyPixelsFromBuffer(plane.buffer)
        return b
    }

    // ---------------------------------------------------------------- rendering

    private fun render(list: List<Detector.Target>) {
        if (capW == 0) return
        val s = dm.widthPixels.toFloat() / capW
        val scaled = list.map {
            Detector.Target(it.cx * s, it.headY * s, it.tagW * s, it.moving)
        }
        overlayView.setData(scaled, PointF(dm.widthPixels / 2f, dm.heightPixels * 0.5f))
    }

    /** Visée tête / tir auto via l'AccessibilityService */
    private fun aimAssist(list: List<Detector.Target>) {
        val svc = AimService.instance ?: return
        if (list.isEmpty() || capW == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAimTs < 130) return
        lastAimTs = now

        val s = dm.widthPixels.toFloat() / capW
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels * 0.5f
        val t = list.minByOrNull {
            abs(it.cx * s - cx) + abs(it.headY * s - cy) * 0.5f
        } ?: return

        val tx = t.cx * s
        val ty = t.headY * s
        val dx = (tx - cx).coerceIn(-45f, 45f)
        val dy = (ty - cy).coerceIn(-45f, 45f)

        if (St.aim) svc.drag(cx, cy, dx, dy)
        if (St.autoFire && abs(dx) + abs(dy) < 60f) svc.tap(tx, ty)
    }

    // --------------------------------------------------------------------- views

    private fun addViews() {
        // 1) Calque ESP (non tactile, plein écran)
        overlayView = TargetView(this)
        val op = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        op.gravity = Gravity.TOP or Gravity.START
        wm.addView(overlayView, op)

        // 2) Bulle ON/OFF déplaçable
        bubble = TextView(this).apply {
            text = "PHP"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00E676.toInt())
                setStroke(dp(2), 0xFF0B0C0F.toInt())
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(180)
        }
        wm.addView(bubble, bubbleLp)
        dragWithClick(bubble, bubbleLp) {
            St.enabled = !St.enabled
            bubble.text = if (St.enabled) "PHP" else "OFF"
            bubble.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (St.enabled) 0xFF00E676.toInt() else 0xFFFF1744.toInt())
                setStroke(dp(2), 0xFF0B0C0F.toInt())
            }
            overlayView.invalidate()
            refreshers.forEach { it() }
        }

        // 3) Panel flottant
        panel = buildPanel()
        panelLp = WindowManager.LayoutParams(
            dp(236),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(250)
        }
        wm.addView(panel, panelLp)
    }

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xEE0B0C0F.toInt())
                setStroke(dp(1), 0xFF00E676.toInt())
            }
        }

        // En-tête déplaçable
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PHP NANO"
            textSize = 15f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val master = chip("ON", { St.enabled }, { St.enabled = it })
        master.text = "ON/OFF"
        val close = TextView(this).apply {
            text = " ✕ "
            textSize = 15f
            setTextColor(0xFFFF1744.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { panel.visibility = View.GONE }
        }
        header.addView(title)
        header.addView(master)
        header.addView(close)
        root.addView(header)

        fun row(vararg v: View) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            v.forEach {
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.rightMargin = dp(5)
                r.addView(it, lp)
            }
            root.addView(r)
        }

        row(
            chip("TRACE", { St.trace }, { St.trace = it; overlayView.invalidate() }),
            chip("CARRÉ", { St.box }, { St.box = it; overlayView.invalidate() })
        )
        row(
            chip("PSEUDO", { St.name }, { St.name = it; overlayView.invalidate() }),
            chip("AIM TÊTE", { St.aim }, { St.aim = it })
        )
        row(
            chip("TIR AUTO", { St.autoFire }, { St.autoFire = it }),
            chip("BOOST 1.7x", { St.boost }, { St.boost = it })
        )

        root.addView(slider("SENSIBILITÉ", 1, 10, St.sens) { St.sens = it })
        root.addView(slider("HEADSHOT", 0, 40, St.headOffset.toInt()) { St.headOffset = it.toFloat() })
        root.addView(slider("VITESSE", 1.0f, 2.0f, St.speed) {
            St.speed = it
            main.post { buildDisplay() }
        })

        // Rend le panel déplaçable par l'en-tête
        header.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0f
            var dy = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = e.rawX - panelLp.x
                        dy = e.rawY - panelLp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelLp.x = (e.rawX - dx).toInt()
                        panelLp.y = (e.rawY - dy).toInt()
                        wm.updateViewLayout(panel, panelLp)
                        return true
                    }
                }
                return false
            }
        })

        return root
    }

    private fun chip(label: String, get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            typeface = Typeface.DEFAULT_BOLD
        }
        fun paint() {
            val on = get()
            tv.setTextColor(if (on) Color.BLACK else 0xFFB0BEC5.toInt())
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (on) 0xFF00E676.toInt() else 0xFF23262E.toInt())
                setStroke(dp(1), if (on) 0xFF00E676.toInt() else 0xFF3A3F4B.toInt())
            }
        }
        paint()
        tv.setOnClickListener {
            set(!get())
            paint()
            overlayView.invalidate()
            refreshers.forEach { it() }
        }
        refreshers.add { paint() }
        return tv
    }

    private fun slider(
        label: String,
        minF: Float,
        maxF: Float,
        init: Number,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val head = TextView(this).apply {
            text = "$label : ${fmt(init.toFloat())}"
            textSize = 10f
            setTextColor(0xFF90A4AE.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        val sb = SeekBar(this).apply {
            max = 100
            progress = (((init.toFloat() - minF) / (maxF - minF)) * 100f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = minF + (maxF - minF) * p / 100f
                    head.text = "$label : ${fmt(if (maxF > 10f) v else v)}"
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(head)
        box.addView(sb, LinearLayout.LayoutParams(-1, dp(30)))
        return box
    }

    private fun fmt(v: Float): String =
        if (v >= 10f) v.toInt().toString() else String.format("%.1f", v)

    private fun dragWithClick(view: View, lp: WindowManager.LayoutParams, click: () -> Unit) {
        var dx = 0f
        var dy = 0f
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - lp.x
                    dy = e.rawY - lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx - lp.x) > 6 || abs(e.rawY - dy - lp.y) > 6) moved = true
                    lp.x = (e.rawX - dx).toInt()
                    lp.y = (e.rawY - dy).toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) click()
                    // double-tap sur la bulle = ouvrir/fermer le panel
                    panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}