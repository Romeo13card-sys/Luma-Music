package org.luma.music

import android.content.Intent
import android.os.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** SAF handles the chosen external file; validation and restore run through the Rust-backed writer. */
class BackupActivity:MaterialActivity(),PersonalLibrary.Observer {
    private lateinit var status:TextView
    private lateinit var includeHistory:CheckBox
    private lateinit var includeQueue:CheckBox
    private var ticket=0
    private var pending=0
    private val io=Executors.newSingleThreadExecutor()
    private val exportFile get()=File(cacheDir,"luma-backup-export.json")
    private val importFile get()=File(cacheDir,"luma-backup-import.json")
    override fun onCreate(state:Bundle?){
        super.onCreate(state);pending=0
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(AppThemes.active.background)}
        root.addView(MusicUi.button(this,"Назад"){finish()})
        root.addView(MusicUi.text(this,"Резервная копия",28f,true))
        root.addView(MusicUi.text(this,"Настройки, закрепления, закладки и выбранные метаданные. Саму музыку нужно скачать на новом устройстве отдельно.",14f))
        includeQueue=CheckBox(this).apply {text=L10n.text(this@BackupActivity,"Добавить очередь воспроизведения");isChecked=state?.getBoolean("queue",true) ?: true};root.addView(includeQueue)
        includeHistory=CheckBox(this).apply {text=L10n.text(this@BackupActivity,"Добавить историю прослушивания");isChecked=state?.getBoolean("history",false) ?: false};root.addView(includeHistory)
        status=MusicUi.text(this,"",14f);root.addView(status)
        root.addView(MusicUi.button(this,"Экспортировать в файл"){
            if(pending!=0)return@button
            pending=1;status.text=L10n.text(this,"Подготавливаем резервную копию…")
            val target=WeakReference(this)
            SettingsStore.get(this).exportBackup(exportFile,includeQueue.isChecked,includeHistory.isChecked){result->target.get()?.exportReady(result)}
        })
        root.addView(MusicUi.button(this,"Восстановить из файла"){
            if(pending!=0)return@button
            pending=2
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),2)
        })
        setContentView(ScrollView(this).apply {addView(root)});MusicUi.insets(root)
    }
    private fun exportReady(result:JSONObject){
        if(isDestroyed)return
        if(!result.optBoolean("ok")){pending=0;status.text=result.optString("error");return}
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"Luma-Music-backup-"+java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date())+".json"),1)
    }
    @Deprecated("SAF activity result")
    override fun onActivityResult(request:Int,result:Int,data:Intent?){
        super.onActivityResult(request,result,data)
        val uri=data?.data
        if(result!=RESULT_OK || uri==null){pending=0;return}
        val app=applicationContext;val target=WeakReference(this);val output=if(request==1)exportFile else importFile
        io.execute {
            val success=runCatching {
                if(request==1){
                    app.contentResolver.openOutputStream(uri,"wt").use {destination->checkNotNull(destination){"Не удалось открыть файл"};output.inputStream().use {it.copyTo(destination,8192)}}
                    output.delete()
                }else{
                    app.contentResolver.openInputStream(uri).use {source->checkNotNull(source){"Не удалось открыть файл"}
                        output.outputStream().use {destination->
                            val buffer=ByteArray(8192);var total=0
                            while(true){val n=source.read(buffer);if(n<0)break;total+=n;check(total<=512000){"Резервная копия превышает 512 КБ"};destination.write(buffer,0,n)}
                        }
                    }
                }
            }
            Handler(Looper.getMainLooper()).post {
                val owner=target.get() ?: return@post;if(owner.isDestroyed)return@post
                owner.pending=0
                if(success.isFailure){output.delete();owner.status.text=success.exceptionOrNull()?.message;return@post}
                if(request==1)owner.status.text=L10n.text(owner,"Резервная копия сохранена")
                else owner.preview()
            }
        }
    }
    private fun preview(){
        pending=3;status.text=L10n.text(this,"Проверяем резервную копию…")
        val target=WeakReference(this)
        SettingsStore.get(this).inspectBackup(importFile){result->
            val owner=target.get() ?: return@inspectBackup;if(owner.isDestroyed)return@inspectBackup
            owner.pending=0
            if(!result.optBoolean("ok")){owner.status.text=result.optString("error");owner.importFile.delete();return@inspectBackup}
            val data=result.getJSONObject("data")
            LumaDialog(owner).setTitle("Восстановить резервную копию?")
                .setMessage(L10n.text(owner,"Будут заменены настройки и выбранные локальные данные.")+"\n"+
                    L10n.text(owner,"Настроек")+": "+data.optInt("settings")+" · "+L10n.text(owner,"Закладок")+": "+data.optInt("bookmarks"))
                .setNegativeButton("Отмена"){_,_->owner.importFile.delete()}
                .setPositiveButton("Восстановить"){_,_->owner.restore()}.show()
        }
    }
    private fun restore(){
        pending=4;val target=WeakReference(this)
        PlaybackService.forgetQueueInMemory()
        stopService(Intent(this,PlaybackService::class.java))
        SettingsStore.get(this).restoreBackup(importFile){result->
            val owner=target.get() ?: return@restoreBackup;if(owner.isDestroyed)return@restoreBackup
            owner.pending=0;owner.importFile.delete()
            if(result.optBoolean("ok")){
                owner.startActivity(Intent(owner,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("settings_section","data"))
                owner.status.text=L10n.text(owner,"Копия восстановлена. Настройки применятся при открытии экрана.")
            }else owner.status.text=result.optString("error")
        }
    }
    override fun libraryResult(ticket:Int,operation:String,result:JSONObject){}
    override fun onSaveInstanceState(state:Bundle){state.putInt("pending",pending);state.putBoolean("queue",includeQueue.isChecked);state.putBoolean("history",includeHistory.isChecked);super.onSaveInstanceState(state)}
    override fun onDestroy(){io.shutdownNow();super.onDestroy()}
}
