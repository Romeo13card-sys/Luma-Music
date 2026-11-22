package org.luma.music

import android.widget.LinearLayout
import org.json.JSONArray

/**
 * Library landing screen. Playlist loading and navigation are delegated to the Activity host.
 */
internal class LibraryScreen(
    private val target: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val gap: (LinearLayout, Int) -> Unit,
    private val collectionCards: (LinearLayout) -> Unit,
    private val hasAccount: Boolean,
    private val loadPlaylists: ((JSONArray) -> Unit) -> Unit,
    private val openPlaylist: (String, String) -> Unit,
    private val openFile: () -> Unit,
    private val textColor: Int,
    private val mutedColor: Int,
) {
    fun render() {
        target.addView(text("YOUR SPACE  /  LIBRARY", 10f, AppThemes.active.accent, true, true))
        target.addView(text("Твоя коллекция", 31f, textColor, true, false))
        target.addView(text("Всё, что хочется сохранить и переслушивать.", 13f, mutedColor, false, false))
        gap(target, 8)
        collectionCards(target)
        gap(target, 12)
        target.addView(action("Музыка с устройства", false, openFile))
        gap(target, 12)
        target.addView(text("Твои плейлисты", 22f, textColor, true, false))

        if (!hasAccount) {
            target.addView(text("Подключите аккаунт, чтобы увидеть свои плейлисты.", 15f, mutedColor, false, false))
            return
        }

        loadPlaylists { items ->
            if (items.length() == 0) {
                target.addView(text("Пока нет плейлистов", 15f, mutedColor, false, false))
            }
            for (index in 0 until minOf(items.length(), 200)) {
                val item = items.getJSONObject(index)
                val title = item.optString("title", "Плейлист")
                val owner = item.optJSONObject("owner")?.optString("uid").orEmpty()
                val kind = item.optString("kind")
                target.addView(
                    action(title + "  ›", false) {
                        openPlaylist(title, owner + ":" + kind)
                    },
                )
                gap(target, 6)
            }
        }
    }
}
