package org.luma.music

import android.app.*
import android.content.*
import android.os.*
import org.json.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object OfflineDownloads {
    @Volatile var artworkRevision = 0L
        private set

    private const val PREFS = "offline_queue"
    private const val PENDING = "pending"
    private const val ACTIVE = "active"
    private const val MAX_PENDING = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(PENDING, "[]")) }.getOrDefault(JSONArray())

    fun artworkChanged() {
        artworkRevision++
    }

    fun arguments(context: Context): JSONObject = JSONObject()
        .put("root", File(context.filesDir, "offline").absolutePath)
        .put("owner", TokenStore(context, "offline_owner").load().orEmpty())

    fun list(context: Context): JSONObject {
        if (TokenStore(context, "offline_owner").load().isNullOrBlank()) {
            return JSONObject().put("items", JSONArray()).put("bytes", 0)
        }
        return NativeCore.call("offline_list", arguments(context).toString()) as JSONObject
    }

    fun ready(context: Context, id: String): String? {
        if (TokenStore(context, "offline_owner").load().isNullOrBlank()) return null
        return NativeCore.call("offline_ready", arguments(context).put("id", id).toString()) as? String
    }

    @Synchronized
    fun enqueue(context: Context, song: Song) {
        check(song.uri == null && song.id.isNotEmpty() && song.id.all { it.isDigit() }) {
            "Для этого трека скачивание недоступно"
        }
        check(!TokenStore(context).load().isNullOrBlank()) { "Войдите в Яндекс Музыку" }
        val store = prefs(context)
        val active = runCatching { store.getString(ACTIVE, null)?.let(::JSONObject) }.getOrNull()
        check(active?.optString("id") != song.id) { "Этот трек уже загружается" }
        val items = pending(context)
        check((0 until items.length()).none { items.optJSONObject(it)?.optString("id") == song.id }) {
            "Этот трек уже есть в очереди"
        }
        check(items.length() < MAX_PENDING) { "Очередь ограничена 100 треками" }
        items.put(
            JSONObject()
                .put("id", song.id)
                .put("title", song.title)
                .put("artists", song.artist)
                .put("cover", song.cover ?: ""),
        )
        check(store.edit().putString(PENDING, items.toString()).commit()) {
            "Не удалось сохранить очередь загрузок"
        }
        resume(context)
    }

    @Synchronized
    fun takeNext(context: Context): JSONObject? {
        val store = prefs(context)
        runCatching { store.getString(ACTIVE, null)?.let(::JSONObject) }.getOrNull()?.let {
            return it
        }
        val items = pending(context)
        if (items.length() == 0) return null
        val next = items.optJSONObject(0) ?: return null
        val remaining = JSONArray()
        for (index in 1 until items.length()) {
            items.optJSONObject(index)?.let { remaining.put(it) }
        }
        check(
            store.edit()
                .putString(PENDING, remaining.toString())
                .putString(ACTIVE, next.toString())
                .commit(),
        ) { "Не удалось сохранить текущую загрузку" }
        return next
    }

    @Synchronized
    fun complete(context: Context, id: String) {
        val store = prefs(context)
        val active = runCatching { store.getString(ACTIVE, null)?.let(::JSONObject) }.getOrNull()
        if (active?.optString("id") == id) {
            store.edit().remove(ACTIVE).commit()
        }
    }

    @Synchronized
    fun cancelActive(context: Context) {
        prefs(context).edit().remove(ACTIVE).commit()
        DownloadService.cancel()
    }

    @Synchronized
    fun clearPending(context: Context) {
        prefs(context).edit().putString(PENDING, "[]").commit()
    }

    fun hasWork(context: Context): Boolean {
        val store = prefs(context)
        return store.getString(ACTIVE, null) != null || pending(context).length() > 0
    }

    fun isActive(context: Context, id: String): Boolean {
        val active = runCatching {
            prefs(context).getString(ACTIVE, null)?.let(::JSONObject)
        }.getOrNull()
        return active?.optString("id") == id
    }

    fun queueSummary(context: Context): String {
        val store = prefs(context)
        val active = runCatching { store.getString(ACTIVE, null)?.let(::JSONObject) }.getOrNull()
        val waiting = pending(context)
        val count = waiting.length() + if (active != null) 1 else 0
        if (count == 0) return "Очередь загрузок пуста"
        val activeTitle = active?.optString("title").orEmpty()
        return buildString {
            append("В очереди: ").append(count)
            if (activeTitle.isNotBlank()) append(" · Сейчас: ").append(activeTitle)
            if (waiting.length() > 0) {
                val next = waiting.optJSONObject(0)?.optString("title").orEmpty()
                if (next.isNotBlank()) append(" · Далее: ").append(next)
            }
        }
    }

    fun resume(context: Context) {
        if (DownloadService.busy || !hasWork(context)) return
        runCatching {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }

    fun forgetAccount(context: Context) {
        val args = arguments(context)
        cancelActive(context)
        clearPending(context)
        TokenStore(context, "offline_owner").clear()
        Thread {
            runCatching { NativeCore.call("offline_clear", args.toString()) }
        }.start()
    }
}

