package com.nazhi.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaybackAudioRecorder(
    private val cacheDir: File
) {
    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    @Volatile
    private var activeProjection: MediaProjection? = null

    suspend fun record(
        mediaProjection: MediaProjection,
        maxDurationMs: Long = WavAudioRecorder.MAX_DURATION_MS
    ): RecordedAudio = withContext(Dispatchers.IO) {
        check(!isRecording) { "系统音频录制正在进行中" }

        val outputDir = File(cacheDir, "audio_transcriptions").apply { mkdirs() }
        val outputFile = File(outputDir, "nazhi-system-audio-${System.currentTimeMillis()}.wav")
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WavAudioRecorder.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBufferSize = AudioRecord.getMinBufferSize(
            WavAudioRecorder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "设备不支持当前系统音频录制参数" }
        val bufferSize = max(minBufferSize, 4096)
        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "系统音频录制器初始化失败" }

        var dataSize = 0L
        var reachedLimit = false
        val buffer = ByteArray(bufferSize)
        val audioLevels = AudioLevelAccumulator()
        val projectionCallback = object : Callback() {
            override fun onStop() {
                stop()
            }
        }
        activeRecord = audioRecord
        activeProjection = mediaProjection
        isRecording = true
        isPaused = false
        mediaProjection.registerCallback(projectionCallback, null)

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
                        audioLevels.addPcm16Le(buffer, read)
                        dataSize += read
                    }
                }
                writeWavHeader(output, dataSize)
            }
        } finally {
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            audioRecord.release()
            runCatching { mediaProjection.stop() }
            activeRecord = null
            activeProjection = null
            isRecording = false
            isPaused = false
        }

        RecordedAudio(
            file = outputFile,
            durationMs = dataSize.toRecordedDurationMs(),
            byteSize = dataSize,
            reachedLimit = reachedLimit,
            peakAmplitude = audioLevels.peakAmplitude,
            rmsAmplitude = audioLevels.rmsAmplitude
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
        runCatching {
            activeProjection?.stop()
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
        output.writeShortLe(WavAudioRecorder.CHANNELS)
        output.writeIntLe(WavAudioRecorder.SAMPLE_RATE.toLong())
        output.writeIntLe((WavAudioRecorder.SAMPLE_RATE * WavAudioRecorder.CHANNELS * WavAudioRecorder.BITS_PER_SAMPLE / 8).toLong())
        output.writeShortLe(WavAudioRecorder.CHANNELS * WavAudioRecorder.BITS_PER_SAMPLE / 8)
        output.writeShortLe(WavAudioRecorder.BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeIntLe(dataSize)
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
        val bytesPerSecond = WavAudioRecorder.SAMPLE_RATE * WavAudioRecorder.CHANNELS * WavAudioRecorder.BITS_PER_SAMPLE / 8
        return if (bytesPerSecond <= 0) 0 else this * 1000L / bytesPerSecond
    }

    private class AudioLevelAccumulator {
        private var sumSquares = 0.0
        private var sampleCount = 0L
        var peakAmplitude = 0
            private set

        val rmsAmplitude: Double
            get() = if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount)

        fun addPcm16Le(buffer: ByteArray, read: Int) {
            var index = 0
            while (index + 1 < read) {
                val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff))
                    .toShort()
                    .toInt()
                val amplitude = abs(sample)
                peakAmplitude = max(peakAmplitude, amplitude)
                sumSquares += sample.toDouble() * sample.toDouble()
                sampleCount += 1
                index += 2
            }
        }
    }
}
