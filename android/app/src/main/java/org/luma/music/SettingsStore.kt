package org.luma.music

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Android supplies an app-private path; migrations and persistence belong to Rust. */
class NativeDatabase(context: Context) {
    private val path = File(context.filesDir, "luma.db").absolutePath
    fun call(operation: String, argument: String = "{}"): Any? {
        val reply = JSONObject(Native.store(path, operation, argument))
        check(reply.optBoolean("ok")) { reply.optString("error", "Ошибка базы Luma") }
        return reply.opt("data").takeUnless { it == JSONObject.NULL }
    }
}

/** Small UI snapshot; no SQLite work while drawing or reading a preference. */
class SettingsStore private constructor(context: Context) {
    private val app=context.applicationContext
    private val database = NativeDatabase(context)
    private val writer = Executors.newSingleThreadExecutor()
    private val values: JSONObject

    init {
        val old = JSONObject()
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (value is String || value is Boolean || value is Number) old.put(key, value)
        }
        values = try {
            database.call("settings_import", old.toString()) as JSONObject
        } catch (_: Exception) {
            android.util.Log.w("Luma", "Settings database unavailable; retaining legacy preferences")
            old
        }
    }

    @Synchronized fun getBoolean(key: String, default: Boolean) = values.optBoolean(key, default)
    @Synchronized fun getInt(key: String, default: Int) = values.optInt(key, default)
    @Synchronized fun getLong(key: String, default: Long) = values.optLong(key, default)
    @Synchronized fun getString(key: String, default: String?): String? =
        if (values.has(key)) values.optString(key) else default
    fun edit() = Editor()
    inner class Editor {
        private val patch = JSONObject()
        fun putString(key: String, value: String) = apply { patch.put(key, value) }
        fun putBoolean(key: String, value: Boolean) = apply { patch.put(key, value) }
        fun putInt(key: String, value: Int) = apply { patch.put(key, value) }
        fun putLong(key: String, value: Long) = apply { patch.put(key, value) }
        fun apply() {
            val snapshot = patch.toString()
            synchronized(this@SettingsStore) {
                patch.keys().forEach { key -> values.put(key, patch.get(key)) }
                writer.execute {
                    try { database.call("settings_patch", snapshot) }
                    catch (_: Exception) { android.util.Log.w("Luma", "Could not persist settings") }
                }
            }
        }
    }
    fun reset(onSaved: (Boolean) -> Unit) {
        val defaults = NativeCore.call("settings_defaults", "{}") as JSONObject
        synchronized(this) {
            values.keys().asSequence().toList().forEach { values.remove(it) }
            defaults.keys().forEach { values.put(it, defaults.get(it)) }
            writer.execute {
                val saved = runCatching { database.call("settings_reset") }.isSuccess
                android.os.Handler(android.os.Looper.getMainLooper()).post { onSaved(saved) }
            }
        }
    }

    private fun backupTask(file:File,mode:String,args:JSONObject=JSONObject(),onSaved:(JSONObject)->Unit){
        val credential=TokenStore(app).load().orEmpty()
        writer.execute {
            val result=runCatching {
                check(TokenStore(app).load().orEmpty()==credential){"Аккаунт изменился"}
                val input=JSONObject(args.toString()).put("scope",PersonalLibrary.scope(credential))
                if(mode!="backup_export"){
                    check(file.length() in 1..512000){"Резервная копия превышает 512 КБ"}
                    input.put("backup",JSONObject(file.readText()))
                }
                if(mode=="backup_import"){
                    QueueStore.fence().get(10,java.util.concurrent.TimeUnit.SECONDS)
                    PersonalLibrary.fence().get(10,java.util.concurrent.TimeUnit.SECONDS)
                    check(TokenStore(app).load().orEmpty()==credential){"Аккаунт изменился"}
                }
                val data=database.call(mode,input.toString())
                if(mode=="backup_export"){
                    val temporary=File(file.parentFile,file.name+".part");temporary.writeText(data.toString())
                    check(temporary.renameTo(file)){"Не удалось подготовить копию"}
                }
                if(mode=="backup_import"){
                    val updated=(data as JSONObject).getJSONObject("settings")
                    synchronized(this@SettingsStore){values.keys().asSequence().toList().forEach {values.remove(it)};updated.keys().forEach {values.put(it,updated.get(it))}}
                    QueueStore.acknowledgeRestore()
                }
                JSONObject().put("ok",true).put("data",data ?: JSONObject.NULL)
            }.getOrElse {JSONObject().put("ok",false).put("error",it.message ?: "Ошибка резервной копии")}
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if(mode=="backup_import"){
                    QueueStore.restoring=false;PersonalLibrary.changed();PlaybackService.restore(app)
                }
                onSaved(result)
            }
        }
    }
    fun exportBackup(file:File,queue:Boolean,history:Boolean,callback:(JSONObject)->Unit)=backupTask(file,"backup_export",JSONObject().put("include_queue",queue).put("include_history",history),callback)
    fun inspectBackup(file:File,callback:(JSONObject)->Unit)=backupTask(file,"backup_inspect",onSaved=callback)
    fun restoreBackup(file:File,callback:(JSONObject)->Unit){
        if(QueueStore.restoring){callback(JSONObject().put("ok",false).put("error","Восстановление уже выполняется"));return}
        QueueStore.restoring=true;backupTask(file,"backup_import",onSaved=callback)
    }

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
