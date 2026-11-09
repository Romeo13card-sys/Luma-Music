package org.luma.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat

object MusicUi {
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun text(c:Context,value:String,size:Float=16f,bold:Boolean=false,raw:Boolean=false)=LumaTextView(c).apply {
        localize=!raw;text=value;textSize=size*SettingsStore.get(c).getInt("font_size",100)/100f;setTextColor(AppThemes.active.text)
        if(bold)typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)
    }
    fun button(c:Context,label:String,click:()->Unit)=LumaButton(c).apply {
        text=L10n.text(c,label);textSize=14f*SettingsStore.get(c).getInt("font_size",100)/100f;isAllCaps=false;minHeight=dp(c,48);cornerRadius=dp(c,20)
        setOnClickListener {click()}
    }
    fun icon(c:Context,res:Int,label:String,color:Int=AppThemes.active.text,click:()->Unit)=LumaButton(c).apply {
        text="";icon=ContextCompat.getDrawable(c,res);iconSize=dp(c,24);iconPadding=0;iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        iconTint=ColorStateList.valueOf(color);backgroundTintList=ColorStateList.valueOf(Color.TRANSPARENT)
        insetTop=0;insetBottom=0;minWidth=dp(c,48);minimumWidth=dp(c,48);minHeight=dp(c,48)
        setPadding(0,0,0,0);cornerRadius=dp(c,28);elevation=0f
        contentDescription=L10n.text(c,label);setOnClickListener {click()}
    }
    fun insets(root:View,padding:Int=16){
        root.setOnApplyWindowInsetsListener {view,i->
            val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            val p=dp(root.context,padding)
            view.setPadding(p+b.left,p+b.top,p+b.right,p+b.bottom);i
        };root.requestApplyInsets()
    }
}

/** Reused native row; only visible page/list rows own thumbnail Views. */
class SongRow(c:Context):LinearLayout(c) {
    val art=AlbumArt(c)
    val marker=MusicUi.text(c,"",12f).apply {gravity=Gravity.CENTER;visibility=View.GONE}
    val title=MusicUi.text(c,"",16f,true,true).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    val artist=MusicUi.text(c,"",13f,false,true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(AppThemes.active.muted)}
    val more=MusicUi.icon(c,R.drawable.luma_more,"Действия с треком"){}.apply {isFocusable=false;isFocusableInTouchMode=false}
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        val p=MusicUi.dp(c,10);setPadding(p,p,p,p);background=surface(MusicUi.dp(c,16).toFloat())
        val tile=FrameLayout(c);tile.addView(art,FrameLayout.LayoutParams(-1,-1))
        marker.background=surface(MusicUi.dp(c,12).toFloat(),true)
        tile.addView(marker,FrameLayout.LayoutParams(MusicUi.dp(c,22),MusicUi.dp(c,22),Gravity.END or Gravity.BOTTOM))
        addView(tile,LayoutParams(MusicUi.dp(c,48),MusicUi.dp(c,48)).apply {rightMargin=MusicUi.dp(c,12)})
        val labels=LinearLayout(c).apply {orientation=VERTICAL;addView(title);addView(artist)}
        addView(labels,LayoutParams(0,-2,1f));addView(more,LayoutParams(MusicUi.dp(c,44),MusicUi.dp(c,48)))
    }
    fun bind(song:Song,current:Boolean=false){
        art.bind(song.cover);title.text=song.title;artist.text=song.artist
        marker.text=if(current)"●" else "";marker.visibility=if(current)View.VISIBLE else View.GONE
        alpha=if(song.available)1f else .55f
        contentDescription=song.title+", "+song.artist
    }
}

/** Gesture detection is local to Android; navigation/restart decisions stay in Rust. */
fun swipeTracks(view:View,onTap:()->Unit){
    var startX=0f;var startY=0f;var active=false;var dragged=false
    val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    view.setOnClickListener {onTap()}
    view.setOnTouchListener {v,event->
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{startX=event.x;startY=event.y;active=true;dragged=false}
            MotionEvent.ACTION_POINTER_DOWN->{active=false;v.parent?.requestDisallowInterceptTouchEvent(false)}
            MotionEvent.ACTION_MOVE->if(active){
                val dx=kotlin.math.abs(event.x-startX);val dy=kotlin.math.abs(event.y-startY)
                if(dx>dy+MusicUi.dp(v.context,12)){dragged=true;v.parent?.requestDisallowInterceptTouchEvent(true)}
            }
            MotionEvent.ACTION_UP->{
                if(active){
                    val dx=event.x-startX;val dy=event.y-startY
                    if(kotlin.math.abs(dx)>=MusicUi.dp(v.context,48) && kotlin.math.abs(dx)>kotlin.math.abs(dy)+MusicUi.dp(v.context,12))
                        PlaybackService.command(v.context,if(dx<0)"next" else "previous")
                    else if(!dragged && kotlin.math.abs(dx)<=slop && kotlin.math.abs(dy)<=slop)v.performClick()
                }
                active=false;v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL->{active=false;v.parent?.requestDisallowInterceptTouchEvent(false)}
        }
        true
    }
}
