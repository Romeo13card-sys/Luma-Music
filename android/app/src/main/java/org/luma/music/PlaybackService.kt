package org.luma.music

import android.app.*
import android.content.*
import android.media.*
import android.media.session.*
import android.os.*
import java.util.concurrent.Executors
import org.json.JSONObject

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val available: Boolean = true,
    val uri: String? = null,
    val waveBatch: String? = null,
    val cover: String? = null,
    val album: String? = null,
    val waveStation: String? = null,
    val waveSession: String? = null,
) {
    fun json()=JSONObject().put("id",id).put("title",title).put("artists",artist).put("available",available)
        .apply {uri?.let {put("uri",it)};cover?.let {put("cover",it)};album?.let {put("album",it)}
            waveBatch?.let {put("waveBatch",it)};waveStation?.let {put("waveStation",it)};waveSession?.let {put("waveSession",it)}}
    companion object {
        fun from(v: JSONObject) =
            Song(
                v.getString("id"),
                v.getString("title"),
                v.getString("artists"),
                v.optBoolean("available"),
                waveBatch = v.optString("waveBatch").takeIf { it.isNotBlank() },
                cover = v.optString("cover").takeIf {it.isNotBlank() && it!="null"},
                album = v.optString("album").takeIf {it.isNotBlank() && it!="null"},
                waveStation = v.optString("waveStation").takeIf {it.isNotBlank() && it!="null"},
                waveSession = v.optString("waveSession").takeIf {it.isNotBlank() && it!="null"},
                uri = v.optString("uri").takeIf {it.startsWith("content://") || it.startsWith("file://")},
            )
    }
}

