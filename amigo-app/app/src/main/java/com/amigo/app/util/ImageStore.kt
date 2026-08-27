package com.amigo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ImageStore {
    fun dir(context: Context): File {
        val d = File(context.filesDir, "images")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 从 content URI 拷贝图片到应用私有目录，返回相对文件名 */
    suspend fun importFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val out = File(dir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取图片")
        name
    }

    fun file(context: Context, relative: String): File = File(dir(context), relative)

    fun loadBitmap(context: Context, relative: String, maxSize: Int): Bitmap? {
        val f = File(dir(context), relative)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }
}