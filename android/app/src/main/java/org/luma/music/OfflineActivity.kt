package org.luma.music

import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.*
import java.util.concurrent.Executors

class OfflineActivity:MaterialActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status:TextView
    private lateinit var storage:TextView
    private lateinit var progress:LinearProgressIndicator
    private lateinit var adapter:ArrayAdapter<String>
    private var tracks=JSONArray()
    private var visible=false
    private var loading=false
    private var loadError:String?=null
    private var seen=-1L
    private val tick=object:Runnable{
        override fun run(){
            if(!visible)return
            status.text=loadError ?: L10n.text(this@OfflineActivity,DownloadService.message)
            progress.isIndeterminate=DownloadService.percent<0
            if(!progress.isIndeterminate)progress.progress=DownloadService.percent
            progress.visibility=if(DownloadService.busy)View.VISIBLE else View.GONE
            if(seen!=DownloadService.revision){seen=DownloadService.revision;reload()}
            handler.postDelayed(this,1000)
        }
    }
    private fun button(label:String,root:LinearLayout,action:()->Unit){
        root.addView(MaterialButton(this).apply {text=L10n.text(this@OfflineActivity,label);setOnClickListener {action()}})
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state);title=L10n.text(this,"Загрузки")
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20)}
        root.addView(LumaTextView(this).apply {text="Загрузки";textSize=28f})
        root.addView(LumaTextView(this).apply {text="Скачанные треки играют без сети. Удерживайте трек для удаления.";textSize=14f})
        status=LumaTextView(this);root.addView(status)
        storage=LumaTextView(this);root.addView(storage)
        progress=LinearProgressIndicator(this);root.addView(progress,LinearLayout.LayoutParams(-1,8))
        button("Отменить загрузку",root){DownloadService.cancel()}
        val prefs=SettingsStore.get(this)
        val wifi=com.google.android.material.materialswitch.MaterialSwitch(this).apply {text=L10n.text(this@OfflineActivity,"Загружать только по Wi-Fi");isChecked=prefs.getBoolean("download_wifi_only",true)
            setOnCheckedChangeListener {_,checked->prefs.edit().putBoolean("download_wifi_only",checked).apply()}}
        root.addView(wifi)
        button("Качество загрузок",root){
            val values=intArrayOf(128,192,320)
            LumaDialog(this).setTitle("Качество загрузок").setSingleChoiceItems(arrayOf("128 кбит/с","192 кбит/с","320 кбит/с"),values.indexOf(prefs.getInt("download_quality",192))) {dialog,i->
                prefs.edit().putInt("download_quality",values[i]).apply();dialog.dismiss()
            }.show()
        }
        button("Лимит места",root){
            val values=intArrayOf(256,512,1024,2048,4096,8192,16384)
            LumaDialog(this).setTitle("Лимит места").setSingleChoiceItems(values.map {it.toString()+" МБ"}.toTypedArray(),values.indexOf(prefs.getInt("download_limit_mb",1024))) {dialog,i->
                prefs.edit().putInt("download_limit_mb",values[i]).apply();dialog.dismiss();reload()
            }.show()
        }
        adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,mutableListOf<String>())
        val list=ListView(this).apply {
            adapter=this@OfflineActivity.adapter
            emptyView=null
            setOnItemClickListener {_,_,position,_->
                val songs=(0 until tracks.length()).map {Song.from(tracks.getJSONObject(it).put("available",true))}
                PlaybackService.queue=songs;PlaybackService.command(this@OfflineActivity,"play",position)
            }
            setOnItemLongClickListener {_,_,position,_->
                val id=tracks.getJSONObject(position).getString("id")
                LumaDialog(this@OfflineActivity).setTitle("Удалить скачанный трек?").setNegativeButton("Отмена",null)
                    .setPositiveButton("Удалить") {_,_->
                        worker.execute {
                            val result=runCatching {NativeCore.call("offline_remove",OfflineDownloads.arguments(this@OfflineActivity).put("id",id).toString())}
                            runOnUiThread {if(!isDestroyed){result.exceptionOrNull()?.let {status.text=it.message};reload()}}
                        }
                    }.show()
                true
            }
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        button("Обновить список",root){reload()}
        button("Назад",root){finish()}
        root.setOnApplyWindowInsetsListener {v,i->val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(20+b.left,20+b.top,20+b.right,20+b.bottom);i}
        setContentView(root);root.requestApplyInsets()
    }
    private fun reload(){
        if(loading)return;loading=true
        worker.execute {
            val result=runCatching {OfflineDownloads.list(this)}
            runOnUiThread {
                loading=false
                if(isDestroyed)return@runOnUiThread
                result.onSuccess {data->
                    loadError=null
                    tracks=data.getJSONArray("items")
                    adapter.clear();adapter.addAll((0 until tracks.length()).map {i->val t=tracks.getJSONObject(i);t.getString("title")+"\n"+t.getString("artists")})
                    storage.text=if(tracks.length()==0)L10n.text(this,"Скачанных треков пока нет") else
                        tracks.length().toString()+" · "+(data.optLong("bytes")/1048576).toString()+" МБ / "+SettingsStore.get(this).getInt("download_limit_mb",1024).toString()+" МБ"
                }.onFailure {loadError=it.message;status.text=it.message}
            }
        }
    }
    override fun onStart(){super.onStart();visible=true;handler.post(tick)}
    override fun onStop(){visible=false;handler.removeCallbacks(tick);super.onStop()}
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
