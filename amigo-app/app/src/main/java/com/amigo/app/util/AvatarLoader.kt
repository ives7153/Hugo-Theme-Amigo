package com.amigo.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AvatarLoader {

    private val palette = intArrayOf(
        Color.rgb(0x07, 0xC1, 0x60),
        Color.rgb(0x2E, 0x86, 0xAB),
        Color.rgb(0xC9, 0x6A, 0x4F),
        Color.rgb(0x8E, 0x6C, 0xC9),
        Color.rgb(0xE0, 0x8A, 0x2E),
        Color.rgb(0x4F, 0x86, 0xC9)
    )

    /** 本地文件头像：path 为相对文件名；空则生成首字圆形头像 */
    suspend fun load(context: Context, imageView: ImageView, path: String?, name: String) {
        var bmp = if (!path.isNullOrBlank()) {
            withContext(Dispatchers.IO) { ImageStore.loadBitmap(context, path, 160) }
        } else {
            withContext(Dispatchers.IO) { generate(name) }
        }
        if (bmp == null) return
        imageView.setImageDrawable(BitmapDrawable(context.resources, bmp))
        imageView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun generate(name: String): android.graphics.Bitmap {
        val size = 160
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val first = if (name.isNotEmpty()) name.first().toString() else "友"
        val avatarColor = palette[Math.floorMod(name.hashCode(), palette.size)]
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColor }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val fm = textPaint.fontMetrics
        val baseline = size / 2f - (fm.descent + fm.ascent) / 2f
        canvas.drawText(first, size / 2f, baseline, textPaint)
        return bmp
    }

    fun placeholder(context: Context, name: String): Drawable {
        return BitmapDrawable(context.resources, generate(name))
    }
}