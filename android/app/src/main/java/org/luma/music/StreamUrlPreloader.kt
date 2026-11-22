package org.luma.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
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
    // This future is only a rendezvous signal. The actual URL cache remains
    // account- and quality-scoped in Rust; no URL or credential is shared here.
    private val pending = ConcurrentHashMap<String, CompletableFuture<Unit>>()
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
                val future = CompletableFuture<Unit>()
                val existing = pending[song.id]
                if (existing != null && !existing.isDone) return@forEach
                if (existing != null && !pending.remove(song.id, existing)) return@forEach
                if (pending.putIfAbsent(song.id, future) != null) return@forEach

                try {
                    executor.execute {
                        try {
                            val token = TokenStore(app).load().orEmpty()
                            if (token.isNotBlank()) {
                                val network = app.getSystemService(ConnectivityManager::class.java)
                                val active = network.activeNetwork
                                val capabilities = active?.let { network.getNetworkCapabilities(it) }
                                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                val metered = network.isActiveNetworkMetered
                                val prefs = SettingsStore.get(app)
                                if (hasInternet && !(prefs.getBoolean("unmetered_only", false) && metered) &&
                                    OfflineDownloads.ready(app, song.id) == null
                                ) {
                                    val args = JSONObject()
                                        .put("id", song.id)
                                        .put("quality", quality(app, metered))
                                    val reply = JSONObject(Native.request(token, "stream_quality", args.toString()))
                                    check(reply.optBoolean("ok")) {
                                        reply.optString("error", "Не удалось подготовить поток")
                                    }
                                }
                            }
                            future.complete(Unit)
                        } catch (error: Exception) {
                            future.completeExceptionally(error)
                        } finally {
                            // Keep a completed future briefly available to a tap that
                            // raced the warmup; the Rust cache handles URL expiry.
                            if (future.isCompletedExceptionally) pending.remove(song.id, future)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    pending.remove(song.id, future)
                    future.completeExceptionally(IllegalStateException("Stream warmup queue is full"))
                }
            }

        if (pending.size > 8) {
            pending.entries.firstOrNull { it.value.isDone }?.let { pending.remove(it.key, it.value) }
        }
    }

    fun awaitIfPending(song: Song, token: String) {
        if (token.isBlank() || song.uri != null) return
        val future = pending[song.id] ?: return
        try {
            future.get(300, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // A speculative failure never blocks the normal playback fallback.
        } finally {
            pending.remove(song.id, future)
        }
    }
}
