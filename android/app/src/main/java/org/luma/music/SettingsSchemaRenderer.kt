package org.luma.music

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * Renders settings fields described by the Rust settings schema.
 * Persistence, setting-row widgets, and refresh/navigation side effects are injected by the host.
 */
internal class SettingsSchemaRenderer(
    private val context: Context,
    private val schema: JSONObject,
    private val preferences: SettingsStore,
    private val group: (String) -> LinearLayout,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean) -> Unit,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val translate: (String) -> String,
    private val onChanged: (String) -> Unit,
) {
    fun render(id: String) {
        val groups = schema.getJSONObject("groups").optJSONArray(id) ?: return
        for (groupIndex in 0 until groups.length()) {
            val model = groups.getJSONObject(groupIndex)
            val target = group(model.getString("title"))
            val fields = model.getJSONArray("fields")
            for (fieldIndex in 0 until fields.length()) {
                val field = fields.getJSONObject(fieldIndex)
                val key = field.getString("key")
                val title = field.getString("title")
                val summary = field.getString("summary")
                if (field.getString("type") == "bool") {
                    settingSwitch(target, title, summary, key, field.getBoolean("default"))
                } else {
                    val values = field.getJSONArray("values")
                    val choices = field.getJSONArray("labels")
                    val selected = preferences.getInt(key, field.getInt("default"))
                    val index = (0 until values.length()).firstOrNull {
                        values.getInt(it) == selected
                    } ?: 0
                    settingRow(target, title, translate(choices.getString(index)) + " · " + translate(summary)) {
                        LumaDialog(context)
                            .setTitle(title)
                            .setSingleChoiceItems(
                                (0 until choices.length()).map {
                                    translate(choices.getString(it))
                                }.toTypedArray(),
                                index,
                            ) { dialog, selectedIndex ->
                                preferences.edit().putInt(key, values.getInt(selectedIndex)).apply()
                                dialog.dismiss()
                                onChanged(id)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
        }
    }
}
