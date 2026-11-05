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
) {
    companion object {
        fun from(v: JSONObject) =
            Song(
                v.getString("id"),
                v.getString("title"),
                v.getString("artists"),
                v.optBoolean("available"),
                waveBatch = v.optString("waveBatch").takeIf { it.isNotBlank() },
            )
    }
}

class PlaybackService : Service() {
    companion object {
        var instance: PlaybackService? = null
        var queue = listOf<Song>()
        var index = -1
        var playing = false
        var message = "Выберите музыку"
        var shuffle = false
        var repeat = false
        var restoredPosition = 0

        fun forgetQueue(context: Context) {
            queue = emptyList()
            index = -1
            restoredPosition = 0
            val app = context.applicationContext
            val service = instance
            if (service != null) service.worker.execute { QueueStore(app).clear() }
            else QueueStore(app).clear()
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
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var generation = 0
    private var prepared = false
    private var sleepTimer: Runnable? = null

    fun setSleepTimer(minutes: Int) {
        sleepTimer?.let { main.removeCallbacks(it) }
        sleepTimer = null
        if (minutes <= 0) return
        val task = Runnable {
            pause()
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
        val preferences = getSharedPreferences("player_settings", MODE_PRIVATE)
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
        worker.execute { QueueStore(this).save(snapshot) }
    }

    private fun feedback(song: Song, type: String, seconds: Int = 0) {
        val batch = song.waveBatch ?: return
        val data =
            JSONObject()
                .put("type", type)
                .put("timestamp", java.time.Instant.now().toString())
                .put("batchId", batch)
                .put("trackId", song.id)
                .put("totalPlayedSeconds", seconds.coerceAtLeast(0))
        Native.request(TokenStore(this).load().orEmpty(), "wave_feedback", data.toString())
    }

    private fun refillWave(song: Song, finished: Boolean = true, playedSeconds: Int = 0) {
        val version = ++generation
        release()
        message = "Продолжаем Мою волну…"
        publish()
        worker.execute {
            if (finished) feedback(song, "trackFinished", playedSeconds)
            val data =
                try {
                    JSONObject(Native.request(TokenStore(this).load().orEmpty(), "wave", song.id))
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
                load(0)
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
                pause()
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val preferences = getSharedPreferences("player_settings", MODE_PRIVATE)
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

    private fun load(position: Int) {
        if (position !in queue.indices) return
        generation++
        val version = generation
        release()
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
                            resume()
                            saveQueue()
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
        saveQueue()
        if (prepared) player?.pause()
        playing = false
        message = "Пауза"
        audio.abandonAudioFocusRequest(focus)
        publish()
    }

    private fun next(step: Int) {
        if (queue.isEmpty()) return
        val current = queue.getOrNull(index)
        if (current?.waveBatch != null && step > 0) {
            val seconds = position() / 1000
            worker.execute { feedback(current, "skip", seconds) }
            if (index + 1 >= queue.size) refillWave(current, false) else load(index + 1)
            return
        }
        val available = queue.indices.filter { queue[it].available }
        if (available.isEmpty()) return
        val destination =
            if (shuffle && step > 0)
                (available.filter { it != index }.ifEmpty { available }).random()
            else
                (1..queue.size)
                    .map { Math.floorMod(index + step * it, queue.size) }
                    .first { queue[it].available }
        load(destination)
    }

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
            .setContentText(song?.artist ?: message)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        "Назад",
                        action("previous"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        if (playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play,
                        if (playing) "Пауза" else "Играть",
                        action("toggle"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        "Далее",
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
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song?.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song?.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
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
        saveQueue()
        generation++
        main.removeCallbacksAndMessages(null)
        release()
        session.release()
        unregisterReceiver(noisy)
        worker.shutdownNow()
        instance = null
        message = "Воспроизведение остановлено"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
