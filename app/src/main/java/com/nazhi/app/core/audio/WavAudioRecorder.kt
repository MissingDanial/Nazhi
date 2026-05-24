package com.nazhi.app.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordedAudio(
    val file: File,
    val durationMs: Long,
    val byteSize: Long,
    val reachedLimit: Boolean,
    val peakAmplitude: Int = 0,
    val rmsAmplitude: Double = 0.0
)

class WavAudioRecorder(
    private val context: Context
) {
    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun record(maxDurationMs: Long = MAX_DURATION_MS): RecordedAudio = withContext(Dispatchers.IO) {
        check(hasRecordPermission()) { "缺少麦克风权限" }
        check(!isRecording) { "录音正在进行中" }

        val outputDir = File(context.cacheDir, "audio_transcriptions").apply { mkdirs() }
        val outputFile = File(outputDir, "nazhi-audio-${System.currentTimeMillis()}.wav")
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "设备不支持当前录音参数" }
        val bufferSize = max(minBufferSize, 4096)
        val buffer = ByteArray(bufferSize)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "录音器初始化失败" }

        var dataSize = 0L
        var reachedLimit = false
        activeRecord = audioRecord
        isRecording = true
        isPaused = false

        try {
            RandomAccessFile(outputFile, "rw").use { output ->
                output.setLength(0)
                writeWavHeader(output, 0)
                audioRecord.startRecording()
                while (isRecording) {
                    val recordedDurationMs = dataSize.toRecordedDurationMs()
                    if (recordedDurationMs >= maxDurationMs) {
                        reachedLimit = true
                        isRecording = false
                        break
                    }
                    if (isPaused) {
                        SystemClock.sleep(80)
                        continue
                    }
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        dataSize += read
                    }
                }
                patchWavHeader(output, dataSize)
            }
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            audioRecord.release()
            activeRecord = null
            isRecording = false
            isPaused = false
        }

        RecordedAudio(
            file = outputFile,
            durationMs = dataSize.toRecordedDurationMs(),
            byteSize = dataSize,
            reachedLimit = reachedLimit
        )
    }

    fun pause() {
        if (isRecording) {
            isPaused = true
        }
    }

    fun resume() {
        if (isRecording) {
            isPaused = false
        }
    }

    fun stop() {
        isRecording = false
    }

    fun cancel() {
        isRecording = false
        isPaused = false
        runCatching {
            activeRecord?.stop()
        }
    }

    private fun writeWavHeader(output: RandomAccessFile, dataSize: Long) {
        output.seek(0)
        output.writeBytes("RIFF")
        output.writeIntLe(36 + dataSize)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(CHANNELS)
        output.writeIntLe(SAMPLE_RATE.toLong())
        output.writeIntLe((SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong())
        output.writeShortLe(CHANNELS * BITS_PER_SAMPLE / 8)
        output.writeShortLe(BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeIntLe(dataSize)
    }

    private fun patchWavHeader(output: RandomAccessFile, dataSize: Long) {
        writeWavHeader(output, dataSize)
    }

    private fun RandomAccessFile.writeIntLe(value: Long) {
        write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        ))
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        ))
    }

    private fun Long.toRecordedDurationMs(): Long {
        val bytesPerSecond = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        return if (bytesPerSecond <= 0) 0 else this * 1000L / bytesPerSecond
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val MAX_DURATION_MS = 15 * 60 * 1000L
    }
}
