package com.thgbao.location_multishot_capture.camera

import android.content.Context
import java.io.File

class ImageFileRepository(context: Context) {
    private val rootDir = File(context.cacheDir, "camera_session")
    private val pendingDir = File(rootDir, "pending")
    private val confirmedDir = File(rootDir, "confirmed")

    fun prepareCaptureSession() {
        deleteRecursively(pendingDir)
        pendingDir.mkdirs()
        confirmedDir.mkdirs()
    }

    fun savePending(captureId: String, bytes: ByteArray): File {
        pendingDir.mkdirs()
        val file = File(pendingDir, "$captureId.jpg")
        file.writeBytes(bytes)
        return file
    }

    fun confirmCapture(captureId: String, pendingFile: File): File {
        confirmedDir.mkdirs()
        val confirmedFile = File(confirmedDir, "$captureId.jpg")
        if (confirmedFile.exists()) {
            confirmedFile.delete()
        }
        if (!pendingFile.renameTo(confirmedFile)) {
            pendingFile.copyTo(confirmedFile, overwrite = true)
            pendingFile.delete()
        }
        return confirmedFile
    }

    fun discard(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    fun discardAll(files: Iterable<File>) {
        files.forEach(::discard)
    }

    fun clearAllSessionImages() {
        deleteRecursively(rootDir)
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }
}
