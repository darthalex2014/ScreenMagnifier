package com.allicex.magnifier

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

data class MagState(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val areaL: Int, val areaT: Int, val areaR: Int, val areaB: Int
)

class MagnifierService : Service() {

    companion object {
        private val instances = mutableListOf<MagnifierService>()
        val isRunning get() = instances.isNotEmpty()

        fun getCount() = instances.size

        fun stopAll() {
            instances.toList().forEach { it.stopSelf() }
        }

        fun getAllStates(): List<MagState> {
            return instances.mapNotNull { it.getState() }
        }
    }

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var opacity = 1.0f

    private lateinit var wm: WindowManager
    private var scrW = 0
    private var scrH = 0
    private var scrD = 0

    private var rootView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var ivMag: ImageView? = null
    private var topBar: LinearLayout? = null
    private var expandBtn: View? = null

    private var iconView: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var selOverlay: View? = null

    private var areaL = 0
    private var areaT = 0
    private var areaR = 0
    private var areaB = 0
    private var hasArea = false

    private var savedX = 0
    private var savedY = 0
    private var mode = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instances.add(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        scrW = m.widthPixels
        scrH = m.heightPixels
        scrD = m.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1, buildNotif())

        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        opacity = intent.getFloatExtra("opacity", 1.0f)

        // Preset data
        val presetX = intent.getIntExtra("presetX", -1)
        val presetY = intent.getIntExtra("presetY", -1)
        val presetW = intent.getIntExtra("presetW", -1)
        val presetH = intent.getIntExtra("presetH", -1)
        areaL = intent.getIntExtra("areaL", 0)
        areaT = intent.getIntExtra("areaT", 0)
        areaR = intent.getIntExtra("areaR", 0)
        areaB = intent.getIntExtra("areaB", 0)
        hasArea = areaR > areaL && areaB > areaT

        val code = intent.getIntExtra("resultCode", -999)
        val projIntent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("projIntent", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("projIntent")
        }

