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
        setColor(if (accent) 0xFFFFE04A.toInt() else if (AppThemes.active.light) 0xFFF0F0F0.toInt() else if (AppThemes.active.oled) 0xFF101010.toInt() else 0xFF202020.toInt())

    }

/** Static record placeholder; no bitmap cache, network cover downloads or animation. */
class AlbumArt(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w=width.toFloat();val h=height.toFloat();val r=minOf(w,h)*.38f
        paint.color=if(AppThemes.active.light) 0xFFE8E8E8.toInt() else 0xFF202020.toInt()
        c.drawRoundRect(0f,0f,w,h,24f,24f,paint)
        paint.color=0xFF303030.toInt();c.drawCircle(w/2,h/2,r,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=0xFF505050.toInt()
        for(i in 1..4)c.drawCircle(w/2,h/2,r*(.5f+i*.09f),paint)
        paint.style=Paint.Style.FILL;paint.color=AppThemes.active.accent;c.drawCircle(w/2,h/2,r*.22f,paint)
        paint.color=0xFF191919.toInt();c.drawCircle(w/2,h/2,r*.045f,paint)
    }
}
