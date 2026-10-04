package io.github.guellenmade.rootlessvm.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File

/**
 * Converts a container app icon (arbitrary bitmap) into an Adaptive Icon
 * compatible bitmap for the host launcher: foreground layer on a themed
 * background, sized to the safe zone.
 */
object AdaptiveIconFactory {
    const val SIZE = 432
    private const val SAFE = SIZE / 3f * 1.9f

    fun create(context: Context, source: Bitmap?, packageName: String): File {
        val fg = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fg)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        if (source != null) {
            val scale = SAFE / maxOf(source.width, source.height)
            val w = source.width * scale
            val h = source.height * scale
            val left = (SIZE - w) / 2f
            val top = (SIZE - h) / 2f
            canvas.drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 140f; textAlign = Paint.Align.CENTER }
            canvas.drawText(packageName.take(1).uppercase(), SIZE / 2f, SIZE / 2f + 50f, paint)
        }
        val dir = File(context.getDir("vm", Context.MODE_PRIVATE), "shortcut_icons").apply { mkdirs() }
        val file = File(dir, "$packageName.png")
        file.outputStream().use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun foregroundBitmap(file: File): Bitmap? =
        if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null

    fun circleCrop(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path().apply {
            addCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width / 2f, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }
}
