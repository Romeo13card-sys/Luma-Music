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
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var generation = 0
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

    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }
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
        prepared = false
        autoResume = false
        playing = false
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun fail(text: String) {
        release()
        message = text
        publish()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun load(position: Int, autoplay:Boolean=true) {
        if (position !in queue.indices) return
        generation++
        revision++
        val version = generation
        release()
        autoResume=autoplay
        index = position
        message = "Подготовка…"
        publish()
        val song = queue[position]
        worker.execute {
            val response =
                try {
                    if (song.waveBatch != null && !waveSessionStarted) {
                        feedback(song, "radioStarted")
                        waveSessionStarted = true
                    }
                    song.uri
                        ?: OfflineDownloads.ready(this,song.id)
                        ?: JSONObject(
                                Native.request(
                                    TokenStore(this).load().orEmpty(),
                                    "stream_quality",
                                    streamOptions(song),
                                )
                            )
                            .let {
                                if (!it.optBoolean("ok"))
                                    throw IllegalStateException(
                                        it.optString("error", "Ошибка сервиса")
                                    )
                                it.getString("data")
                            }
                } catch (e: Exception) {
                    main.post {
                        if (version == generation) fail(e.message ?: "Не удалось открыть трек")
                    }
                    return@execute
                }
            main.post {
                if (version != generation) return@post
                try {
                    val media = MediaPlayer()
                    player = media
                    media.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    media.setOnPreparedListener {
                        if (player === it) {
                            prepared = true
                            if (resumePosition > 0) {
                                it.seekTo(resumePosition.coerceAtMost(it.duration))
                                resumePosition = 0
                            }
                            if (song.waveBatch != null)
                                worker.execute { feedback(song, "trackStarted") }
                            if(autoResume)resume() else {message="Пауза";publish()}
                            saveQueue()
                            if(song.cover==null && song.uri==null && song.id.all {it.isDigit()})enrichArtwork(song,version)
                        }
                    }
                    media.setOnErrorListener { failed, _, _ ->
                        if (player === failed) fail("Ошибка аудио. Попробуйте другой трек")
                        true
                    }
                    media.setOnCompletionListener {
                        if (player === it) {
                            if (repeat) {
                                it.seekTo(0)
                                resume()
                            } else if (song.waveBatch != null) {
                                if (index + 1 < queue.size) {
                                    val seconds = it.duration / 1000
                                    worker.execute { feedback(song, "trackFinished", seconds) }
                                    load(index + 1)
                                } else refillWave(song, playedSeconds = it.duration / 1000)
                            } else if (index + 1 < queue.size || shuffle) next(1)
                            else {
                                pause()
                                message = "Очередь завершена"
                                publish()
                            }
                        }
                    }
                    if (song.uri != null) media.setDataSource(this, android.net.Uri.parse(response))
                    else media.setDataSource(response)
                    media.prepareAsync()
                } catch (_: Exception) {
                    fail("Не удалось подготовить аудио")
                }
            }
        }
    }

    private fun resume() {
        if (!prepared) return
        // Foreground status is required before audio focus on recent Android versions.
        startForeground(1, notification())
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            player?.start()
            playing = true
            message = "Сейчас играет"
            publish()
        } else {
            message = "Аудиовывод занят"
            publish()
        }
    }

    private fun pause() {
        autoResume=false
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
        val selected=NativeCore.call("queue_navigation",JSONObject()
            .put("available",org.json.JSONArray(queue.map {it.available && it.id!=blockedId})).put("index",index)
            .put("step",step).put("position",position()).put("shuffle",shuffle)
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
        if(currentChanged) {
            val resume=playing || autoResume
            generation++;release();resumePosition=0;restoredPosition=0
            if(resume){load(index);return}
            message="Очередь изменена"
        }
        saveQueue();publish()
    }

    private fun enrichArtwork(song:Song,version:Int) {
        val token=TokenStore(this).load().orEmpty()
        val network=getSystemService(android.net.ConnectivityManager::class.java)
        if(token.isBlank() || network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true)return
        worker.execute {
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
        instance = null
        message = "Воспроизведение остановлено"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
