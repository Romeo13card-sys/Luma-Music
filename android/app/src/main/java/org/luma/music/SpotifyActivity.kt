package org.luma.music

import android.content.*
import android.net.Uri
import android.os.*
import android.text.InputFilter
import android.view.*
import android.widget.*
import com.google.android.material.button.MaterialButton
import org.json.*
import java.security.SecureRandom
import java.util.concurrent.Executors
import android.util.Base64

private object SpotifyCredentialLock { val value=Any() }

/** Separate OAuth credentials; playback is explicitly handed to the installed Spotify app. */
class SpotifyActivity:MaterialActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private var busy=false
    private var epoch=0
    private fun t(s:String)=L10n.text(this,s)
    private val auth get()=TokenStore(this,"spotify_auth")
    private val stamp get()=TokenStore(this,"spotify_epoch")
    private val pending get()=TokenStore(this,"spotify_pending")
    private val config get()=getSharedPreferences("spotify_config",MODE_PRIVATE)
    private fun label(value:String,size:Float=16f)=TextView(this).apply {text=value;textSize=size;setPadding(0,10,0,10);setTextIsSelectable(true)}
    private fun button(name:String,raw:Boolean=false,action:()->Unit){
        content.addView(MaterialButton(this).apply {text=if(raw)name else t(name);setOnClickListener {if(!busy)action()}})
    }
    private fun draw(){
        epoch++
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16)}
        val scroll=ScrollView(this)
        content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        content.addView(label("Spotify",30f))
        content.addView(label(t("Отдельный аккаунт Spotify. Яндекс Музыка остаётся подключённой.")))
        content.addView(label(t("Воспроизведение открывается в приложении Spotify. Скачивание Spotify в Luma недоступно."),14f))
        status=label("");content.addView(status)
        val client=EditText(this).apply {
            hint="Spotify Client ID";setSingleLine();filters=arrayOf(InputFilter.LengthFilter(32))
            setText(config.getString("client_id",BuildConfig.SPOTIFY_CLIENT_ID))
        }
        content.addView(client)
        content.addView(label(t("Redirect URI для Spotify Dashboard:")+"\norg.luma.music://spotify-callback",13f))
        button("Войти в Spotify"){
            val id=client.text.toString().trim()
            try{
                val random=SecureRandom()
                fun secret():String {val b=ByteArray(48);random.nextBytes(b);return Base64.encodeToString(b,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
                val session=JSONObject().put("client_id",id).put("state",secret()).put("verifier",secret())
                    .put("started",System.currentTimeMillis()/1000)
                val data=NativeCore.call("spotify_start",session.toString()) as JSONObject
                synchronized(SpotifyCredentialLock.value) {
                    stamp.save(session.getString("state"))
                    pending.save(session.toString())
                }
                config.edit().putString("client_id",id).apply()
                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(data.getString("url"))))
            }catch(e:Exception){status.text=e.message}
        }
        if(!auth.load().isNullOrBlank()){
            button("Мои плейлисты"){library("spotify_playlists",0)}
            button("Избранное Spotify"){library("spotify_likes",0)}
            button("Отключить Spotify"){
                synchronized(SpotifyCredentialLock.value){stamp.save(java.util.UUID.randomUUID().toString());auth.clear();pending.clear()}
                draw()
            }
        }
        button("Открыть Spotify"){openSpotify("spotify:app:home")}
        button("Назад"){finish()}
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener {v,i->val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(24+b.left,16+b.top,24+b.right,16+b.bottom);i}
        setContentView(root);root.requestApplyInsets()
    }
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit){
        if(busy)return
        busy=true;status.text=t("Загрузка…");val generation=epoch
        worker.execute{
            val reply=runCatching {work()}
            runOnUiThread{
                busy=false
                if(isDestroyed || generation!=epoch)return@runOnUiThread
                reply.onSuccess {status.text="";done(it)}.onFailure {status.text=it.message}
            }
        }
    }
    private fun credentials():JSONObject{
        val original=auth.load() ?: error(t("Войдите в Spotify"))
        val epoch=stamp.load()
        var data=JSONObject(original)
        if(data.optLong("expires_at")<=System.currentTimeMillis()/1000+60){
            data=NativeCore.call("spotify_refresh",data.toString()) as JSONObject
            data.put("expires_at",System.currentTimeMillis()/1000+data.getLong("expires_in"))
            synchronized(SpotifyCredentialLock.value) {
                check(stamp.load()==epoch && auth.load()==original) {"Сессия Spotify изменилась"}
                auth.save(data.toString())
            }
        }
        return data
    }
    private fun callback(intent:Intent?){
        val uri=intent?.data ?: return
        // Do not retain codes in Activity state or subsequent intents.
        setIntent(Intent(this,SpotifyActivity::class.java))
        val raw=pending.load() ?: run {status.text=t("Начните вход в Spotify заново");return}
        try{
            val session=JSONObject(raw).put("url",uri.toString()).put("now",System.currentTimeMillis()/1000)
            val code=NativeCore.call("spotify_callback",session.toString()) as JSONObject
            pending.clear() // Accepted codes/states are consumed before any token request.
            session.put("code",code.getString("code"))
            task({
                val tokens=NativeCore.call("spotify_exchange",session.toString()) as JSONObject
                tokens.put("expires_at",System.currentTimeMillis()/1000+tokens.getLong("expires_in"))
                val profile=NativeCore.call("spotify_profile",tokens.toString()) as JSONObject
                synchronized(SpotifyCredentialLock.value) {
                    check(stamp.load()==session.getString("state")) {"Вход Spotify отменён"}
                    auth.save(tokens.toString())
                }
                profile
            }){profile->draw();status.text=profile.optString("name","Spotify")}
        }catch(e:Exception){status.text=e.message}
    }
    private fun library(op:String,offset:Int){
        task({
            NativeCore.call(op,credentials().put("offset",offset).toString()) as JSONObject
        }){data->
            draw()
            content.addView(label(t(if(op=="spotify_likes")"Избранное Spotify" else "Мои плейлисты"),22f))
            val items=data.getJSONArray("items")
            if(items.length()==0)content.addView(label(t("Здесь пока нет музыки")))
            for(i in 0 until items.length()){
                val item=items.getJSONObject(i)
                button(item.getString("name"),true){openSpotify(item.getString("uri"))}
            }
            if(offset>0)button("‹ Назад"){library(op,(offset-20).coerceAtLeast(0))}
            if(data.optBoolean("has_more"))button("Ещё ›"){library(op,offset+20)}
        }
    }
    private fun openSpotify(uri:String){
        stopService(Intent(this,PlaybackService::class.java))
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(uri)).setPackage("com.spotify.music"))}
        catch(_:ActivityNotFoundException){
            status.text=t("Для воспроизведения установите Spotify")
            if(uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:track:")){
                val pieces=uri.split(':')
                runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://open.spotify.com/"+pieces[1]+"/"+pieces[2])))}
            }
        }
    }
    override fun onCreate(state:Bundle?){super.onCreate(state);draw();callback(intent)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);callback(intent)}
    override fun onDestroy(){epoch++;worker.shutdownNow();super.onDestroy()}
}
