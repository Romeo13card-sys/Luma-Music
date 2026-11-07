package org.luma.music

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

/** Shared Material 3 theme; uses Android Views and no continuously running effects. */
open class MaterialActivity:AppCompatActivity() {
    override fun attachBaseContext(base:Context) {super.attachBaseContext(L10n.wrap(base))}
    override fun onCreate(state:Bundle?) {
        val prefs=SettingsStore.get(this)
        val night=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val palette=AppThemes.resolve(prefs.getString("theme_mode","system").orEmpty(),night)
        setTheme(if(palette.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
        super.onCreate(state)
        DynamicColors.applyToActivityIfAvailable(this)
    }
}
