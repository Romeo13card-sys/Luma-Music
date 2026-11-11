package org.luma.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** At most one silent native player and one pending resolver; all player access is on main. */
class NextTrackBuffer(private val context:Context) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(1),ThreadPoolExecutor.DiscardOldestPolicy()).apply {allowCoreThreadTimeOut(true)}
    @Volatile private var ticket=0
    private var key:String?=null
    private var account:String?=null
    private var candidate:MediaPlayer?=null
    @Volatile private var ready=false
    val readyKey:String? get()=key.takeIf {ready}
    fun prepare(identity:String,credential:String,resolve:()->String) {
        if(key==identity && account==credential)return
        cancel();key=identity;account=credential
        val version=ticket
        worker.execute {
            if(version!=ticket)return@execute
            val source=runCatching(resolve).getOrNull() ?: run {main.post {if(version==ticket)cancel()};return@execute}
            main.post {
                if(version!=ticket || TokenStore(context).load().orEmpty()!=credential)return@post
                try {
                    val media=MediaPlayer();candidate=media
                    media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    media.setOnPreparedListener {if(version==ticket && candidate===it)ready=true else it.release()}
                    media.setOnErrorListener {failed,_,_->if(candidate===failed)cancel();true}
                    if(source.startsWith("content://") || source.startsWith("file://"))media.setDataSource(context,Uri.parse(source)) else media.setDataSource(source)
                    media.prepareAsync()
                }catch(_:Exception){if(version==ticket)cancel()}
            }
        }
    }
    fun take(identity:String,credential:String):MediaPlayer? {
        if(key!=identity || account!=credential || !ready){cancel();return null}
        val result=candidate;candidate=null;ready=false;key=null;account=null;ticket++
        result?.setOnPreparedListener(null);result?.setOnErrorListener(null)
        return result
    }
    fun cancel() {ticket++;candidate?.release();candidate=null;key=null;account=null;ready=false;worker.queue.clear()}
    fun close() {cancel();worker.shutdownNow()}
}
