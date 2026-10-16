package com.nazhi.app.feature.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bounded UTF-8 input and complete SAF output; the picker holds only a cache file path. */
internal object BackupDocumentIO {
    const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    const val PHASE_PICKER = "PICKER"
    const val PHASE_WRITING = "WRITING"
    private val activeWritePaths = ConcurrentHashMap.newKeySet<String>()

    enum class PendingRecovery { NONE, WAIT_FOR_PICKER, WAIT_FOR_WRITE, INTERRUPTED }

    fun registerWrite(path: String): Boolean = activeWritePaths.add(path)
    fun unregisterWrite(path: String) { activeWritePaths.remove(path) }
    fun isWriteActive(path: String): Boolean = path in activeWritePaths

    /** A surviving cache file proves only that a snapshot exists, never that a writer survived. */
    fun pendingRecovery(path: String?, phase: String?): PendingRecovery = when {
        path == null -> PendingRecovery.NONE
        phase == PHASE_PICKER -> PendingRecovery.WAIT_FOR_PICKER
        phase == PHASE_WRITING && isWriteActive(path) -> PendingRecovery.WAIT_FOR_WRITE
        else -> PendingRecovery.INTERRUPTED
    }

    data class WriteResult(val readBackVerified: Boolean)

    fun stageExport(context: Context, text: String): File {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份超过 32 MiB，请减少数据后重试" }
        val directory = File(context.cacheDir, "backup-export").apply { check(mkdirs() || isDirectory) }
        // Only discard abandoned snapshots older than a week; a picker may remain open overnight.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }
            ?.forEach { it.delete() }
        val file = File(directory, "${UUID.randomUUID()}.json")
        try {
            file.outputStream().use { output -> output.write(bytes); output.flush() }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun readDocument(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { readUtf8(it) }
            ?: error("无法打开导入文件")

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= maxBytes.toLong()) { "备份超过 32 MiB，无法导入" }
            bytes.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("备份不是有效的 UTF-8 文本，请选择原始导出文件", error)
        }
    }

    fun writeDocument(context: Context, uri: Uri, stagedFile: File): WriteResult {
        require(stagedFile.isFile) { "备份暂存文件已丢失，请重新导出" }
        require(stagedFile.length() <= MAX_BACKUP_BYTES) { "备份超过 32 MiB，请减少数据后重试" }
        val expectedDigest = stagedFile.inputStream().use(::digest)
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            stagedFile.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: error("无法打开导出文件")
        // Some document providers grant only a writable stream. A successful close is still a save,
        // but tell the user explicitly that it could not be read back for verification.
        val verificationInput = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        // Opening may be unsupported. Once a readable stream is granted, a partial read is a failure.
        val actualDigest = verificationInput?.use(::digest)
        if (actualDigest != null) {
            check(MessageDigest.isEqual(expectedDigest, actualDigest)) {
                "保存后的文件内容不完整，请换一个保存位置重新导出"
            }
        }
        return WriteResult(readBackVerified = actualDigest != null)
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytesRead += count
            check(bytesRead <= MAX_BACKUP_BYTES) { "保存后的文件大小异常，请重新导出" }
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}
