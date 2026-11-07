package org.luma.music

import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.widget.*
import org.json.*
import java.util.Locale

/** Rust owns packs and fallback. Android caches only the active UI snapshot. */
object L10n {
    private data class Snapshot(val key:String,val catalog:JSONObject)
    @Volatile private var current:Snapshot?=null
    private fun load(context:Context):JSONObject {
        val selected=SettingsStore.get(context).getString("language","system").orEmpty()
        val system=Resources.getSystem().configuration.locales.toLanguageTags()
        val key=selected+"|"+system
        current?.takeIf {it.key==key}?.let {return it.catalog}
        return synchronized(this) {
            current?.takeIf {it.key==key}?.catalog ?: run {
                val args=JSONObject().put("selected",selected).put("system",JSONArray(system.split(',')))
                val data=NativeCore.call("locale_catalog",args.toString()) as JSONObject
                current=Snapshot(key,data);data
            }
        }
    }
    fun text(context:Context,source:String,vararg args:Any):String {
        val data=load(context)
        val translated=data.getJSONObject("strings").optString(source,source)
        return if(args.isEmpty()) translated else String.format(Locale.forLanguageTag(data.getString("code")),translated,*args)
    }
    fun code(context:Context)=load(context).getString("code")
    fun name(context:Context)=load(context).getString("name")
    fun preview(context:Context)=load(context).optBoolean("preview")
    fun wrap(context:Context):Context {
        val config=Configuration(context.resources.configuration)
        val locale=Locale.forLanguageTag(code(context))
        config.setLocales(LocaleList(locale));config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
class LumaTextView(context:Context):androidx.appcompat.widget.AppCompatTextView(context) {
    var localize=true
    override fun setText(value:CharSequence?,type:BufferType?) {
        super.setText(if(localize && value is String) L10n.text(context,value) else value,type)
    }
}
class LumaButton(context:Context):com.google.android.material.button.MaterialButton(context) {
    private var ready=true
    override fun setText(value:CharSequence?,type:BufferType?) {
        super.setText(if(ready && value is String) L10n.text(context,value) else value,type)
    }
}
class LumaDialog(private val owner:Context):AlertDialog.Builder(owner) {
    private fun tr(s:CharSequence?)=s?.let {L10n.text(owner,it.toString())}
    override fun setTitle(title:CharSequence?):AlertDialog.Builder=super.setTitle(tr(title))
    override fun setMessage(message:CharSequence?):AlertDialog.Builder=super.setMessage(tr(message))
    override fun setPositiveButton(text:CharSequence?,listener:DialogInterface.OnClickListener?):AlertDialog.Builder=
        super.setPositiveButton(tr(text),listener)
    override fun setNegativeButton(text:CharSequence?,listener:DialogInterface.OnClickListener?):AlertDialog.Builder=
        super.setNegativeButton(tr(text),listener)
    override fun setNeutralButton(text:CharSequence?,listener:DialogInterface.OnClickListener?):AlertDialog.Builder=
        super.setNeutralButton(tr(text),listener)
    override fun setItems(items:Array<out CharSequence>?,listener:DialogInterface.OnClickListener?):AlertDialog.Builder=
        super.setItems(items?.map {tr(it) ?: ""}?.toTypedArray(),listener)
    override fun setSingleChoiceItems(items:Array<out CharSequence>?,checked:Int,listener:DialogInterface.OnClickListener?):AlertDialog.Builder=
        super.setSingleChoiceItems(items?.map {tr(it) ?: ""}?.toTypedArray(),checked,listener)
}
