package dev.romeo.yamusic

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
) {
    companion object {
        fun from(v: JSONObject) =
            Song(
                v.getString("id"),
                v.getString("title"),
                v.getString("artists"),
                v.optBoolean("available"),
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

        fun command(context: Context, action: String, position: Int = -1) {
            val intent =
                Intent(context, PlaybackService::class.java)
                    .setAction(action)
                    .putExtra("position", position)
            if (action == "play") context.startForegroundService(intent)
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
                startForeground(1, notification())
                load(intent.getIntExtra("position", 0))
            }
            "toggle" -> if (playing) pause() else resume()
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
                    song.uri
                        ?: JSONObject(
                                Native.request(TokenStore(this).load().orEmpty(), "stream", song.id)
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
                            resume()
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
        if (prepared) player?.pause()
        playing = false
        message = "Пауза"
        audio.abandonAudioFocusRequest(focus)
        publish()
    }

    private fun next(step: Int) {
        if (queue.isEmpty()) return
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
        generation++
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
