package com.nazhi.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class FloatingCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: LinearLayout? = null
    private var actionView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeBubble()
                return START_STICKY
            }
            ACTION_SHOW -> {
                showBubble(collapsed = true)
                return START_STICKY
            }
            ACTION_START_HIDDEN -> {
                removeBubble()
                return START_STICKY
            }
        }

        showBubble(collapsed = true)
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubble()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble(collapsed: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        if (bubbleView != null) {
            if (collapsed) collapseBubble() else expandBubble()
            return
        }

        val bubbleSize = 52.dp()
        val bubble = TextView(this).apply {
            text = "纳"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circleDrawable(color = 0xFF2563EB.toInt(), strokeWidth = 2.dp())
            elevation = 10f
            contentDescription = "纳知悬浮收纳"
            minWidth = bubbleSize
            minHeight = bubbleSize
        }
        val action = TextView(this).apply {
            text = "粘贴保存"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(14.dp(), 0, 14.dp(), 0)
            background = roundedDrawable(color = 0xFF111827.toInt(), radius = 20.dp())
            minHeight = 40.dp()
            visibility = View.GONE
            setOnClickListener {
                collapseBubble()
                openClipboardCapture()
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = COLLAPSED_ALPHA
            addView(action, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                40.dp()
            ).apply {
                rightMargin = 8.dp()
            })
            addView(bubble, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = collapsedX()
            y = 220.dp()
        }

        bubble.setOnTouchListener(BubbleTouchListener(params))
        bubbleView = container
        actionView = action
        bubbleParams = params
        windowManager.addView(container, params)
        if (collapsed) collapseBubble() else expandBubble()
    }

    private fun removeBubble() {
        bubbleView?.let { view ->
            runCatching {
                windowManager.removeView(view)
            }
        }
        bubbleView = null
        actionView = null
        bubbleParams = null
        isExpanded = false
    }

    private fun expandBubble() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        isExpanded = true
        actionView?.visibility = View.VISIBLE
        view.alpha = 1f
        params.x = 16.dp()
        windowManager.updateViewLayout(view, params)
    }

    private fun collapseBubble() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        isExpanded = false
        actionView?.visibility = View.GONE
        view.alpha = COLLAPSED_ALPHA
        params.x = collapsedX()
        windowManager.updateViewLayout(view, params)
    }

    private fun openClipboardCapture() {
        val intent = Intent(this, ClipboardCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "无法打开悬浮收纳", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class BubbleTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var downTime = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    bubbleView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - initialTouchX) + abs(event.rawY - initialTouchY)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (moved < 12.dp() && elapsed < 350) {
                        view.performClick()
                        if (isExpanded) collapseBubble() else expandBubble()
                    } else {
                        collapseBubble()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun circleDrawable(color: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(strokeWidth, 0xFFFFFFFF.toInt())
        }
    }

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun collapsedX(): Int {
        return (-30).dp()
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_START_HIDDEN = "com.nazhi.app.floating.START_HIDDEN"
        const val ACTION_SHOW = "com.nazhi.app.floating.SHOW"
        const val ACTION_HIDE = "com.nazhi.app.floating.HIDE"
        private const val COLLAPSED_ALPHA = 0.48f

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
