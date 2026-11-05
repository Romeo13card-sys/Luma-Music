package org.luma.music

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Opaque surfaces: no blur, transparency, sheen or continuous animation. */
fun surface(radius: Float = 20f, accent: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(if (accent) 0xFFB6F4DD.toInt() else if (AppThemes.active.light) 0xFFFFFFFF.toInt() else if (AppThemes.active.oled) 0xFF101010.toInt() else 0xFF182329.toInt())
        setStroke(1, if (AppThemes.active.light) 0xFFD5DEDC.toInt() else 0xFF344147.toInt())
    }

class AlbumArt(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradient =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(0xFF478B88.toInt(), 0xFF283F5A.toInt(), 0xFF8F7FA2.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = gradient
        c.drawRoundRect(0f, 0f, w, h, 32f, 32f, paint)
        paint.shader = null
        for (i in 0..8) {
            paint.color = Color.argb(35 + i * 12, 202, 255, 235)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * .035f
            c.drawOval(
                w * (.08f + i * .035f),
                h * (.12f + i * .025f),
                w * (.92f - i * .035f),
                h * (.88f - i * .025f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }
}
