package com.nazhi.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class AudioTranscriptionPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudioRecording()
            finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startAudioRecording()
        } else {
            Toast.makeText(this, "未授权麦克风，无法录音", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun startAudioRecording() {
        val intent = Intent(this, AudioTranscriptionService::class.java)
            .setAction(AudioTranscriptionService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 8101
    }
}
