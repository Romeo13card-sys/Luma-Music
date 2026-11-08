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

/** A single small bitmap, weak asynchronous delivery and a static fallback when artwork is absent. */
class AlbumArt(context: Context) : View(context),CoverImages.Target {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val destination = RectF()
    private var cover:String?=null
    private var size=100
    private var requested=false
    var image:CoverImages.Image?=null;private set
    var onImage:((CoverImages.Image?)->Unit)?=null
    var prominent=false
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;paint.isFilterBitmap=true}
    fun bind(value:String?,large:Boolean=false) {
        val next=value?.takeIf {it.isNotBlank()};val pixels=if(large)400 else 100
        if(next==cover && size==pixels)return
        CoverImages.cancel(this);cover=next;size=pixels;image=null;requested=false
        onImage?.invoke(null);invalidate();request()
    }
    private fun request() {
        if(!isAttachedToWindow || windowVisibility!=VISIBLE || requested)return
        val value=cover ?: return
        requested=true;CoverImages.request(context,value,size,this,prominent)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();request()}
    override fun onWindowVisibilityChanged(visibility:Int){
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE)request()
        else {CoverImages.cancel(this);requested=false;image=null}
    }
    override fun onDetachedFromWindow(){CoverImages.cancel(this);image=null;requested=false;super.onDetachedFromWindow()}
    override fun artworkLoaded(key:String,image:CoverImages.Image?) {
        if(key!=cover?.let {CoverImages.key(it,size)} || !isAttachedToWindow || windowVisibility!=VISIBLE)return
        this.image=image;onImage?.invoke(image);invalidate()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val radius=12*resources.displayMetrics.density
        clip.reset();clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
    }
    override fun onDraw(c: Canvas) {
        image?.bitmap?.let {bitmap->
            val scale=maxOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            val save=c.save();c.clipPath(clip)
            destination.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
            c.drawBitmap(bitmap,null,destination,paint)
            c.restoreToCount(save);return
        }
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
