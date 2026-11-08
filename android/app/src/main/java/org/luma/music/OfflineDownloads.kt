package org.luma.music

import android.app.*
import android.content.*
import android.os.*
import org.json.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object OfflineDownloads {
    @Volatile var artworkRevision=0L;private set
    fun artworkChanged(){artworkRevision++}
    fun arguments(context:Context):JSONObject = JSONObject()
        .put("root",File(context.filesDir,"offline").absolutePath)
        .put("owner",TokenStore(context,"offline_owner").load().orEmpty())
    fun list(context:Context):JSONObject {
        if(TokenStore(context,"offline_owner").load().isNullOrBlank()) return JSONObject().put("items",JSONArray()).put("bytes",0)
        return NativeCore.call("offline_list",arguments(context).toString()) as JSONObject
    }
    fun ready(context:Context,id:String):String? {
        if(TokenStore(context,"offline_owner").load().isNullOrBlank())return null
        return NativeCore.call("offline_ready",arguments(context).put("id",id).toString()) as? String
    }
    fun enqueue(context:Context,song:Song) {
        check(song.uri==null && song.id.all {it.isDigit()}) {"Для этого трека скачивание недоступно"}
        check(!TokenStore(context).load().isNullOrBlank()) {"Войдите в Яндекс Музыку"}
        check(!DownloadService.busy) {"Дождитесь завершения текущей загрузки"}
        context.startForegroundService(Intent(context,DownloadService::class.java).putExtra("id",song.id)
            .putExtra("title",song.title).putExtra("artists",song.artist).putExtra("cover",song.cover))
    }
    fun forgetAccount(context:Context) {
        val args=arguments(context)
        DownloadService.cancel()
        TokenStore(context,"offline_owner").clear()
        Thread {
            runCatching {NativeCore.call("offline_clear",args.toString())}
        }.start()
    }
}

/** One foreground job, a 32 KiB Rust transfer buffer, no timer or UI retained by the worker. */
class DownloadService:Service() {
    companion object {
        @Volatile var busy=false;private set
        @Volatile var message="Загрузок пока нет";private set
        @Volatile var percent=-1;private set
        @Volatile var revision=0L;private set
        private val cancelled=AtomicBoolean(false)
        fun cancel(){cancelled.set(true);if(busy)message="Отмена загрузки…"}
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads","Офлайн-загрузки",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,OfflineActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"downloads").setSmallIcon(R.drawable.ic_music).setContentTitle("Luma · загрузка")
            .setContentText(message).setOngoing(busy).setContentIntent(open)
            .setProgress(100,percent.coerceAtLeast(0),percent<0).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(busy)return START_NOT_STICKY
        busy=true;cancelled.set(false);percent=-1;message=intent?.getStringExtra("title").orEmpty()
        startForeground(2,notification())
        val id=intent?.getStringExtra("id").orEmpty()
        val title=message;val artists=intent?.getStringExtra("artists").orEmpty();val cover=intent?.getStringExtra("cover").orEmpty()
        val token=TokenStore(this).load().orEmpty()
        val prefs=SettingsStore.get(this)
        val wifi=prefs.getBoolean("download_wifi_only",true)
        val quota=prefs.getInt("download_limit_mb",1024).toLong()*1024*1024
        val quality=prefs.getInt("download_quality",192)
        worker.execute {
            try {
                val network=getSystemService(android.net.ConnectivityManager::class.java)
                fun allowed() = !wifi || (network.getNetworkCapabilities(network.activeNetwork)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true && !network.isActiveNetworkMetered)
                check(allowed()) {"Для загрузки требуется Wi-Fi без тарификации"}
                val account=JSONObject(Native.request(token,"account",""))
                check(account.optBoolean("ok")) {account.optString("error","Не удалось проверить аккаунт")}
                val owner=account.getJSONObject("data").get("uid").toString()
                check(owner.isNotBlank() && owner.all {it.isDigit()}) {"Нет идентификатора аккаунта"}
                check(!cancelled.get() && TokenStore(this).load()==token) {"Загрузка отменена"}
                TokenStore(this,"offline_owner").save(owner)
                val args=OfflineDownloads.arguments(this).put("id",id).put("title",title).put("artists",artists)
                    .put("quality",quality).put("quota",quota).put("cover",cover)
                var last=-2
                val result=JSONObject(Native.offlineDownload(token,args.toString()) {value ->
                    val proceed=!cancelled.get() && allowed()
                    if(value!=last){last=value;percent=value;getSystemService(NotificationManager::class.java).notify(2,notification())}
                    proceed
                })
                check(result.optBoolean("ok")) {result.optString("error","Загрузка не завершена")}
                message="Скачано · "+title;percent=100
            }catch(e:Exception){message=e.message ?: "Не удалось скачать трек"}
            finally{
                main.post {busy=false;revision++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){cancelled.set(true);worker.shutdown();super.onDestroy()}
}
