package org.luma.music

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray

/** Native adaptation of YAYMA's mobile wave preset panel; no animated shader. */
object WaveSheet {
    fun show(activity:Activity,onSelected:()->Unit):android.app.Dialog{
        val prefs=SettingsStore.get(activity)
        var selected=prefs.getString("wave_station","user:onyourwave").orEmpty()
        val stations=NativeCore.call("wave_stations","{}") as JSONArray
        val dialog=BottomSheetDialog(activity)
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        root.addView(MusicUi.text(activity,"Настроить Мою волну",23f,true))
        root.addView(MusicUi.text(activity,"Выберите одну подборку",14f))
        val chips=mutableListOf<Pair<Chip,String>>()
        var section=""
        var group=ChipGroup(activity)
        for(i in 0 until stations.length()){
            val item=stations.getJSONObject(i);val category=item.getString("group");val id=item.getString("id")
            if(category!=section){
                section=category;root.addView(MusicUi.text(activity,category,15f,true))
                group=ChipGroup(activity).apply {chipSpacingHorizontal=MusicUi.dp(activity,6);chipSpacingVertical=MusicUi.dp(activity,4)}
                root.addView(group)
            }
            val chip=Chip(activity).apply {
                text=L10n.text(activity,item.getString("title"));isCheckable=true;isChecked=id==selected
                setOnClickListener {selected=id;chips.forEach {(view,key)->view.isChecked=key==selected}}
            }
            chips.add(chip to id);group.addView(chip)
        }
        root.addView(MusicUi.button(activity,"Слушать подборку"){
            prefs.edit().putString("wave_station",selected).apply();dialog.dismiss();onSelected()
        })
        root.addView(MusicUi.button(activity,"Закрыть"){dialog.dismiss()})
        dialog.setContentView(ScrollView(activity).apply {addView(root)})
        dialog.show();dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }
}
