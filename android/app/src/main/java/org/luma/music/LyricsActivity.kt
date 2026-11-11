package org.luma.music

import android.app.*
import android.os.*
import android.view.*
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.json.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One shared worker and at most one pending request, including across rotations. */
private object LyricsRequests {
    val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())
}

/** Android renders and seeks; parsing, caching and timeline selection belong to Rust. */
class LyricsActivity : MaterialActivity() {
    private val preferences by lazy { SettingsStore.get(this) }
    private val colors get() = AppThemes.resolve(preferences.getString("theme_mode","system").orEmpty(),
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var message: TextView
    private lateinit var list: ListView
    private lateinit var followButton: Button
    private lateinit var retry: Button
    private lateinit var adapter: Lines
    private val handler = Handler(Looper.getMainLooper())
    private var document: JSONObject? = null
    private var key = ""
    @Volatile private var serial = 0
    @Volatile private var alive = true
    private var visible = false
    private var follow = true
    private var active = -1
    private var words = 0
    private var lastPosition = -1
    private var loading = false
    private var localOnly = false
    private var pendingImportKey = ""
    private var lastError: String? = null
    private val economy by lazy {
        val manager=getSystemService(ActivityManager::class.java)
        val memory=ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        RenderPolicy.choose(manager.isLowRamDevice,memory.totalMem,manager.memoryClass).economy
    }
    private var restoredList: Parcelable? = null
    private fun dp(n:Int) = (n*resources.displayMetrics.density).toInt()
    private fun label(size:Float) = LumaTextView(this).apply {
        textSize=size*preferences.getInt("font_size",100)/100f
        setTextColor(colors.text);setPadding(dp(18),dp(12),dp(18),dp(12))
    }
    private fun song() = PlaybackService.queue.getOrNull(PlaybackService.index)
    private fun key(song:Song) = song.id + "\u0000" + song.title + "\u0000" + song.artist

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(L10n.wrap(base))
    }
    private fun t(source: String, vararg args: Any) = L10n.text(this, source, *args)

