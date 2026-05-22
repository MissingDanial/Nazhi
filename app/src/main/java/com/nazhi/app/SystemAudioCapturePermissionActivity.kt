package com.nazhi.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class SystemAudioCapturePermissionActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "系统音频模式需要 Android 10 或更高版本", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 8201
    }
}
