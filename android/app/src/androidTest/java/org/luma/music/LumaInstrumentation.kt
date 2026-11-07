package org.luma.music

import android.app.*
import android.content.Intent
import android.os.*
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import org.json.JSONObject

/** Offline integration checks: actual activities, JNI, MediaPlayer and Android font fallback. */
class LumaInstrumentation:Instrumentation() {
    private var checks=0
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    private fun verify(value:Boolean,message:String) {check(value){message};checks++}
    private fun field(owner:Any,name:String):Any? =
        owner.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(owner)
    private fun waitUntil(message:String,timeout:Long=20000,predicate:()->Boolean) {
        val until=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<until) {
            if(predicate()) return
            Thread.sleep(100)
        }
        error(message)
    }
    override fun onStart() {
        val results=Bundle()
        try {
            runChecks()
            results.putString("stream","PASS Luma: "+checks+" integration assertions")
            finish(Activity.RESULT_OK,results)
        } catch(error:Throwable) {
            results.putString("stream","FAIL Luma: "+error.stackTraceToString())
            finish(Activity.RESULT_CANCELED,results)
        }
    }
    private fun runChecks() {
        verify(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0,
            "Target application is debuggable")
        runOnMainSync {SettingsStore.get(targetContext).edit().putBoolean("auto_update_check",false).apply()}
        val initial=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        TokenStore(targetContext).save("fixtureAccountA")
        val root=java.io.File(targetContext.filesDir,"offline")
        val dir=java.io.File(root,"999999999").apply {mkdirs()}
        val audio=java.io.File(dir,"999999998.mp3")
        context.assets.open("offline-fixture.mp3").use {source->audio.outputStream().use {source.copyTo(it)}}
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
            .joinToString("") {String.format(java.util.Locale.ROOT,"%02x",it.toInt() and 255)}
        java.io.File(dir,"index.json").writeText(org.json.JSONArray().put(JSONObject()
            .put("id","999999998").put("title","Offline fixture").put("artists","Luma")
            .put("bytes",audio.length()).put("sha256",digest).put("quality",128)).toString())
        TokenStore(targetContext,"offline_owner").save("999999999")
        verify(OfflineDownloads.list(targetContext).getJSONArray("items").length()==1,"Offline library missing")
        verify(OfflineDownloads.ready(targetContext,"999999998")==audio.absolutePath,"JNI did not verify cached audio")
        PlaybackService.queue=listOf(Song("999999998","Offline fixture","Luma"))
        PlaybackService.command(targetContext,"play",0)
        waitUntil("Downloaded MP3 did not play without an account") {(PlaybackService.instance?.duration() ?: 0)>1000}
        verify((PlaybackService.instance?.duration() ?: 0) in 2000..4000,"Unexpected offline MP3 duration")
        PlaybackService.command(targetContext,"stop")
        waitUntil("Offline service did not stop"){PlaybackService.instance==null}
        TokenStore(targetContext).save("fixtureAccountB")
        verify(TokenStore(targetContext,"offline_owner").load()==null,"Changing Yandex identity retained offline owner")
        waitUntil("Changing Yandex identity retained private audio"){!dir.exists()}
        TokenStore(targetContext).clear()
        runOnMainSync {initial.finish()}
        runOnMainSync {
            SettingsStore.get(targetContext).edit().putString("language","en")
                .putBoolean("auto_update_check",false).apply()
        }
        val main=startActivitySync(Intent(targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val whatsNew=startActivitySync(Intent(targetContext,WhatsNewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync {whatsNew.finish()}
        val downloads=startActivitySync(Intent(targetContext,OfflineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync {downloads.finish()}
        val spotify=startActivitySync(Intent(targetContext,SpotifyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync {spotify.finish()}
        runOnMainSync {main.javaClass.getDeclaredMethod("playDemo").apply {isAccessible=true}.invoke(main)}
        waitUntil("Demo MediaPlayer did not prepare") {(PlaybackService.instance?.duration() ?: 0)>0}
        runOnMainSync {if(PlaybackService.playing) PlaybackService.command(main,"toggle")}
        waitUntil("Demo did not pause") {!PlaybackService.playing}
        val lyrics=startActivitySync(Intent(targetContext,LyricsActivity::class.java)
            .putExtra("localOnly",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LyricsActivity
        val key=field(lyrics,"key") as String
        val fixture="[00:00]<00:00>Hello <00:01>world\n[00:04]Second line\n"+
            (5..40).joinToString("\n") {"[00:"+it.toString().padStart(2,'0')+".00]Line "+it}
        val file=java.io.File(targetContext.cacheDir,"integration.lrc").apply {writeText(fixture)}
        runOnMainSync {
            screenImport(lyrics,key,file)
        }
        waitUntil("Imported file did not reach the screen") {
            waitForIdleSync();field(lyrics,"document")!=null
        }
        val monitor=addMonitor(LyricsActivity::class.java.name,null,false)
        runOnMainSync {lyrics.recreate()}
        val screen=waitForMonitorWithTimeout(monitor,20000) as? LyricsActivity ?: error("Lyrics did not recreate")
        removeMonitor(monitor)
        waitForIdleSync()
        verify(field(screen,"document")!=null,"Rotation lost cached imported lyrics")
        val list=field(screen,"list") as ListView
        verify(list.adapter.count>10,"Imported lines not rendered")
        runOnMainSync {PlaybackService.instance?.seek(1500)}
        waitUntil("Seek did not reach first line") {PlaybackService.instance?.position()?.let {it in 1000..2500}==true}
        runOnMainSync {(field(screen,"tick") as Runnable).run()}
        waitForIdleSync()
        verify(field(screen,"active")==0,"Wrong line after seek")
        verify(field(screen,"words")==2,"Actual word starts not selected")
        runOnMainSync {
            val first=list.adapter.getView(0,null,list) as TextView
            val text=first.text
            verify(text is Spanned && text.getSpans(0,text.length,ForegroundColorSpan::class.java).isNotEmpty(),
                "Word highlight not rendered")
        }
        runOnMainSync {PlaybackService.instance?.seek(4500)}
        waitUntil("Seek did not reach second line") {PlaybackService.instance?.position()?.let {it in 4000..5500}==true}
        runOnMainSync {(field(screen,"tick") as Runnable).run()}
        verify(field(screen,"active")==1,"Line selection did not advance")
        runOnMainSync {PlaybackService.instance?.seek(500)}
        waitUntil("Backward seek failed") {PlaybackService.instance?.position()?.let {it in 0..900}==true}
        runOnMainSync {(field(screen,"tick") as Runnable).run()}
        verify(field(screen,"active")==0 && field(screen,"words")==1,"Backward seek kept stale word highlight")
        runOnMainSync {
            PlaybackService.queue=listOf(Song("other","Настройки","Избранное"))
            PlaybackService.index=0
            (field(screen,"tick") as Runnable).run()
            verify(list.adapter.count==0,"Old track lyrics remained after track changed")
            verify((field(screen,"title") as TextView).text.toString()=="Настройки","Track metadata was translated")
        }
        targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil("Lyrics stayed visible in background") {field(screen,"visible")==false}
        runOnMainSync {verify(!(field(screen,"handler") as Handler).hasCallbacks(field(screen,"tick") as Runnable),
            "Lyrics timer continues in background")}
        runOnMainSync {screen.finish();main.finish()}
        waitForIdleSync()
        val languages=NativeCore.call("languages","{}") as org.json.JSONArray
        verify(languages.length()==35,"Expected 35 languages")
        for(i in 0 until languages.length()) {
            val code=languages.getJSONObject(i).getString("code")
            runOnMainSync {
                SettingsStore.get(targetContext).edit().putString("language",code).apply()
                val context=L10n.wrap(targetContext)
                val view=LumaTextView(context).apply {text="Настройки"}
                verify(view.text.toString()==L10n.text(context,"Настройки"),"Widget language mismatch: "+code)
                val cp=view.text.toString().codePoints().filter {Character.isLetter(it)}.findFirst().orElse(65)
                verify(view.paint.hasGlyph(String(Character.toChars(cp))),"Missing system font glyph: "+code)
                val rtl=context.resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL
                verify(rtl==(code in listOf("ar","he","fa")),"Wrong direction: "+code)
            }
        }
        runOnMainSync {SettingsStore.get(targetContext).edit().putString("language","ar").apply()}
        val arabic=startActivitySync(Intent(targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitForIdleSync()
        runOnMainSync {
            verify(arabic.resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL,"Arabic Activity is not RTL")
            arabic.javaClass.getDeclaredMethod("settings",String::class.java).apply {isAccessible=true}.invoke(arabic,"about")
            arabic.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val root=field(arabic,"root") as LinearLayout
            fun texts(view:View):List<String> = if(view is TextView) listOf(view.text.toString())
                else if(view is android.view.ViewGroup) (0 until view.childCount).flatMap {texts(view.getChildAt(it))} else emptyList()
            val labels=texts(root)
            verify(labels.any {it=="made by Romeo13card"},"About credit is missing")
            verify(labels.any {it=="التطبيق"},"About language not applied")
        }
        uiAutomation.takeScreenshot()?.let {bitmap ->
            java.io.File(targetContext.getExternalFilesDir(null),"about-ar.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
        waitUntil("Selected language was not persisted") {
            (NativeDatabase(targetContext).call("settings_load") as JSONObject).optString("language")=="ar"
        }
        // Return to the source language for normal manual testing after the suite.
        runOnMainSync {
            SettingsStore.get(targetContext).edit().putString("language","ru").apply()
            PlaybackService.command(targetContext,"stop")
            arabic.finish()
        }
        waitUntil("Final language was not saved") {
            (NativeDatabase(targetContext).call("settings_load") as JSONObject).optString("language")=="ru"
        }
    }
    private fun screenImport(activity:LyricsActivity,key:String,file:java.io.File) {
        activity.javaClass.getDeclaredField("pendingImportKey").apply {isAccessible=true}.set(activity,key)
        activity.javaClass.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,21,Activity.RESULT_OK,Intent().setData(android.net.Uri.fromFile(file)))
    }
}
