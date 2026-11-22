package org.luma.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Speculatively resolves stream URLs without doing credential, preference,
 * connectivity or network work on the caller (usually the UI) thread.
 * The Rust core owns the account/quality-scoped short-lived URL cache.
 */
internal fun isAsciiNumericTrackId(id: String): Boolean =
    id.isNotEmpty() && id.all { digit -> digit in '0'..'9' }

internal object StreamUrlPreloader {
    // Prevent duplicate concurrent warmups; URL caching and request sharing live in Rust.
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private fun quality(context: Context, metered: Boolean): Int {
        val prefs = SettingsStore.get(context)
        return prefs.getInt(if (metered) "quality_mobile" else "quality_wifi", if (metered) 128 else 320)
    }

    fun preload(context: Context, songs: List<Song>) {
        val app = context.applicationContext
        songs.asSequence()
            .filter { it.available && it.uri == null && isAsciiNumericTrackId(it.id) }
            .distinctBy { it.id }
            .take(2)
            .forEach { song ->
                try {
                    executor.execute {
                        var pendingKey: String? = null
                        try {
                            val token = TokenStore(app).load().orEmpty()
                            if (token.isNotBlank()) {
                                val network = app.getSystemService(ConnectivityManager::class.java)
                                val active = network.activeNetwork
                                val capabilities = active?.let { network.getNetworkCapabilities(it) }
                                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                val metered = network.isActiveNetworkMetered
                                val prefs = SettingsStore.get(app)
                                val bitrate = quality(app, metered)
                                val accountScope = MessageDigest.getInstance("SHA-256")
                                    .digest(token.toByteArray(Charsets.UTF_8))
                                    .joinToString("") { byte -> "%02x".format(byte) }
                                val key = "${song.id}:$bitrate:$accountScope"
                                if (!pending.add(key)) return@execute
                                pendingKey = key
                                if (hasInternet && !(prefs.getBoolean("unmetered_only", false) && metered) &&
                                    OfflineDownloads.ready(app, song.id) == null
                                ) {
                                    val args = JSONObject()
                                        .put("id", song.id)
                                        .put("quality", bitrate)
                                    val reply = JSONObject(Native.request(token, "stream_quality", args.toString()))
                                    check(reply.optBoolean("ok")) {
                                        reply.optString("error", "Не удалось подготовить поток")
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Speculative warmup failures never block normal playback.
                        } finally {
                            pendingKey?.let(pending::remove)
                        }
                    }
                } catch (_: RejectedExecutionException) { }
            }
    }
}