        if (code == -999 || projIntent == null) { stopSelf(); return START_NOT_STICKY }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, projIntent)
        if (projection == null) { stopSelf(); return START_NOT_STICKY }

        if (Build.VERSION.SDK_INT >= 34) {
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { handler.post { stopSelf() } }
            }, handler)
        }

        reader = ImageReader.newInstance(scrW, scrH, PixelFormat.RGBA_8888, 2)
        vDisplay = projection!!.createVirtualDisplay(
            "Mag", scrW, scrH, scrD,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )

        showWindow(
            if (presetX >= 0) presetX else (scrW / 2 - 130),
            if (presetY >= 0) presetY else 50,
            if (presetW > 0) presetW else dp(260),
            if (presetH > 0) presetH else dp(150)
        )
        active = true
        startCapture()

        return START_NOT_STICKY
    }

    fun getState(): MagState? {
        val p = rootParams ?: return null
        return MagState(p.x, p.y, p.width, p.height, areaL, areaT, areaR, areaB)
    }

    // ========================================
    // WINDOW
    // ========================================
    @SuppressLint("ClickableViewAccessible")
    private fun showWindow(initX: Int, initY: Int, initW: Int, initH: Int) {
        val root = FrameLayout(this)
        rootView = root

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 8, 30))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // TOP BAR
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(35, 18, 60))
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)
            )
        }
        topBar = bar

        val btnIcon = btn("●", 9f) { toIconMode() }
        btnIcon.setTextColor(Color.rgb(255, 200, 100))
        val btnCompact = btn("▽", 9f) { toCompactMode() }
        btnCompact.setTextColor(Color.rgb(150, 150, 255))
        val btnSel = btn("📌", 10f) { openSelection() }
        val sp = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val btnX = btn("✕", 11f) { stopSelf() }
        btnX.setTextColor(Color.rgb(255, 80, 80))

        bar.addView(btnIcon)
        bar.addView(btnCompact)
        bar.addView(btnSel)
        bar.addView(sp)
        bar.addView(btnX)
        content.addView(bar)

        // IMAGE
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.rgb(5, 2, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        ivMag = iv
        content.addView(iv)
        root.addView(content)

        // EXPAND BUTTON (COMPACT mode)
        val expandSize = dp(14)
        val expand = object : View(this@MagnifierService) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(100, 100, 255); style = Paint.Style.FILL
            }
            override fun onDraw(c: Canvas) {
                val path = Path().apply {
                    moveTo(0f, 0f); lineTo(width.toFloat(), 0f); lineTo(0f, height.toFloat()); close()
                }
                c.drawPath(path, paint)
            }
        }
        expand.layoutParams = FrameLayout.LayoutParams(expandSize, expandSize).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        expand.visibility = View.GONE
        expand.setOnClickListener { toFullMode() }
        expandBtn = expand
        root.addView(expand)

        // RESIZE HANDLE
        val handleSize = dp(9)
        val handle = object : View(this@MagnifierService) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 60, 60); style = Paint.Style.FILL
            }
            override fun onDraw(c: Canvas) {
                val path = Path().apply {
                    moveTo(width.toFloat(), 0f)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(0f, height.toFloat())
                    close()
                }
                c.drawPath(path, paint)
            }
        }
        handle.layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
        root.addView(handle)

        savedX = initX; savedY = initY

        val params = WindowManager.LayoutParams(
            initW, initH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX; y = initY
        }
        rootParams = params

        // Apply opacity to view, NOT to params (fixes touch issue)
        root.alpha = opacity

        bar.setOnTouchListener(DragListener(params, root))
        iv.setOnTouchListener(DragListener(params, root))
        handle.setOnTouchListener(ResizeListener(params, root))

        wm.addView(root, params)
        mode = 0
    }

    private fun toCompactMode() {
        topBar?.visibility = View.GONE
        expandBtn?.visibility = View.VISIBLE
        mode = 1
    }

    private fun toFullMode() {
        topBar?.visibility = View.VISIBLE
        expandBtn?.visibility = View.GONE
        mode = 0
    }

    private fun toIconMode() {
        rootParams?.let { savedX = it.x; savedY = it.y }
        rootView?.visibility = View.GONE
        showIcon()
        mode = 2
    }

    private fun fromIconMode() {
        hideIcon()
        rootView?.visibility = View.VISIBLE
        toFullMode()
    }

    // ========================================
    // ICON
    // ========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun showIcon() {
        if (iconView != null) return

        val icon = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(50, 30, 80))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val btnExpand = TextView(this).apply {
            text = "▲"; textSize = 12f
            setTextColor(Color.rgb(100, 255, 100))
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { fromIconMode() }
        }
        val btnClose = TextView(this).apply {
            text = "✕"; textSize = 12f
            setTextColor(Color.rgb(255, 80, 80))
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { stopSelf() }
        }

        icon.addView(btnExpand)
        icon.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX; y = savedY
        }
        iconParams = params

        icon.setOnTouchListener(object : View.OnTouchListener {
            private var lx = 0f; private var ly = 0f; private var moved = false
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { lx = ev.rawX; ly = ev.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if ((ev.rawX - lx).let { it * it } + (ev.rawY - ly).let { it * it } > 25) moved = true
                        if (moved) {
                            params.x += (ev.rawX - lx).toInt()
                            params.y += (ev.rawY - ly).toInt()
                            savedX = params.x; savedY = params.y
                            try { wm.updateViewLayout(icon, params) } catch (_: Exception) {}
                            lx = ev.rawX; ly = ev.rawY
                        }
                    }
                }
                return false
            }
        })

        wm.addView(icon, params)
        iconView = icon
    }

    private fun hideIcon() {
        iconView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        iconView = null
    }

    // ========================================
    // SELECTION
    // ========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun openSelection() {
        closeSelection()

        val view = object : View(this@MagnifierService) {
            private var sx = 0f; private var sy = 0f; private var cx = 0f; private var cy = 0f
            private var drawing = false

            private val rectP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 3f
            }
            private val fillP = Paint().apply { color = Color.argb(30, 0, 229, 255) }
            private val dimP = Paint().apply { color = Color.argb(120, 0, 0, 0) }
            private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            }

            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                if (!drawing) {
                    c.drawRect(0f, 0f, w, h, dimP)
                    c.drawText("Выдели область", w / 2, h / 2, txtP)
                } else {
                    val l = minOf(sx, cx); val t = minOf(sy, cy)
                    val r = maxOf(sx, cx); val b = maxOf(sy, cy)
                    c.drawRect(0f, 0f, w, t, dimP)
                    c.drawRect(0f, b, w, h, dimP)
                    c.drawRect(0f, t, l, b, dimP)
                    c.drawRect(r, t, w, b, dimP)
                    c.drawRect(l, t, r, b, fillP)
                    c.drawRect(l, t, r, b, rectP)
                }
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.x; sy = ev.y; cx = ev.x; cy = ev.y; drawing = true; invalidate() }
                    MotionEvent.ACTION_MOVE -> { cx = ev.x; cy = ev.y; invalidate() }
                    MotionEvent.ACTION_UP -> {
                        val l = minOf(sx, cx).toInt(); val t = minOf(sy, cy).toInt()
                        val r = maxOf(sx, cx).toInt(); val b = maxOf(sy, cy).toInt()
                        if (r - l > 10 && b - t > 10) {
                            areaL = l; areaT = t; areaR = r; areaB = b; hasArea = true
                        }
                        handler.postDelayed({ closeSelection() }, 50)
                    }
                }
                return true
            }
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, p)
        selOverlay = view
    }

    private fun closeSelection() {
        selOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        selOverlay = null
    }

    // ========================================
    // TOUCH
    // ========================================
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
        private val target: View
    ) : View.OnTouchListener {
        private var lx = 0f; private var ly = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { lx = ev.rawX; ly = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x += (ev.rawX - lx).toInt()
                    params.y += (ev.rawY - ly).toInt()
                    try { wm.updateViewLayout(target, params) } catch (_: Exception) {}
                    lx = ev.rawX; ly = ev.rawY
                }
            }
            return true
        }
    }

    private inner class ResizeListener(
        private val params: WindowManager.LayoutParams,
        private val target: View
    ) : View.OnTouchListener {
        private var lx = 0f; private var ly = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { lx = ev.rawX; ly = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (params.width + (ev.rawX - lx).toInt()).coerceAtLeast(dp(50))
                    params.height = (params.height + (ev.rawY - ly).toInt()).coerceAtLeast(dp(30))
                    try { wm.updateViewLayout(target, params) } catch (_: Exception) {}
                    lx = ev.rawX; ly = ev.rawY
                }
            }
            return true
        }
    }

    // ========================================
    // CAPTURE (без zoom!)
    // ========================================
    private fun startCapture() {
        handler.post(object : Runnable {
            override fun run() {
                if (!active) return
                capture()
                handler.postDelayed(this, 80)
            }
        })
    }

    private fun capture() {
        if (!hasArea || mode == 2) return
        val img = try { reader?.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            val bw = scrW + (rs - ps * scrW) / ps

            val full = Bitmap.createBitmap(bw, scrH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)

            val l = areaL.coerceIn(0, bw - 2)
            val t = areaT.coerceIn(0, scrH - 2)
            val r = areaR.coerceIn(l + 1, bw)
            val b = areaB.coerceIn(t + 1, scrH)

            if (r - l > 2 && b - t > 2) {
                val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
                ivMag?.post { ivMag?.setImageBitmap(crop) }
            }
            full.recycle()
        } catch (_: Exception) {} finally { img.close() }
    }

    // ========================================
    private fun btn(text: String, size: Float, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(3), 0, dp(3), 0); setOnClickListener { click() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        val ch = NotificationChannel("mag_ch", "Magnifier", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mag_ch")
            .setContentTitle("Magnifier").setContentText("${getCount()} active")
            .setSmallIcon(R.drawable.ic_mag).setContentIntent(pi).setOngoing(true).build()
    }

    override fun onDestroy() {
        instances.remove(this)
        active = false
        handler.removeCallbacksAndMessages(null)
        closeSelection(); hideIcon()
        rootView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vDisplay?.release(); reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
