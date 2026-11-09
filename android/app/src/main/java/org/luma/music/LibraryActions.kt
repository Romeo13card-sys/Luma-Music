package org.luma.music

import android.content.Context
import android.os.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.*

/** Writes are serial and bounded, and never keep an Activity alive during HTTP. */
object LibraryActions {
    interface Observer {fun reactionFinished(id:String,result:JSONObject)}
    @Volatile var revision=0L;private set
    private val main=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(2)).apply {allowCoreThreadTimeOut(true)}
    fun cached(token:String,id:String):JSONObject = runCatching {
        JSONObject(Native.request(token,"reaction_cached",id)).optJSONObject("data") ?: JSONObject()
    }.getOrElse {JSONObject()}
    fun react(context:Context,token:String,song:Song,kind:String,set:Boolean,observer:Observer):Boolean {
        if(token.isBlank())return false
        val app=context.applicationContext;val target=WeakReference(observer);val id=song.id
        val args=JSONObject().put("id",song.id).put("kind",kind).put("set",set)
        song.album?.let {args.put("album",it)}
        val raw=args.toString()
        return try {
            worker.execute {
                val result=if(TokenStore(app).load()!=token)JSONObject().put("ok",false).put("error","Аккаунт изменился")
                else runCatching {JSONObject(Native.request(token,"reaction",raw))}
                    .getOrElse {JSONObject().put("ok",false).put("error","Не удалось изменить отметку")}
                if(result.optBoolean("ok"))revision++
                main.post {if(TokenStore(app).load()==token){
                    if(result.optBoolean("ok"))PlaybackService.instance?.reactionFeedback(id,kind,set)
                    target.get()?.reactionFinished(id,result)
                }}
            };true
        }catch(_:RejectedExecutionException){false}
    }
}
