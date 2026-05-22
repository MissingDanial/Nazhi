package com.nazhi.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    private var actionsView: LinearLayout? = null
    private var statusView: TextView? = null
    private var bubbleLabel: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var isAudioMenu = false

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
            ACTION_REFRESH -> {
                refreshBubble()
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
            text = AudioTranscriptionService.state.symbol()
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circleDrawable(color = AudioTranscriptionService.state.color(), strokeWidth = 2.dp())
            elevation = 10f
            contentDescription = "纳知悬浮收纳"
            minWidth = bubbleSize
            minHeight = bubbleSize
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(10.dp(), 0, 10.dp(), 0)
            background = roundedDrawable(color = 0xE6111827.toInt(), radius = 18.dp())
            maxWidth = 180.dp()
            minHeight = 36.dp()
            visibility = View.GONE
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = COLLAPSED_ALPHA
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = 8.dp()
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                36.dp()
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
        actionsView = actions
        statusView = status
        bubbleLabel = bubble
        bubbleParams = params
        renderActions()
        renderStatus()
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
        actionsView = null
        statusView = null
        bubbleLabel = null
        bubbleParams = null
        isExpanded = false
        isAudioMenu = false
    }

    private fun expandBubble() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        isExpanded = true
        if (AudioTranscriptionService.state != AudioFloatingState.IDLE) {
            isAudioMenu = true
        }
        renderActions()
        renderStatus()
        actionsView?.visibility = View.VISIBLE
        view.alpha = 1f
        params.x = 16.dp()
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(view, params)
    }

    private fun collapseBubble() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        isExpanded = false
        actionsView?.visibility = View.GONE
        isAudioMenu = false
        renderStatus()
        view.alpha = COLLAPSED_ALPHA
        params.x = collapsedX()
        params.height = 52.dp()
        windowManager.updateViewLayout(view, params)
    }

    private fun refreshBubble() {
        bubbleLabel?.let { label ->
            label.text = AudioTranscriptionService.state.symbol()
            label.background = circleDrawable(color = AudioTranscriptionService.state.color(), strokeWidth = 2.dp())
        }
        renderActions()
        renderStatus()
        bubbleView?.let { view ->
            bubbleParams?.let { params -> windowManager.updateViewLayout(view, params) }
        }
    }

    private fun renderActions() {
        val actions = actionsView ?: return
        actions.removeAllViews()
        if (isAudioMenu || AudioTranscriptionService.state != AudioFloatingState.IDLE) {
            actions.addIconButton("📋") {
                collapseBubble()
                openClipboardCapture()
            }
            actions.addIconButton(AudioTranscriptionService.state.startSymbol()) {
                handleStartOrResume()
            }
            actions.addIconButton("⏸", enabled = AudioTranscriptionService.state == AudioFloatingState.RECORDING) {
                sendAudioAction(AudioTranscriptionService.ACTION_PAUSE)
                collapseBubble()
            }
            actions.addIconButton("■", enabled = AudioTranscriptionService.state.canFinish()) {
                sendAudioAction(AudioTranscriptionService.ACTION_FINISH)
                collapseBubble()
            }
        } else {
            actions.addIconButton("📋") {
                collapseBubble()
                openClipboardCapture()
            }
            actions.addIconButton("🎙") {
                isAudioMenu = true
                renderActions()
                renderStatus()
                bubbleView?.let { view ->
                    bubbleParams?.let { params -> windowManager.updateViewLayout(view, params) }
                }
            }
        }
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

    private fun handleStartOrResume() {
        val state = AudioTranscriptionService.state
        if (state == AudioFloatingState.PAUSED) {
            sendAudioAction(AudioTranscriptionService.ACTION_RESUME)
            collapseBubble()
            return
        }
        if (state != AudioFloatingState.IDLE && state != AudioFloatingState.FAILED && state != AudioFloatingState.SAVED) {
            collapseBubble()
            return
        }
        openAudioPermission()
        collapseBubble()
    }

    private fun sendAudioAction(action: String) {
        startAudioService(action)
    }

    private fun startAudioService(action: String) {
        val intent = Intent(this, AudioTranscriptionService::class.java).setAction(action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            Toast.makeText(this, "无法启动录音转写", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAudioPermission() {
        val intent = Intent(this, AudioTranscriptionPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "无法请求麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createActionView(textValue: String, enabled: Boolean = true): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt())
            setPadding(0, 0, 0, 0)
            alpha = if (enabled) 1f else 0.46f
            background = roundedDrawable(color = 0xFF111827.toInt(), radius = 20.dp())
            minWidth = 40.dp()
            minHeight = 40.dp()
            isEnabled = enabled
        }
    }

    private fun renderStatus() {
        val status = statusView ?: return
        val state = AudioTranscriptionService.state
        val textValue = AudioTranscriptionService.statusText.ifBlank { state.defaultStatusText() }
        val shouldShow = !isExpanded && state.shouldShowStatusChip() && textValue.isNotBlank()
        status.text = textValue
        status.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun LinearLayout.addIconButton(
        symbol: String,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        addView(createActionView(symbol, enabled).apply {
            setOnClickListener {
                if (enabled) {
                    onClick()
                }
            }
        }, LinearLayout.LayoutParams(40.dp(), 40.dp()).apply {
            rightMargin = 6.dp()
        })
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
        const val ACTION_REFRESH = "com.nazhi.app.floating.REFRESH"
        private const val COLLAPSED_ALPHA = 0.48f

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

private fun AudioFloatingState.symbol(): String {
    return when (this) {
        AudioFloatingState.IDLE -> "纳"
        AudioFloatingState.RECORDING -> "●"
        AudioFloatingState.PAUSED -> "Ⅱ"
        AudioFloatingState.FINISHING,
        AudioFloatingState.UPLOADING,
        AudioFloatingState.TRANSCRIBING,
        AudioFloatingState.SAVING -> "…"
        AudioFloatingState.SAVED -> "✓"
        AudioFloatingState.FAILED -> "!"
    }
}

private fun AudioFloatingState.color(): Int {
    return when (this) {
        AudioFloatingState.IDLE -> 0xFF2563EB.toInt()
        AudioFloatingState.RECORDING -> 0xFFDC2626.toInt()
        AudioFloatingState.PAUSED -> 0xFFF59E0B.toInt()
        AudioFloatingState.FINISHING,
        AudioFloatingState.UPLOADING,
        AudioFloatingState.TRANSCRIBING,
        AudioFloatingState.SAVING -> 0xFF2563EB.toInt()
        AudioFloatingState.SAVED -> 0xFF16A34A.toInt()
        AudioFloatingState.FAILED -> 0xFFB91C1C.toInt()
    }
}

private fun AudioFloatingState.startSymbol(): String {
    return when (this) {
        AudioFloatingState.PAUSED -> "▶"
        else -> "▶"
    }
}

private fun AudioFloatingState.canFinish(): Boolean {
    return this == AudioFloatingState.RECORDING || this == AudioFloatingState.PAUSED
}

private fun AudioFloatingState.shouldShowStatusChip(): Boolean {
    return when (this) {
        AudioFloatingState.FINISHING,
        AudioFloatingState.UPLOADING,
        AudioFloatingState.TRANSCRIBING,
        AudioFloatingState.SAVING,
        AudioFloatingState.SAVED,
        AudioFloatingState.FAILED -> true
        AudioFloatingState.IDLE,
        AudioFloatingState.RECORDING,
        AudioFloatingState.PAUSED -> false
    }
}

private fun AudioFloatingState.defaultStatusText(): String {
    return when (this) {
        AudioFloatingState.IDLE -> ""
        AudioFloatingState.RECORDING -> "录音中"
        AudioFloatingState.PAUSED -> "录音已暂停"
        AudioFloatingState.FINISHING -> "录音已结束，正在准备转写"
        AudioFloatingState.UPLOADING -> "音频上传中"
        AudioFloatingState.TRANSCRIBING -> "音频转写中"
        AudioFloatingState.SAVING -> "正在加入今日收件箱"
        AudioFloatingState.SAVED -> "音频已加入今日收件箱"
        AudioFloatingState.FAILED -> "转写失败"
    }
}
