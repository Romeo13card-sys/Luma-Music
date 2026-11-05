package dev.romeo.yamusic

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View

fun glass(radius: Float = 28f, accent: Boolean = false): GradientDrawable =
    GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (accent) intArrayOf(Color.parseColor("#C2F8E5"), Color.parseColor("#78C9C0"))
            else intArrayOf(Color.parseColor("#35FFFFFF"), Color.parseColor("#12FFFFFF")),
        )
        .apply {
            cornerRadius = radius
            setStroke(1, if (accent) 0x70FFFFFF else 0x38FFFFFF)
        }

class Atmosphere(context: Context) : View(context) {
    private val first = Paint(Paint.ANTI_ALIAS_FLAG)
    private val second = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        first.shader =
            RadialGradient(
                w * .85f,
                h * .15f,
                w * .95f,
                intArrayOf(0xFF254F59.toInt(), 0x00254F59),
                null,
                Shader.TileMode.CLAMP,
            )
        second.shader =
            RadialGradient(
                0f,
                h * .7f,
                w * .9f,
                intArrayOf(0xFF343459.toInt(), 0x00343459),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(9, 19, 26))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), first)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), second)
    }
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
