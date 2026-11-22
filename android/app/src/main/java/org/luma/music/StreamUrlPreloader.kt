package org.luma.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Resolves short-lived stream URLs while a track list is on screen.
 *
 * The Rust core owns the actual account-scoped, 45-second URL cache. This
 * coordinator only deduplicates in-flight warmups so a tap can reuse the
 * result instead of issuing a second download-info request.
 */
internal object StreamUrlPreloader {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    private val executor = ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private fun quality(context: Context): Int {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val mobile = network.isActiveNetworkMetered
        val prefs = SettingsStore.get(context)
        return prefs.getInt(if (mobile) "quality_mobile" else "quality_wifi", if (mobile) 128 else 320)
    }

    private fun key(token: String, song: Song, quality: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$digest:${song.id}:$quality"
    }

    fun preload(context: Context, songs: List<Song>) {
        val app = context.applicationContext
        val token = TokenStore(app).load().orEmpty()
        if (token.isBlank()) return

        val network = app.getSystemService(ConnectivityManager::class.java)
        val active = network.activeNetwork ?: return
        val capabilities = network.getNetworkCapabilities(active) ?: return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val mobile = network.isActiveNetworkMetered
        val prefs = SettingsStore.get(app)
        if (prefs.getBoolean("unmetered_only", false) && mobile) return
        val selectedQuality = quality(app)

        songs.asSequence()
            .filter { it.available && it.uri == null && it.id.isNotBlank() && it.id.all { digit -> digit in '0'..'9' } }
            .distinctBy { it.id }
            .take(2)
            .forEach { song ->
                val cacheKey = key(token, song, selectedQuality)
                val future = CompletableFuture<Unit>()
                val existing = pending[cacheKey]
                if (existing != null && !existing.isDone) return@forEach
                if (existing != null) pending.remove(cacheKey, existing)
                if (pending.putIfAbsent(cacheKey, future) != null) return@forEach
                try {
                    executor.execute {
                        try {
                            // Offline tracks do not need a signed streaming URL.
                            if (OfflineDownloads.ready(app, song.id) == null) {
                                val args = JSONObject()
                                    .put("id", song.id)
                                    .put("quality", selectedQuality)
                                val reply = JSONObject(Native.request(token, "stream_quality", args.toString()))
                                check(reply.optBoolean("ok")) {
                                    reply.optString("error", "Не удалось подготовить поток")
                                }
                            }
                            future.complete(Unit)
                        } catch (error: Exception) {
                            future.completeExceptionally(error)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    pending.remove(cacheKey, future)
                    future.completeExceptionally(IllegalStateException("Stream warmup queue is full"))
                }
            }

        // Keep only a small number of completed/in-flight account-scoped entries.
        if (pending.size > 8) {
            pending.entries.firstOrNull { it.value.isDone }?.let { pending.remove(it.key, it.value) }
        }
    }

    fun awaitIfPending(context: Context, song: Song, token: String) {
        if (token.isBlank() || song.uri != null) return
        val future = runCatching { pending[key(token, song, quality(context))] }.getOrNull() ?: return
        try {
            future.get(12, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The regular playback request below remains the source of truth
            // and retries resolution if a speculative warmup failed.
        } finally {
            pending.entries.firstOrNull { it.value === future }?.let { pending.remove(it.key, future) }
        }
    }
}