    override fun onCreate(state:Bundle?) {
        setTheme(if(colors.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
        super.onCreate(state)
        follow=state?.getBoolean("follow",true) ?: true
        localOnly=state?.getBoolean("localOnly") ?: intent.getBooleanExtra("localOnly",false)
        pendingImportKey=state?.getString("pendingImportKey").orEmpty()
        @Suppress("DEPRECATION")
        restoredList=state?.getParcelable("list")
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.background)}
        root.addView(LumaButton(this).apply {text="‹ К плееру";setOnClickListener {finish()}})
        title=label(24f).apply { localize=false };root.addView(title)
        subtitle=label(13f).apply { localize=false };root.addView(subtitle)
        message=label(14f).apply {accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        root.addView(message)
        adapter=Lines()
        list=ListView(this).apply {
            divider=null;adapter=this@LyricsActivity.adapter;clipToPadding=false
            setPadding(0,dp(24),0,dp(80))
            setOnItemClickListener {_,_,position,_ ->
                val doc=document ?: return@setOnItemClickListener
                if(song()?.let {key(it)} != key) return@setOnItemClickListener
                val lines=doc.optJSONArray("lines") ?: return@setOnItemClickListener
                val line=lines.optJSONObject(position) ?: return@setOnItemClickListener
                val player=PlaybackService.instance
                val duration=player?.duration() ?: 0
                if(duration>0) {
                    player?.seek((line.optDouble("start",0.0)*1000).toInt().coerceIn(0,duration))
                    follow=true;lastPosition=-1
                } else Toast.makeText(this@LyricsActivity,t("Перемотка для этого потока пока недоступна"),Toast.LENGTH_SHORT).show()
            }
            setOnScrollListener(object:AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view:AbsListView,state:Int) {
                    if(state==AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {follow=false;followButton.visibility=View.VISIBLE}
                }
                override fun onScroll(v:AbsListView?,first:Int,count:Int,total:Int) {}
            })
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        followButton=LumaButton(this).apply {
            text="К текущей строке";visibility=View.GONE
            setOnClickListener {follow=true;visibility=View.GONE;center()}
        }
        root.addView(followButton)
        retry=LumaButton(this).apply {text="Повторить";visibility=View.GONE;setOnClickListener {load()}}
        root.addView(retry)
        root.addView(LumaButton(this).apply {
            text="LRC / TXT · "+t("Открыть")
            setOnClickListener {
                pendingImportKey=song()?.let {key(it)}.orEmpty()
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*").addCategory(android.content.Intent.CATEGORY_OPENABLE),21)
            }
        })
        setContentView(root)
        val bars=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if(colors.light) bars else 0,bars)
        root.setOnApplyWindowInsetsListener {view,insets ->
            val b=insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(b.left,b.top,b.right,b.bottom);insets
        }
        root.requestApplyInsets()
        load()
    }
    private fun load() {
        val song=song()
        serial++;val request=serial
        releaseDocument()
        document=null;active=-1;words=0;lastPosition=-1;lastError=null
        adapter.notifyDataSetChanged();retry.visibility=View.GONE;followButton.visibility=View.GONE
        if(song==null) {key="";title.text=t("Текст песни");message.text="Сначала выберите трек";return}
        key=key(song);val requestedKey=key
        title.text=song.title;subtitle.text=song.artist
        if(localOnly) {
            loading=false
            val cached=runCatching {NativeCore.call("lyrics_cached",JSONObject().put("key",key).toString()) as? JSONObject}.getOrNull()
            if(cached?.optString("source")=="file") {document=cached;display()}
            else message.text="LRC / TXT · "+t("Открыть")
            return
        }
        message.text="Ищем в LrcLib…";loading=true
        val args=JSONObject().put("key",key).put("title",song.title).put("artist",song.artist).toString()
        LyricsRequests.worker.execute {
            if(!alive || request!=serial) return@execute
            val result=runCatching {
                (NativeCore.call("lyrics_cached",args) as? JSONObject)
                    ?: NativeCore.call("lyrics",args) as JSONObject
            }
            runOnUiThread {
                val doc=result.getOrNull()
                if(!alive || request!=serial || requestedKey!=key || requestedKey!=song()?.let {key(it)}) {
                    if(!isChangingConfigurations && doc!=null)
                        runCatching {NativeCore.call("lyrics_release",JSONObject().put("session",doc.optLong("session")).toString())}
                    return@runOnUiThread
                }
                loading=false;document=doc;lastError=result.exceptionOrNull()?.message
                if(visible) display(lastError)
            }
        }
    }
    private fun display(error:String?=null) {
        val doc=document
        message.text=if(doc?.optString("source")=="file") "LRC / TXT"
            else if(doc==null && localOnly && error==null) "LRC / TXT · "+t("Открыть")
            else if(doc==null) error ?: "Текст не найден"
            else if(doc.getJSONArray("lines").length()==0) "LrcLib · текст без синхронизации"
            else if(doc.optBoolean("wordSync")) "LrcLib · синхронизация слов"
            else "LrcLib · синхронизация строк"
        retry.visibility=if(doc==null && !loading) View.VISIBLE else View.GONE
        followButton.visibility=if(doc!=null && doc.getJSONArray("lines").length()>0 && !follow) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        restoredList?.let {list.onRestoreInstanceState(it);restoredList=null}
    }
    private fun center() {
        if(follow) list.setSelectionFromTop(active.coerceAtLeast(0),list.height/3)
    }
    private val tick=object:Runnable {
        override fun run() {
            if(!visible) return
            val current=song()?.let {key(it)}.orEmpty()
            if(current!=key) {follow=true;load()}
            val doc=document
            val position=PlaybackService.instance?.position() ?: 0
            if(doc!=null && position!=lastPosition) {
                lastPosition=position
                val cursor=runCatching {NativeCore.call("lyrics_cursor",
                    JSONObject().put("session",doc.optLong("session")).put("positionMs",position).toString()) as JSONObject}.getOrNull()
                if(cursor!=null) {
                    val line=cursor.optInt("line",-1);val count=cursor.optInt("words")
                    if(line!=active || count!=words) {
                        val moved=line!=active;active=line;words=count;adapter.notifyDataSetChanged()
                        if(moved && follow) center()
                    }
                }
            }
            handler.postDelayed(this,if(doc?.optJSONArray("lines")?.length()==0) 1000
                else if(preferences.getBoolean("reduce_effects",false) || economy) 500 else 250)
        }
    }
    private inner class Lines:BaseAdapter() {
        override fun getCount():Int {
            val doc=document ?: return 0
            return doc.getJSONArray("lines").length().takeIf {it>0} ?: (doc.optJSONArray("plainLines")?.length() ?: 0)
        }
        override fun getItem(position:Int)=position
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int, recycled:View?, parent:ViewGroup):View {
            val view=(recycled as? TextView) ?: label(23f).apply {
                localize=false
                minimumHeight=dp(56);setLineSpacing(dp(6).toFloat(),1f)
            }
            view.textSize=23f*preferences.getInt("font_size",100)/100f*preferences.getInt("lyrics_scale",100)/100f
            val doc=document ?: return view
            val line=doc.getJSONArray("lines").optJSONObject(position)
            val raw=line?.optString("text") ?: doc.optJSONArray("plainLines")?.optString(position).orEmpty()
            view.text=raw
            view.setTextColor(if(position==active || line==null) colors.text else colors.muted)
            view.setTypeface(null,if(position==active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if(preferences.getBoolean("lyrics_words",true) && position==active && words>0 && line!=null) {
                val list=line.optJSONArray("words")
                if(list!=null) {
                    var end=0
                    for(i in 0 until minOf(words,list.length()))
                        end+=list.getJSONObject(i).getString("text").length+(if(i>0) 1 else 0)
                    view.text=SpannableString(raw).apply {
                        setSpan(ForegroundColorSpan(colors.accent),0,end.coerceAtMost(length),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return view
        }
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putBoolean("localOnly",localOnly)
        state.putString("pendingImportKey",pendingImportKey)
        state.putBoolean("follow",follow);state.putParcelable("list",list.onSaveInstanceState())
        super.onSaveInstanceState(state)
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(request:Int,result:Int,data:android.content.Intent?) {
        super.onActivityResult(request,result,data)
        val uri=data?.data ?: return
        if(request!=21 || result!=RESULT_OK) return
        val current=song() ?: return
        if(key(current)!=pendingImportKey) {pendingImportKey="";load();return}
        pendingImportKey=""
        localOnly=true;serial++;val attempt=serial;val requestedKey=key(current)
        releaseDocument()
        key=requestedKey;document=null;active=-1;words=0;lastPosition=-1;loading=true
        adapter.notifyDataSetChanged();message.text="Загрузка…"
        LyricsRequests.worker.execute {
            if(!alive || attempt!=serial) return@execute
            val imported=runCatching {
                val bytes=java.io.ByteArrayOutputStream()
                contentResolver.openInputStream(uri)?.use {input ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        val n=input.read(buffer);if(n<0) break
                        require(bytes.size()+n<=256*1024) {"Текст слишком большой"}
                        bytes.write(buffer,0,n)
                    }
                } ?: error("Не удалось открыть файл")
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
                NativeCore.call("lyrics_import",JSONObject().put("key",requestedKey).put("text",text).toString()) as JSONObject
            }
            runOnUiThread {
                val doc=imported.getOrNull()
                if(!alive || attempt!=serial || requestedKey!=song()?.let {key(it)}) {
                    if(doc!=null && !isChangingConfigurations) runCatching {NativeCore.call("lyrics_release",JSONObject().put("session",doc.optLong("session")).toString())}
                    return@runOnUiThread
                }
                loading=false;document=doc;lastError=imported.exceptionOrNull()?.message
                if(visible) display(lastError)
            }
        }
    }
    override fun onStart() {
        super.onStart();visible=true
        if(song()?.let {key(it)}.orEmpty()!=key) {follow=true;load()}
        else if(document!=null || !loading) display(lastError)
        handler.post(tick)
    }
    override fun onStop() {
        visible=false;handler.removeCallbacks(tick);super.onStop()
    }
    override fun onDestroy() {
        alive=false;handler.removeCallbacksAndMessages(null)
        if(!isChangingConfigurations) releaseDocument()
        super.onDestroy()
    }
    private fun releaseDocument() {
        document?.let {doc ->
            runCatching {NativeCore.call("lyrics_release",JSONObject().put("session",doc.optLong("session")).toString())}
        }
    }
}
