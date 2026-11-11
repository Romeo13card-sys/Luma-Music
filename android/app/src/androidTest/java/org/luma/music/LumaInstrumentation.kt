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
    private var liveArtwork=false
    private var fastSwitchMs=0L
    private var warmPssKb=0
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
        error(message+"; index="+PlaybackService.index+", playing="+PlaybackService.playing+"; "+PlaybackService.instance?.debugState())
    }
    override fun onStart() {
        val results=Bundle()
        try {
            runChecks()
            results.putString("stream","PASS Luma: "+checks+" integration assertions; live CDN artwork="+liveArtwork+"; warm switch="+fastSwitchMs+"ms; warm PSS="+warmPssKb+"KiB")
            finish(Activity.RESULT_OK,results)
        } catch(error:Throwable) {
            results.putString("stream","FAIL Luma: "+error.stackTraceToString())
            finish(Activity.RESULT_CANCELED,results)
        }
    }
    private fun runChecks() {
        val icon=targetContext.packageManager.getApplicationIcon(targetContext.packageName)
        verify(icon is android.graphics.drawable.AdaptiveIconDrawable,"Launcher icon is not adaptive")
        if(Build.VERSION.SDK_INT>=33)verify((icon as android.graphics.drawable.AdaptiveIconDrawable).monochrome!=null,"Monochrome launcher icon is missing")
        verify(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0,
            "Target application is debuggable")
        runOnMainSync {SettingsStore.get(targetContext).edit().putBoolean("auto_update_check",false).putString("theme_mode","dark").putBoolean("cover_colors",true).apply()}
        val initial=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val cover="https://avatars.yandex.net/get-music-content/42/luma-fixture-a/%%"
        val otherCover="https://avatars.yandex.net/get-music-content/42/luma-fixture-b/%%"
        seedCover(cover,0xff267aad.toInt());seedCover(otherCover,0xffd34946.toInt())
        // A live public default cover exercises Rust HTTPS without any account credentials.
        runCatching {
            val args=JSONObject().put("root",java.io.File(targetContext.cacheDir,"covers").absolutePath)
                .put("cover","avatars.yandex.net/get-music-content/default/m/%%").put("size",100).put("network",true)
            val path=NativeCore.call("cover_load",args.toString()) as? String
            path?.let {android.graphics.BitmapFactory.decodeFile(it)}?.let {bitmap->
                liveArtwork=bitmap.width>0 && bitmap.height>0
                bitmap.recycle()
            }
        }

        TokenStore(targetContext).save("fixtureAccountA")
        val root=java.io.File(targetContext.filesDir,"offline")
        val dir=java.io.File(root,"999999999").apply {mkdirs()}
        val audio=java.io.File(dir,"999999998.mp3")
        context.assets.open("offline-fixture.mp3").use {source->audio.outputStream().use {source.copyTo(it)}}
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
            .joinToString("") {String.format(java.util.Locale.ROOT,"%02x",it.toInt() and 255)}
        java.io.File(dir,"index.json").writeText(org.json.JSONArray().put(JSONObject()
            .put("id","999999998").put("title","Offline fixture").put("artists","Luma")
            .put("bytes",audio.length()).put("sha256",digest).put("quality",128).put("cover",cover)).toString())
        TokenStore(targetContext,"offline_owner").save("999999999")
        verify(OfflineDownloads.list(targetContext).getJSONArray("items").length()==1,"Offline library missing")
        verify(OfflineDownloads.ready(targetContext,"999999998")==audio.absolutePath,"JNI did not verify cached audio")
        verify(OfflineDownloads.list(targetContext).getJSONArray("items").getJSONObject(0).getString("cover")==cover,"Offline index lost artwork")
        PlaybackService.queue=listOf(Song("999999998","Offline fixture","Luma",cover=cover))
        PlaybackService.command(targetContext,"play",0)
        waitUntil("Downloaded MP3 did not play without an account") {(PlaybackService.instance?.duration() ?: 0)>1000}
        verify((PlaybackService.instance?.duration() ?: 0) in 2000..4000,"Unexpected offline MP3 duration")
        checkArtwork(initial,cover,otherCover)
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
        verify((field(PlaybackService.instance!!,"session") as android.media.session.MediaSession).controller.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)==null,"Previous track artwork remained in MediaSession")

        runOnMainSync {if(PlaybackService.playing) PlaybackService.command(main,"toggle")}
        waitUntil("Demo did not pause") {!PlaybackService.playing}
        checkQueueAndWave(main)
        checkPreparedPlayback(main)
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
    private fun checkQueueAndWave(main:MainActivity) {
        val demo=PlaybackService.queue.single()
        val second=demo.copy(id="demo-b",title="Second local track")
        val third=demo.copy(id="demo-c",title="Third local track")
        val player=field(PlaybackService.instance!!,"player")
        runOnMainSync {PlaybackService.instance!!.seek(1500)}
        waitUntil("Queue fixture seek failed"){(PlaybackService.instance?.position() ?: 0) in 1200..2000}
        runOnMainSync {
            PlaybackService.editQueue(main,"append",second)
            PlaybackService.editQueue(main,"next",third)
            verify(PlaybackService.queue.map {it.id}==listOf("demo","demo-c","demo-b"),"Play next did not insert after active slot")
            PlaybackService.editQueue(main,"move",at=0,to=2)
            verify(PlaybackService.index==2 && PlaybackService.queue[2].id=="demo","Move lost current queue slot")
            PlaybackService.editQueue(main,"remove",at=0)
            verify(PlaybackService.index==1 && PlaybackService.queue.size==2,"Remove before current changed selection")
            verify(field(PlaybackService.instance!!,"player")===player,"Metadata edit replaced active audio")
            verify(!PlaybackService.playing && PlaybackService.instance!!.position() in 1200..2000,"Queue edit lost paused position")
        }
        val queue=startActivitySync(Intent(targetContext,QueueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QueueActivity
        waitForIdleSync()
        runOnMainSync {
            val adapter=field(queue,"adapter") as BaseAdapter
            verify(adapter.count==2,"Queue screen did not render items")
            val row=adapter.getView(1,null,LinearLayout(queue)) as SongRow
            verify(row.marker.visibility==View.VISIBLE && !row.more.isFocusable,"Queue current marker or row focus is wrong")
            val stale=adapter.getView(0,null,LinearLayout(queue))
            PlaybackService.editQueue(queue,"append",third)
            fun findList(view:View):ListView? {
                if(view is ListView)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)findList(view.getChildAt(i))?.let {return it}
                return null
            }
            findList(queue.window.decorView)!!.performItemClick(stale,0,0)
        }
        waitForIdleSync();Thread.sleep(500)
        runOnMainSync {
            verify(!PlaybackService.playing && PlaybackService.index==1 && field(PlaybackService.instance!!,"player")===player,
                "A stale queue row played another track")
            PlaybackService.editQueue(queue,"remove",at=2)
            val sheet=TrackSheet.show(queue,PlaybackService.queue[0],0)
            PlaybackService.editQueue(queue,"append",third)
            textView(sheet.window!!.decorView,L10n.text(queue,"Удалить из очереди")).performClick()
            verify(PlaybackService.queue.size==3,"Stale action sheet removed another queue slot")
            verify(textView(sheet.window!!.decorView,L10n.text(queue,"Очередь изменилась. Откройте меню заново.")).visibility==View.VISIBLE,"Stale menu warning is missing")
            sheet.dismiss();queue.finish()
            PlaybackService.editQueue(main,"remove",at=PlaybackService.index)
            verify(!PlaybackService.playing && field(PlaybackService.instance!!,"player")==null,"Removing paused current item started audio")
            PlaybackService.editQueue(main,"remove",at=0)
            PlaybackService.editQueue(main,"remove",at=0)
        }
        waitUntil("Empty queue left a service running"){PlaybackService.instance==null}
        waitUntil("Empty queue was not persisted"){QueueStore(targetContext).load()==null}
        runOnMainSync {PlaybackService.select(main,listOf(demo,second),0)}
        waitUntil("Queue fixture did not prepare"){PlaybackService.playing && (PlaybackService.instance?.duration() ?: 0)>0}
        runOnMainSync {PlaybackService.command(main,"toggle")}
        waitUntil("Queue fixture did not pause"){!PlaybackService.playing}
        runOnMainSync {
            PlaybackService.repeat=true
            PlaybackService.command(main,"next")
        }
        waitUntil("Manual next did not advance while paused"){PlaybackService.index==1 && (PlaybackService.instance?.duration() ?: 0)>0}
        verify(!PlaybackService.playing,"Manual next unexpectedly resumed playback")
        runOnMainSync {PlaybackService.instance!!.seek(6000)}
        waitUntil("Previous fixture seek failed"){(PlaybackService.instance?.position() ?: 0)>5500}
        runOnMainSync {PlaybackService.command(main,"previous")}
        waitUntil("Previous did not restart current song"){(PlaybackService.instance?.position() ?: 9999)<1000}
        verify(PlaybackService.index==1 && !PlaybackService.playing,"Previous restart switched track or resumed")
        runOnMainSync {PlaybackService.command(main,"previous")}
        waitUntil("Previous near start did not change song"){PlaybackService.index==0 && (PlaybackService.instance?.duration() ?: 0)>0}
        runOnMainSync {
            main.onBackPressed()
            verify(field(main,"playerScreen")==false,"Back did not collapse full player")
        }
        waitUntil("Mini player was not laid out") {
            var ready=false;runOnMainSync {val label=field(main,"mini") as View;ready=label.isShown && label.width>MusicUi.dp(main,80)};ready
        }
        val bounds=IntArray(4)
        runOnMainSync {
            val label=(field(main,"mini") as View).parent as View
            val location=IntArray(2);label.getLocationOnScreen(location)
            bounds[0]=location[0]+label.width-MusicUi.dp(main,8);bounds[1]=location[0]+MusicUi.dp(main,8)
            bounds[2]=location[1]+label.height/2
        }
        val down=SystemClock.uptimeMillis()
        // A deliberate slow swipe must work as well as a fling; inject real window events.
        for(i in 0..8){
            val x=bounds[0]+(bounds[1]-bounds[0])*i/8f
            val action=if(i==0)MotionEvent.ACTION_DOWN else if(i==8)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,bounds[2].toFloat(),0)
            sendPointerSync(event);event.recycle();Thread.sleep(40)
        }
        waitUntil("Mini player swipe did not switch songs"){PlaybackService.index==1 && (PlaybackService.instance?.duration() ?: 0)>0}
        verify(!PlaybackService.playing,"Swipe unexpectedly resumed paused playback")
        var selected=false
        runOnMainSync {
            val dialog=WaveSheet.show(main){selected=true}
            val root=dialog.window!!.decorView
            textView(root,L10n.text(main,"Спокойное")).performClick()
            textView(root,L10n.text(main,"Слушать подборку")).performClick()
            verify(selected && SettingsStore.get(main).getString("wave_station",null)=="mood:calm","Wave preset was not persisted")
            SettingsStore.get(main).edit().putString("wave_station","user:onyourwave").apply()
            PlaybackService.repeat=false
            PlaybackService.select(main,listOf(demo),0)
        }
        waitUntil("Demo not restored after queue tests"){PlaybackService.playing && (PlaybackService.instance?.duration() ?: 0)>0}
        runOnMainSync {PlaybackService.command(main,"toggle")}
        waitUntil("Restored demo did not pause"){!PlaybackService.playing}
    }
    private fun checkPreparedPlayback(main:MainActivity){
        val demo=PlaybackService.queue.single()
        val second=demo.copy(id="warm-next",title="Prepared local track")
        runOnMainSync {
            SettingsStore.get(main).edit().putBoolean("preload_next",true).putBoolean("preload_wifi_only",false).apply()
            PlaybackService.select(main,listOf(demo,second),0)
        }
        waitUntil("Preload fixture did not start"){PlaybackService.playing && (PlaybackService.instance?.duration() ?: 0)>0}
        waitUntil("Next local track was not prepared") {
            (field(PlaybackService.instance!!,"nextBuffer") as NextTrackBuffer).readyKey!=null
        }
        val ready=field(field(PlaybackService.instance!!,"nextBuffer")!!,"candidate")
        warmPssKb=android.os.Debug.MemoryInfo().also {android.os.Debug.getMemoryInfo(it)}.totalPss
        val before=SystemClock.uptimeMillis()
        runOnMainSync {PlaybackService.command(main,"next")}
        waitUntil("Prepared next track did not start") {PlaybackService.index==1 && PlaybackService.playing}
        fastSwitchMs=SystemClock.uptimeMillis()-before
        verify(field(PlaybackService.instance!!,"player")===ready,"Switch discarded the prepared MediaPlayer")
        val audio=main.getSystemService(android.media.AudioManager::class.java)
        val competing=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .setOnAudioFocusChangeListener {}.build()
        runOnMainSync {verify(audio.requestAudioFocus(competing)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"Transient focus request failed")}
        waitUntil("Player did not pause for transient audio focus"){!PlaybackService.playing}
        runOnMainSync {audio.abandonAudioFocusRequest(competing)}
        waitUntil("Player did not resume after transient focus returned"){PlaybackService.playing}
        verify(field(PlaybackService.instance!!,"player")===ready,"Transient focus loss recreated the decoder")
        runOnMainSync {PlaybackService.command(main,"toggle")}
        waitUntil("Warm fixture did not pause"){!PlaybackService.playing}
        verify((field(PlaybackService.instance!!,"nextBuffer") as NextTrackBuffer).readyKey==null,"Paused player retained next decoder")
        val player=field(PlaybackService.instance!!,"player")
        runOnMainSync {
            val service=PlaybackService.instance!!
            service.javaClass.getDeclaredMethod("load",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                .apply {isAccessible=true}.invoke(service,0,false)
            PlaybackService.editQueue(main,"remove",at=0)
        }
        waitForIdleSync();Thread.sleep(250)
        verify(PlaybackService.index==0 && PlaybackService.queue[0].id==second.id && !PlaybackService.playing && field(PlaybackService.instance!!,"player")===player,
            "An obsolete preparation replaced audio after a queue edit")
        runOnMainSync {PlaybackService.select(main,listOf(demo),0)}
        waitUntil("Demo not restored after preload test"){PlaybackService.playing && (PlaybackService.instance?.duration() ?: 0)>0}
        runOnMainSync {PlaybackService.command(main,"toggle")}
        waitUntil("Demo did not pause after preload test"){!PlaybackService.playing}
        for(section in listOf("audio","network","downloads","wave","lyrics"))runOnMainSync {
            main.javaClass.getDeclaredMethod("settings",String::class.java).apply {isAccessible=true}.invoke(main,section)
            verify((field(main,"body") as LinearLayout).childCount>1,"New settings section is empty: "+section)
        }
        runOnMainSync {main.javaClass.getDeclaredMethod("fullPlayer").apply {isAccessible=true}.invoke(main)}
    }
    private fun textView(root:View,text:String):TextView {
        fun find(view:View):TextView? {
            if(view is TextView && view.text.toString()==text)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let {return it}
            return null
        }
        return find(root) ?: error("Missing UI label: "+text)
    }
    private fun seedCover(cover:String,color:Int) {
        val root=java.io.File(targetContext.cacheDir,"covers").apply {mkdirs()}
        val image=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(image);canvas.drawColor(color)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {this.color=android.graphics.Color.WHITE;textSize=72f;typeface=android.graphics.Typeface.DEFAULT_BOLD}
        canvas.drawText("LUMA",126f,280f,paint)
        for(size in intArrayOf(100,400)) {
            val info=NativeCore.call("cover_info",JSONObject().put("cover",cover).put("size",size).toString()) as JSONObject
            java.io.File(root,info.getString("file")).outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            verify(NativeCore.call("cover_load",JSONObject().put("root",root.absolutePath).put("cover",cover).put("size",size).put("network",false).toString()) is String,"Artwork disk cache unavailable offline")
        }
        image.recycle()
    }
    private fun screenshot(activity:Activity,name:String) {
        val decor=activity.window.decorView
        waitUntil("Screenshot window was not laid out") {
            var ready=false;runOnMainSync {ready=decor.isLaidOut && decor.width>0 && decor.height>0};ready
        }
        var original:android.graphics.Bitmap?=null
        runOnMainSync {
            val bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap));original=bitmap
        }
        original?.let {bitmap->
            val small=android.graphics.Bitmap.createScaledBitmap(bitmap,400,bitmap.height*400/bitmap.width,true)
            java.io.File(targetContext.getExternalFilesDir(null),name).outputStream().use {
                small.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY,55,it)
            }
            if(small!==bitmap)small.recycle();bitmap.recycle()
        }
    }
    private fun checkArtwork(activity:MainActivity,cover:String,other:String) {
        runOnMainSync {activity.javaClass.getDeclaredMethod("fullPlayer").apply {isAccessible=true}.invoke(activity)}
        val art=field(activity,"playerCover") as AlbumArt
        waitUntil("Player artwork did not decode") {waitForIdleSync();art.image!=null}
        verify(art.image!!.bitmap.width<=400 && art.image!!.bitmap.height<=400,"Player decoded oversized artwork")
        runOnMainSync {
            val root=field(activity,"root") as LinearLayout
            verify((root.background as android.graphics.drawable.ColorDrawable).color==art.image!!.dark.surface,"Player background does not follow artwork")
            val play=field(activity,"playButton") as TextView
            verify(play.backgroundTintList!!.defaultColor==art.image!!.dark.accent,"Play button lacks artwork accent")
        }
        waitUntil("MediaSession artwork missing") {
            val service=PlaybackService.instance ?: return@waitUntil false
            (field(service,"session") as android.media.session.MediaSession).controller.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)!=null
        }
        val metadata=(field(PlaybackService.instance!!,"session") as android.media.session.MediaSession).controller.metadata!!
        verify(metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)!!.width<=100,"Notification artwork exceeds thumbnail bound")
        val stored=QueueStore.decode(QueueStore.encode(QueueStore.Snapshot(PlaybackService.queue,0,0)))!!
        verify(stored.songs[0].cover==cover,"Saved queue lost artwork")
        screenshot(activity,"cover-dark.webp")
        val held=art.image!!
        val downloads=startActivitySync(Intent(targetContext,OfflineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil("Player window did not hide"){art.windowVisibility!=View.VISIBLE}
        runOnMainSync {
            art.artworkLoaded(CoverImages.key(cover,400),held)
            verify(art.image==null,"Late artwork retained a bitmap in a hidden screen")
            downloads.finish()
        }
        waitUntil("Player artwork did not return from background"){waitForIdleSync();art.image!=null}
        runOnMainSync {art.bind(other,true);art.bind(cover,true)}
        waitUntil("Artwork rebinding did not finish") {waitForIdleSync();art.image!=null}
        verify(android.graphics.Color.blue(art.image!!.bitmap.getPixel(0,0))>android.graphics.Color.red(art.image!!.bitmap.getPixel(0,0)),"A stale artwork request replaced the current track")
        runOnMainSync {
            art.bind(null,true)
            verify(art.image==null,"Missing cover retained previous track artwork")
            art.bind(cover,true)
        }
        waitUntil("Artwork not restored from memory cache") {waitForIdleSync();art.image!=null}
        verify(CoverImages.memoryBytes() in 1..2*1024*1024,"Artwork memory cache exceeds cap")
        verify(CoverImages.queuedJobs()<=63,"Artwork job queue exceeds cap")
        // The fixture account is never sent to a service while checking the light screen.
        TokenStore(targetContext).clear()
        runOnMainSync {SettingsStore.get(targetContext).edit().putString("theme_mode","light").apply();activity.finish()}
        val light=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        runOnMainSync {light.javaClass.getDeclaredMethod("fullPlayer").apply {isAccessible=true}.invoke(light)}
        val lightArt=field(light,"playerCover") as AlbumArt
        waitUntil("Light player artwork missing") {waitForIdleSync();lightArt.image!=null}
        runOnMainSync {verify(((field(light,"root") as LinearLayout).background as android.graphics.drawable.ColorDrawable).color==lightArt.image!!.light.surface,"Light artwork palette did not apply")}
        screenshot(light,"cover-light.webp")
        runOnMainSync {light.finish();SettingsStore.get(targetContext).edit().putString("theme_mode","system").apply()}
    }
    private fun screenImport(activity:LyricsActivity,key:String,file:java.io.File) {
        activity.javaClass.getDeclaredField("pendingImportKey").apply {isAccessible=true}.set(activity,key)
        activity.javaClass.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,21,Activity.RESULT_OK,Intent().setData(android.net.Uri.fromFile(file)))
    }
}
