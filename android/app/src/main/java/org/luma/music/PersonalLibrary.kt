package org.luma.music

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Android supplies the private DB path and auth snapshot; Rust owns metadata and validation. */
object PersonalLibrary {
    interface Observer {fun libraryResult(ticket:Int,operation:String,result:JSONObject)}
    @Volatile var revision=0L;private set
    private val main=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(16)).apply {allowCoreThreadTimeOut(true)}
    fun scope(credential:String)=NativeCore.call("account_scope",JSONObject().put("token",credential).toString()) as String
    fun changed(){revision++}
    fun fence():java.util.concurrent.FutureTask<Unit>{val task=java.util.concurrent.FutureTask(java.util.concurrent.Callable {Unit});worker.execute(task);return task}
    fun request(context:Context,observer:Observer?,ticket:Int,operation:String,args:JSONObject=JSONObject(),
                credential:String=TokenStore(context).load().orEmpty(),notify:Boolean=false):Boolean {
        val app=context.applicationContext;val target=observer?.let {WeakReference(it)};val raw=args.toString()
        fun deliver(result:JSONObject){main.post {
            if(TokenStore(app).load().orEmpty()!=credential){target?.get()?.libraryResult(ticket,operation,JSONObject().put("ok",false).put("error","Аккаунт изменился"));return@post}
            if(notify)Toast.makeText(app,L10n.text(app,if(result.optBoolean("ok"))"Сохранено" else result.optString("error")),Toast.LENGTH_SHORT).show()
            val owner=target?.get();if(owner !is Activity || !owner.isDestroyed)owner?.libraryResult(ticket,operation,result)
        }}
        return try {
            worker.execute {
                val result=runCatching {
                    check(!QueueStore.restoring){"Восстанавливаем локальные данные…"}
                    check(TokenStore(app).load().orEmpty()==credential){"Аккаунт изменился"}
                    val input=JSONObject(raw).put("scope",scope(credential))
                    val data=NativeDatabase(app).call(operation,input.toString())
                    if(operation !in setOf("history_list","history_stats","bookmark_list","pin_list","backup_export","backup_inspect"))revision++
                    JSONObject().put("ok",true).put("data",data ?: JSONObject.NULL)
                }.getOrElse {JSONObject().put("ok",false).put("error",it.message ?: "Не удалось открыть локальные данные")}
                deliver(result)
            };true
        }catch(_:RejectedExecutionException){deliver(JSONObject().put("ok",false).put("error","Дождитесь завершения предыдущего действия"));false}
    }
    fun write(context:Context,operation:String,args:JSONObject,credential:String=TokenStore(context).load().orEmpty(),notify:Boolean=false)=
        request(context,null,0,operation,args,credential,notify)
    fun bookmark(activity:Activity,song:Song,position:Int){
        val input=EditText(activity).apply {hint=L10n.text(activity,"Название закладки");maxLines=2}
        val millis=position.coerceAtLeast(0)
        LumaDialog(activity).setTitle("Закладка в песне").setView(input).setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить"){_,_->write(activity,"bookmark_add",JSONObject().put("song",song.json()).put("position",millis).put("label",input.text.toString()),notify=true)}.show()
    }
}
