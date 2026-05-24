package com.nazhi.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SystemAudioCapturePermissionActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var hasRequestedProjection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "系统音频模式需要 Android 10 或更高版本", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        hasRequestedProjection = savedInstanceState?.getBoolean(KEY_REQUESTED_PROJECTION) ?: false
        if (hasAcceptedIntro()) {
            requestSystemAudioPermission()
        } else if (!hasRequestedProjection) {
            setContentView(buildExplanationView())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_REQUESTED_PROJECTION, hasRequestedProjection)
        super.onSaveInstanceState(outState)
    }

    private fun requestSystemAudioPermission() {
        if (hasRequestedProjection) return
        hasRequestedProjection = true
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            finish()
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未授权系统音频捕获", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        markIntroAccepted()
        val intent = Intent(this, AudioTranscriptionService::class.java)
            .setAction(AudioTranscriptionService.ACTION_START_SYSTEM_AUDIO)
            .putExtra(AudioTranscriptionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            .putExtra(AudioTranscriptionService.EXTRA_PROJECTION_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }

    private fun buildExplanationView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setPadding(18.dp(), 24.dp(), 18.dp(), 24.dp())
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp(), 22.dp(), 22.dp(), 18.dp())
            background = roundedDrawable(Color.WHITE, 20.dp())
        }
        val title = TextView(this).apply {
            text = "开启系统音频转写"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111827.toInt())
        }
        val body = TextView(this).apply {
            text = "系统接下来可能显示“屏幕录制/投屏”授权，这是 Android 的系统音频捕获入口。\n\n纳知只用于捕获允许被捕获的播放音频，不保存屏幕视频，也不会读取第三方 App 画面。\n\n部分 App 会禁止被捕获；如果没有有效音频，纳知会提示失败，不会保存空内容。"
            textSize = 15f
            setLineSpacing(4.dp().toFloat(), 1.0f)
            setTextColor(0xFF374151.toInt())
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = Button(this).apply {
            text = "暂不使用"
            setTextColor(0xFF374151.toInt())
            setOnClickListener {
                Toast.makeText(this@SystemAudioCapturePermissionActivity, "已取消系统音频转写", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        val continueButton = Button(this).apply {
            text = "继续并不再提示"
            setTextColor(Color.WHITE)
            background = roundedDrawable(0xFF2563EB.toInt(), 12.dp())
            setOnClickListener {
                requestSystemAudioPermission()
            }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
            rightMargin = 10.dp()
        })
        buttons.addView(continueButton, LinearLayout.LayoutParams(0, 48.dp(), 1f))

        card.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 14.dp()
        })
        card.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 20.dp()
        })

        val scrollView = ScrollView(this).apply {
            addView(card, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return root
    }

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun hasAcceptedIntro(): Boolean {
        return getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INTRO_ACCEPTED, false)
    }

    private fun markIntroAccepted() {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INTRO_ACCEPTED, true)
            .apply()
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 8201
        private const val KEY_REQUESTED_PROJECTION = "requested_projection"
        private const val PREFERENCES_NAME = "nazhi_audio_capture"
        private const val KEY_INTRO_ACCEPTED = "system_audio_intro_accepted"
    }
}