class PlaybackService : Service(),CoverImages.Target {
    companion object {
        var instance: PlaybackService? = null
        var queue = listOf<Song>()
        var index = -1
        var playing = false
        var message = "Выберите музыку"
        var shuffle = false
        var repeat = false
        var restoredPosition = 0
        @Volatile var revision=0L;private set
        fun select(context:Context,songs:List<Song>,position:Int,wave:Boolean=false) {
            if(position !in songs.indices)return
            val start=maxOf(0,position-20)
            queue=songs.drop(start).take(200);index=position-start;revision++
            command(context,if(wave)"wave" else "play",index)
        }
        fun editQueue(context:Context,action:String,song:Song?=null,at:Int=-1,to:Int=-1) {
            val args=JSONObject().put("queue",QueueStore.runtime(queue,index,instance?.position() ?: restoredPosition))
                .put("action",action).put("at",at).put("to",to)
            song?.let {args.put("song",it.json())}
            val data=NativeCore.call("queue_edit",args.toString()) as JSONObject
            val items=data.getJSONArray("songs")
            queue=(0 until items.length()).map {Song.from(items.getJSONObject(it))}
            index=data.getInt("index");restoredPosition=data.getInt("position");revision++
            val service=instance
            if(queue.isEmpty()) {
                QueueStore.clearAsync(context);service?.stopSelf()
            } else if(service!=null)service.queueEdited(data.getBoolean("currentChanged"))
            else QueueStore.saveAsync(context,QueueStore.Snapshot(queue,index,restoredPosition))
        }

        fun forgetQueue(context: Context) {
            queue = emptyList()
            index = -1
            revision++
            restoredPosition = 0
            QueueStore.clearAsync(context)
        }

        fun restore(context: Context) {
            if (instance != null || queue.isNotEmpty()) return
            QueueStore(context).load()?.let {
                queue = it.songs
                index = it.index
                restoredPosition = it.position
                message = "Очередь восстановлена · нажмите играть"
            }
        }

        fun command(context: Context, action: String, position: Int = -1) {
            val intent =
                Intent(context, PlaybackService::class.java)
                    .setAction(action)
                    .putExtra("position", position)
            if (action == "play" || action == "wave" || action == "restore")
                context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val feedbackWorker=java.util.concurrent.ThreadPoolExecutor(1,1,30,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(8),java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()).apply {allowCoreThreadTimeOut(true)}
    private val main = Handler(Looper.getMainLooper())
    private var coverKey:String?=null
    private var coverImage:CoverImages.Image?=null
    private var player: MediaPlayer? = null
    private var loading:MediaPlayer?=null
    private var activeSong:Song?=null
    private var requestedIndex:Int?=null
    private val nextBuffer=NextTrackBuffer(this)
    private var plannedNext:Int?=null
    private var plannedRevision=-1L
    private var plannedShuffle=false
    private var startedGeneration=-1
    private var activeVersion=0
    private val transitions=java.util.ArrayDeque<String>()
    @Synchronized private fun note(event:String){if(transitions.size>=16)transitions.removeFirst();transitions.addLast(event+"[g="+generation+",i="+index+",p="+playing+"]")}
    @Synchronized fun debugState():String="prepared="+prepared+", requested="+requestedIndex+", status="+message+", trace="+transitions.joinToString(" > ")
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focusOwned=false
    private var resumeAfterFocus=false
    @Volatile private var generation = 0
    private var prepared = false
    private var autoResume = false
    private var sleepTimer: Runnable? = null
    private var sleepDeadline = 0L
    fun sleepMinutesRemaining(): Int =
        ((sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60000.0).let { kotlin.math.ceil(it).toInt() }


    fun setSleepTimer(minutes: Int) {
        sleepTimer?.let { main.removeCallbacks(it) }
        sleepTimer = null
        sleepDeadline = 0L
        if (minutes <= 0) return
        SettingsStore.get(this).edit().putBoolean("sleep_end",false).apply()
        sleepDeadline = SystemClock.elapsedRealtime() + minutes.toLong() * 60000
        val task = Runnable {
            pause()
            sleepDeadline = 0L
            message = "Таймер сна · пауза"
            publish()
            sleepTimer = null
        }
        sleepTimer = task
        main.postDelayed(task, minutes.toLong() * 60000)
    }

    private fun streamOptions(song: Song): String {
        val network = getSystemService(android.net.ConnectivityManager::class.java)
        val metered = network.isActiveNetworkMetered
        val preferences = SettingsStore.get(this)
        check(!preferences.getBoolean("unmetered_only", false) || !metered) {
            "Аудио в сети с лимитом отключено в настройках"
        }
        return JSONObject()
            .put("id", song.id)
            .put(
                "quality",
                preferences.getInt(
                    if (metered) "quality_mobile" else "quality_wifi",
                    if (metered) 128 else 320,
                ),
            )
            .toString()
    }

    private var waveSessionStarted = false
    private var resumePosition = 0
    private val checkpoint =
        object : Runnable {
            override fun run() {
                if (playing) saveQueue()
                main.postDelayed(this, 30000)
            }
        }

    private fun saveQueue() {
        if (index !in queue.indices) return
        if(requestedIndex!=null && queue[index].id!=activeSong?.id)return
        val start = maxOf(0, index - 20)
        val snapshot = QueueStore.Snapshot(queue.drop(start).take(200), index - start, position())
        QueueStore.saveAsync(this, snapshot)
    }

    private fun feedback(song: Song, type: String, seconds: Int = 0) {
        val batch = song.waveBatch ?: return
        val data =
            JSONObject()
                .put("type", type)
                .put("timestamp", java.time.Instant.now().toString())
                .put("batchId", batch)
                .put("trackId", song.id)
                .put("station",song.waveStation ?: "user:onyourwave")
                .apply {song.waveSession?.let {put("session",it)};song.album?.let {put("album",it)}}
                .put("totalPlayedSeconds", seconds.coerceAtLeast(0))
        val token=TokenStore(this).load().orEmpty();val args=data.toString()
        feedbackWorker.execute {if(TokenStore(this).load()==token)Native.request(token,"wave_feedback",args)}
    }

    private fun refillWave(song: Song, finished: Boolean = true, playedSeconds: Int = 0) {
        val resumePlayback=playing || autoResume
        val version = ++generation
        release()
        autoResume=resumePlayback
        message = "Продолжаем Мою волну…"
        publish()
        worker.execute {
            if(version!=generation)return@execute
            feedback(song,if(finished)"trackFinished" else "skip",playedSeconds)
            val data =
                try {
                    JSONObject(Native.request(TokenStore(this).load().orEmpty(), "wave", JSONObject().put("previous",song.id).put("station",song.waveStation ?: "user:onyourwave").apply {song.waveSession?.let {put("session",it)};song.album?.let {put("album",it)}}.toString()))
                } catch (_: Exception) {
                    null
                }
            main.post {
                if (version != generation) return@post
                if (data == null || !data.optBoolean("ok")) {
                    fail(data?.optString("error") ?: "Не удалось продолжить волну")
                    return@post
                }
                val items = data.getJSONArray("data")
                val songs =
                    (0 until items.length())
                        .map { Song.from(items.getJSONObject(it)) }
                        .filter { it.id != song.id }
                if (songs.isEmpty()) {
                    fail("Волна повторила предыдущую партию. Запустите её заново.")
                    return@post
                }
                queue = songs
                load(0,autoResume)
            }
        }
    }

    private val focus:AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener {change->
                note("focusChange="+change)
                when(change){
                    AudioManager.AUDIOFOCUS_GAIN->{
                        focusOwned=true
                        if(resumeAfterFocus && autoResume && prepared){resumeAfterFocus=false;resume()}
                        else if(!autoResume){focusOwned=false;audio.abandonAudioFocusRequest(focus)}
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->{
                        focusOwned=false;resumeAfterFocus=playing || autoResume
                        saveQueue();if(prepared)player?.pause();playing=false
                        nextBuffer.cancel();plannedNext=null
                        message="Пауза · другое приложение воспроизводит звук";publish()
                    }
                    AudioManager.AUDIOFOCUS_LOSS->{focusOwned=false;pause()}
                }
            }
            .build()
    }
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (SettingsStore.get(this@PlaybackService).getBoolean("pause_on_unplug", true)) pause()
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val preferences = SettingsStore.get(this)
        shuffle = preferences.getBoolean("shuffle", false)
        repeat = preferences.getBoolean("repeat", false)
        main.postDelayed(checkpoint, 30000)
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "playback",
                    "Воспроизведение",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        session =
            MediaSession(this, "Music").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() {
                            resume()
                        }

                        override fun onPause() {
                            pause()
                        }

                        override fun onSkipToNext() {
                            next(1)
                        }

                        override fun onSkipToPrevious() {
                            next(-1)
                        }

                        override fun onSeekTo(pos: Long) {
                            seek(pos.toInt())
                        }

                        override fun onStop() {
                            stopSelf()
                        }
                    }
                )
                isActive = true
            }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED,
            )
        else registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> {
                restoredPosition = 0
                startForeground(1, notification())
                load(intent.getIntExtra("position", 0))
            }
            "wave" -> {
                restoredPosition = 0
                startForeground(1, notification())
                waveSessionStarted = false
                load(intent.getIntExtra("position", 0))
            }
            "restore" -> {
                startForeground(1, notification())
                resumePosition = restoredPosition
                restoredPosition = 0
                load(index.coerceAtLeast(0))
            }
            "toggle" ->
                if (playing) pause()
                else if (prepared) resume()
                else {
                    startForeground(1, notification())
                    resumePosition = restoredPosition
                    restoredPosition = 0
                    load(index.coerceAtLeast(0))
                }
            "next" -> next(1)
            "previous" -> next(-1)
            "stop" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    fun position(): Int =
        if (prepared)
            try {
                player?.currentPosition ?: 0
            } catch (_: Exception) {
                0
            }
        else 0

    fun duration(): Int =
        if (prepared)
            try {
                player?.duration ?: 0
            } catch (_: Exception) {
                0
            }
        else 0

    fun seek(position: Int) {
        if (prepared) {
            player?.seekTo(position)
            saveQueue()
            publish()
        }
    }

    private fun release() {
        nextBuffer.cancel();plannedNext=null;loading?.release();loading=null;requestedIndex=null
        prepared = false
        autoResume = false
        playing = false
        player?.release()
        player = null
        activeSong=null
        focusOwned=false;resumeAfterFocus=false
        audio.abandonAudioFocusRequest(focus)
    }

    private fun fail(text: String) {
        note("error")
        generation++
        release()
        message = text
        publish()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun source(song:Song,credential:String):String = song.uri ?: OfflineDownloads.ready(this,song.id) ?: run {
        val reply=JSONObject(Native.request(credential,"stream_quality",streamOptions(song)))
        check(reply.optBoolean("ok")){reply.optString("error","Ошибка сервиса")}
        reply.getString("data")
    }
    private fun bufferKey(song:Song):String = song.id+"\n"+song.uri.orEmpty()+"\n"+if(song.uri==null)streamOptions(song) else "local"
    fun audioSessionId():Int=player?.audioSessionId ?: 0
    fun refreshAudioSettings(){
        val levels=NativeCore.call("playback_levels",JSONObject().put("balance",SettingsStore.get(this).getInt("balance",0)).toString()) as JSONObject
        player?.setVolume(levels.getDouble("left").toFloat(),levels.getDouble("right").toFloat())
    }
    fun refreshBufferPolicy(){nextBuffer.cancel();plannedNext=null;if(playing)prepareNext()}
    private fun prepareNext(){
        val prefs=SettingsStore.get(this)
        if(!playing || requestedIndex!=null || !prefs.getBoolean("preload_next",true)){nextBuffer.cancel();plannedNext=null;return}
        if(index==queue.lastIndex && (!shuffle || queue.getOrNull(index)?.waveBatch!=null)){nextBuffer.cancel();plannedNext=null;return}
        val network=getSystemService(android.net.ConnectivityManager::class.java)
        if(prefs.getBoolean("preload_wifi_only",false) && network.isActiveNetworkMetered){nextBuffer.cancel();plannedNext=null;return}
        if(plannedRevision==revision && plannedShuffle==shuffle && plannedNext!=null)return
        val selected=NativeCore.call("queue_navigation",JSONObject().put("available",org.json.JSONArray(queue.map {it.available}))
            .put("index",index).put("step",1).put("position",0).put("shuffle",shuffle)
            .put("entropy",kotlin.random.Random.nextLong(Long.MAX_VALUE)).toString()) as? JSONObject ?: return
        val target=selected.getInt("index");if(target==index)return
        val song=queue.getOrNull(target) ?: return
        val key=runCatching {bufferKey(song)}.getOrNull() ?: return
        val credential=TokenStore(this).load().orEmpty()
        plannedNext=target;plannedRevision=revision;plannedShuffle=shuffle
        nextBuffer.prepare(key,credential){source(song,credential)}
    }
    private fun load(position:Int,autoplay:Boolean=true){
        if(position !in queue.indices)return
        note("load="+position+",auto="+autoplay)
        generation++;revision++
        val version=generation;val song=queue[position]
        loading?.release();loading=null;requestedIndex=position;autoResume=autoplay
        val credential=TokenStore(this).load().orEmpty()
        val warm=runCatching {nextBuffer.take(bufferKey(song),credential)}.getOrNull()
        plannedNext=null
        if(warm!=null){note("warm");activate(warm,song,position,version);return}
        message="Загружаем трек…";publish()
        worker.execute {
            if(version!=generation)return@execute
            val response=runCatching {source(song,credential)}
            main.post {
                if(version!=generation)return@post
                if(response.isFailure){loadFailed(response.exceptionOrNull()?.message ?: "Не удалось открыть трек");return@post}
                try {
                    val media=MediaPlayer();loading=media
                    media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    media.setOnPreparedListener {if(version==generation && loading===it)activate(it,song,position,version)}
                    media.setOnErrorListener {failed,_,_->if(loading===failed)loadFailed("Не удалось подготовить аудио");true}
                    val address=response.getOrThrow()
                    if(address.startsWith("content://") || address.startsWith("file://"))media.setDataSource(this,android.net.Uri.parse(address)) else media.setDataSource(address)
                    media.prepareAsync()
                }catch(_:Exception){loadFailed("Не удалось подготовить аудио")}
            }
        }
    }
    private fun loadFailed(text:String){
        note("loadFailed")
        loading?.release();loading=null;requestedIndex=null;autoResume=playing
        message=text;publish()
        if(player==null)stopForeground(STOP_FOREGROUND_DETACH)
    }
    private fun activate(media:MediaPlayer,song:Song,position:Int,version:Int){
        if(version!=generation){media.release();return}
        note("prepared="+position)
        loading=null;player?.release();player=media;activeSong=song;activeVersion=version;prepared=true;playing=false;index=position;requestedIndex=null
        refreshAudioSettings()
        media.setOnPreparedListener(null)
        media.setOnErrorListener {failed,_,_->if(player===failed)fail("Ошибка аудио. Попробуйте другой трек");true}
        media.setOnCompletionListener {finished->if(player===finished){
            if(requestedIndex!=null){playing=false;publish()}
            else if(SettingsStore.get(this).getBoolean("sleep_end",false)){
                SettingsStore.get(this).edit().putBoolean("sleep_end",false).apply()
                if(song.waveBatch!=null)feedback(song,"trackFinished",finished.duration/1000)
                pause();message="Таймер сна · пауза";publish()
            }
            else if(repeat){finished.seekTo(0);resume()}
            else if(song.waveBatch!=null){
                if(index+1<queue.size){feedback(song,"trackFinished",finished.duration/1000);load(index+1)}
                else refillWave(song,playedSeconds=finished.duration/1000)
            }else if(index+1<queue.size || shuffle)next(1)
            else {pause();message="Очередь завершена";publish()}
        }}
        if(resumePosition>0){media.seekTo(resumePosition.coerceAtMost(media.duration));resumePosition=0}
        if(autoResume)resume() else {message="Пауза";publish()}
        saveQueue()
        if(song.cover==null && song.uri==null && song.id.all {it.isDigit()})enrichArtwork(song,version)
    }
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level==TRIM_MEMORY_RUNNING_LOW || level==TRIM_MEMORY_RUNNING_CRITICAL || level>=TRIM_MEMORY_BACKGROUND){nextBuffer.cancel();plannedNext=null}}

    private fun resume() {
        if (!prepared) return
        // Foreground status is required before audio focus on recent Android versions.
        startForeground(1, notification())
        autoResume=true
        val focusResult=if(focusOwned)AudioManager.AUDIOFOCUS_REQUEST_GRANTED else audio.requestAudioFocus(focus)
        note("focusResult="+focusResult)
        if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusOwned=true;resumeAfterFocus=false
            player?.start()
            playing = true
            autoResume=true
            if(startedGeneration!=activeVersion){
                startedGeneration=activeVersion
                queue.getOrNull(index)?.let {song->if(song.waveBatch!=null){
                    if(!waveSessionStarted){feedback(song,"radioStarted");waveSessionStarted=true}
                    feedback(song,"trackStarted")
                }}
            }
            prepareNext()
            message = "Сейчас играет"
            publish()
        } else {
            resumeAfterFocus=focusResult==AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            message=if(resumeAfterFocus)"Ожидаем освобождения аудиовывода…" else "Аудиовывод занят"
            publish()
        }
    }

    private fun pause() {
        note("pause")
        focusOwned=false;resumeAfterFocus=false
        autoResume=false
        nextBuffer.cancel();plannedNext=null
        saveQueue()
        if (prepared) player?.pause()
        playing = false
        message = "Пауза"
        audio.abandonAudioFocusRequest(focus)
        publish()
    }

    private fun next(step:Int,blockedId:String?=null) {
        val current=queue.getOrNull(index)
        if(step==1 && current?.waveBatch!=null){
            if(index==queue.lastIndex){refillWave(current,false,position()/1000);return}
            feedback(current,"skip",position()/1000)
        }
        val usePlanned=step==1 && blockedId==null && plannedRevision==revision && plannedShuffle==shuffle && requestedIndex==null
        val selected=if(usePlanned && plannedNext!=null)JSONObject().put("index",plannedNext).put("restart",false) else NativeCore.call("queue_navigation",JSONObject()
            .put("available",org.json.JSONArray(queue.map {it.available && it.id!=blockedId})).put("index",requestedIndex ?: index)
            .put("step",step).put("position",if(requestedIndex==null)position() else 0).put("shuffle",shuffle)
            .put("entropy",kotlin.random.Random.nextLong(Long.MAX_VALUE)).toString()) as? JSONObject ?: run {if(blockedId!=null)pause();return}
        if(selected.getBoolean("restart"))seek(0) else load(selected.getInt("index"),playing || autoResume)
    }
    fun reactionFeedback(id:String,kind:String,set:Boolean){
        val song=queue.getOrNull(index) ?: return
        if(song.id!=id)return
        feedback(song,if(kind=="likes"){if(set)"like" else "unlike"}else{if(set)"dislike" else "undislike"})
        if(kind=="dislikes" && set && playing)next(1,id)
    }
    private fun queueEdited(currentChanged:Boolean) {
        nextBuffer.cancel();plannedNext=null
        val wanted=playing || autoResume
        if(requestedIndex!=null){generation++;loading?.release();loading=null;requestedIndex=null;autoResume=playing}
        if(currentChanged) {
            val resume=wanted
            generation++;release();resumePosition=0;restoredPosition=0
            if(resume){load(index);return}
            message="Очередь изменена"
        }
        saveQueue();publish();if(playing)prepareNext()
    }

    private fun enrichArtwork(song:Song,version:Int) {
        val token=TokenStore(this).load().orEmpty()
        val network=getSystemService(android.net.ConnectivityManager::class.java)
        if(token.isBlank() || network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true)return
        worker.execute {
            if(version!=generation)return@execute
            val cover=runCatching {
                val reply=JSONObject(Native.request(token,"track",song.id))
                if(reply.optBoolean("ok"))Song.from(reply.getJSONObject("data")).cover else null
            }.getOrNull() ?: return@execute
            if(TokenStore(this).load()==token)runCatching {
                val args=OfflineDownloads.arguments(this).put("id",song.id).put("cover",cover)
                if(NativeCore.call("offline_cover",args.toString())==true)OfflineDownloads.artworkChanged()
            }
            main.post {
                if(version!=generation || TokenStore(this).load()!=token)return@post
                queue=queue.map {if(it.id==song.id)it.copy(cover=cover) else it};revision++
                saveQueue();publish()
            }
        }
    }
    private fun requestArtwork(song:Song?) {
        val key=song?.cover?.let {CoverImages.key(it,100)}
        if(key==coverKey)return
        CoverImages.cancel(this);coverKey=key;coverImage=null
        song?.cover?.let {CoverImages.request(this,it,100,this,true)}
    }
    override fun artworkLoaded(key:String,image:CoverImages.Image?) {
        if(instance!==this || key!=coverKey)return
        coverImage=image;publish()
    }
    private fun currentArtwork():CoverImages.Image? =
        coverImage.takeIf {coverKey==queue.getOrNull(index)?.cover?.let {CoverImages.key(it,100)}}

    private fun notification(): Notification {
        val song = queue.getOrNull(index)
        fun action(name: String) =
            PendingIntent.getService(
                this,
                name.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(this, "playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.artist ?: L10n.text(this,message))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .apply {currentArtwork()?.let {setLargeIcon(it.bitmap);setColor(it.dark.accent)}}
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        L10n.text(this,"Назад"),
                        action("previous"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        if (playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play,
                        L10n.text(this,if (playing) "Пауза" else "Играть"),
                        action("toggle"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        L10n.text(this,"Далее"),
                        action("next"),
                    )
                    .build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun publish() {
        val song = queue.getOrNull(index)
        requestArtwork(song)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song?.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song?.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
                .apply {currentArtwork()?.let {putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,it.bitmap);putBitmap(MediaMetadata.METADATA_KEY_ART,it.bitmap)}}
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position().toLong(),
                    if (playing) 1f else 0f,
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    override fun onDestroy() {
        CoverImages.cancel(this);coverImage=null;coverKey=null
        saveQueue()
        generation++
        main.removeCallbacksAndMessages(null)
        release()
        session.release()
        unregisterReceiver(noisy)
        worker.shutdownNow()
        feedbackWorker.shutdownNow()
        nextBuffer.close()
        instance = null
        message = "Воспроизведение остановлено"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