/** Persistent account-scoped queue; the active item remains on disk across process death. */
class DownloadService : Service() {
    companion object {
        @Volatile var busy = false
            private set
        @Volatile var message = "Загрузок пока нет"
            private set
        @Volatile var percent = -1
            private set
        @Volatile var revision = 0L
            private set
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            cancelled.set(true)
            if (busy) message = "Отмена загрузки…"
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Офлайн-загрузки", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OfflineActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Luma · загрузка")
            .setContentText(message)
            .setOngoing(busy)
            .setContentIntent(open)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (busy) return START_NOT_STICKY
        val item = OfflineDownloads.takeNext(this)
        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        busy = true
        cancelled.set(false)
        percent = -1
        val id = item.optString("id")
        val title = item.optString("title", "Без названия")
        val artists = item.optString("artists")
        val cover = item.optString("cover")
        message = title
        startForeground(2, notification())

        val token = TokenStore(this).load().orEmpty()
        val prefs = SettingsStore.get(this)
        val wifi = prefs.getBoolean("download_wifi_only", true)
        val quota = prefs.getInt("download_limit_mb", 1024).toLong() * 1024 * 1024
        val quality = prefs.getInt("download_quality", 192)

        worker.execute {
            try {
                val network = getSystemService(android.net.ConnectivityManager::class.java)
                fun allowed() = !wifi || (
                    network.getNetworkCapabilities(network.activeNetwork)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        !network.isActiveNetworkMetered
                    )
                check(allowed()) { "Для загрузки требуется Wi-Fi без тарификации" }
                val account = JSONObject(Native.request(token, "account", ""))
                check(account.optBoolean("ok")) {
                    account.optString("error", "Не удалось проверить аккаунт")
                }
                val owner = account.getJSONObject("data").get("uid").toString()
                check(owner.isNotBlank() && owner.all { it.isDigit() }) {
                    "Нет идентификатора аккаунта"
                }
                check(!cancelled.get() && TokenStore(this).load() == token) {
                    "Загрузка отменена"
                }
                TokenStore(this, "offline_owner").save(owner)
                val args = OfflineDownloads.arguments(this)
                    .put("id", id)
                    .put("title", title)
                    .put("artists", artists)
                    .put("quality", quality)
                    .put("quota", quota)
                    .put("cover", cover)
                var last = -2
                val result = JSONObject(Native.offlineDownload(token, args.toString()) { value ->
                    val proceed = !cancelled.get() && allowed()
                    if (value != last) {
                        last = value
                        percent = value
                        getSystemService(NotificationManager::class.java).notify(2, notification())
                    }
                    proceed
                })
                check(result.optBoolean("ok")) {
                    result.optString("error", "Загрузка не завершена")
                }
                OfflineDownloads.complete(this, id)
                message = "Скачано · $title"
                percent = 100
            } catch (e: Exception) {
                message = e.message ?: "Не удалось скачать трек"
            } finally {
                main.post {
                    busy = false
                    revision++
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (OfflineDownloads.hasWork(this) && !OfflineDownloads.isActive(this, id)) {
                        OfflineDownloads.resume(this)
                    } else {
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }
}
