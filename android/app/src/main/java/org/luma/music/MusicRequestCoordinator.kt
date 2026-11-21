package org.luma.music

import android.app.Activity
import android.widget.TextView
import org.json.JSONObject

/**
 * Lifecycle- and generation-aware bridge for NativeCore requests.
 * The host supplies current account state and owns the result-specific UI actions.
 */
internal class MusicRequestCoordinator(
    private val activity: Activity,
    private val requests: LatestRequests,
    private val generation: () -> Int,
    private val credential: () -> String,
    private val destroyed: () -> Boolean,
    private val status: () -> TextView,
) {
    fun submit(
        operation: String,
        argument: String = "",
        finished: (() -> Unit)? = null,
        done: (Any) -> Unit,
    ) {
        val version = generation()
        val token = credential()
        status().text = "Загрузка…"
        requests.submit { ticket ->
            if (destroyed() || version != generation() || !ticket.valid) return@submit
            val result = try {
                JSONObject(Native.request(token, operation, argument))
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "Не удалось обработать ответ")
            }
            activity.runOnUiThread {
                if (destroyed() || version != generation() || !ticket.valid) return@runOnUiThread
                finished?.invoke()
                if (result.optBoolean("ok")) {
                    status().text = ""
                    done(result.get("data"))
                } else {
                    status().text = result.optString("error", "Ошибка сервиса")
                }
            }
        }
    }
}
