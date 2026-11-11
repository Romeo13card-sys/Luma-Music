package org.luma.music

import android.app.Activity
import android.content.*
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

/** One shared mobile action sheet, adapted from YAYMA's TrackContextActions. */
object TrackSheet {
    fun show(activity:Activity,song:Song,position:Int?=null,changed:()->Unit={}):android.app.Dialog {
        return Controller(activity,song,position,changed).open()
    }
    private class Controller(val activity:Activity,val song:Song,val position:Int?,val changed:()->Unit):LibraryActions.Observer {
        private val dialog=BottomSheetDialog(activity)
        private val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        private val status=MusicUi.text(activity,"",14f)
        private val actions=mutableListOf<View>()
        private var closed=false
        private val queueRevision=PlaybackService.revision
        fun open():android.app.Dialog {
            val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
            header.addView(AlbumArt(activity).apply {bind(song.cover)},LinearLayout.LayoutParams(MusicUi.dp(activity,64),MusicUi.dp(activity,64)))
            val labels=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,0,0,0)
                addView(MusicUi.text(activity,song.title,19f,true,true));addView(MusicUi.text(activity,song.artist,14f,false,true))}
            header.addView(labels,LinearLayout.LayoutParams(0,-2,1f));root.addView(header);root.addView(status)
            button("Воспроизвести следующим"){edit("next")}
            button("В конец очереди"){edit("append")}
            button("Добавить закладку"){
                val millis=if(PlaybackService.queue.getOrNull(PlaybackService.index)?.id==song.id)PlaybackService.instance?.position() ?: 0 else 0
                dialog.dismiss();PersonalLibrary.bookmark(activity,song,millis)
            }
            position?.let {at->
                if(at>0)button("Переместить выше"){edit("move",at,at-1)}
                if(at<PlaybackService.queue.lastIndex)button("Переместить ниже"){edit("move",at,at+1)}
                button("Удалить из очереди"){edit("remove",at)}
            }
            val token=TokenStore(activity).load().orEmpty()
            if(song.uri==null && song.id.isNotEmpty() && song.id.all {it.isDigit()} && token.isNotBlank()){
                val state=LibraryActions.cached(token,song.id)
                if(state.isNull("liked")){button("Добавить в избранное"){reaction(token,"likes",true)};button("Убрать из избранного"){reaction(token,"likes",false)}}
                else button(if(state.optBoolean("liked"))"Убрать из избранного" else "Добавить в избранное"){reaction(token,"likes",!state.optBoolean("liked"))}
                if(state.isNull("disliked")){button("Не нравится"){reaction(token,"dislikes",true)};button("Снять отметку «Не нравится»"){reaction(token,"dislikes",false)}}
                else button(if(state.optBoolean("disliked"))"Снять отметку «Не нравится»" else "Не нравится"){reaction(token,"dislikes",!state.optBoolean("disliked"))}
            }
            if(song.uri==null && song.id.isNotEmpty() && song.id.all {it.isDigit()}){
                button("Скачать для офлайн"){
                    runCatching {OfflineDownloads.enqueue(activity,song)}.onSuccess {message("Загрузка началась");dialog.dismiss()}.onFailure {status.text=it.message}
                }
                button("Поделиться треком"){
                    runCatching {
                        val link=NativeCore.call("track_link",song.json().toString()) as String
                        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,song.title+" — "+song.artist+"\n"+link),L10n.text(activity,"Поделиться треком")))
                        dialog.dismiss()
                    }.onFailure {status.text=it.message}
                }
                button("Скопировать ссылку"){
                    runCatching {
                        val link=NativeCore.call("track_link",song.json().toString()) as String
                        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(song.title,link))
                        message("Ссылка скопирована");dialog.dismiss()
                    }.onFailure {status.text=it.message}
                }
            }
            if(activity is MainActivity){
                button("Качество звука"){dialog.dismiss();activity.openMusicSettings("sound")}
                button("Таймер сна"){dialog.dismiss();activity.openMusicSettings("player")}
            }
            button("Закрыть"){dialog.dismiss()}
            val scroll=ScrollView(activity).apply {addView(root)}
            dialog.setContentView(scroll)
            dialog.setOnDismissListener {closed=true}
            dialog.show()
            dialog.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            return dialog
        }
        private fun button(label:String,click:()->Unit){
            val button=MusicUi.button(activity,label,click);actions.add(button);root.addView(button,LinearLayout.LayoutParams(-1,-2))
        }
        private fun message(text:String){Toast.makeText(activity,L10n.text(activity,text),Toast.LENGTH_SHORT).show()}
        private fun edit(action:String,at:Int=-1,to:Int=-1){
            if(position!=null && action in listOf("remove","move") && queueRevision!=PlaybackService.revision){
                status.text=L10n.text(activity,"Очередь изменилась. Откройте меню заново.");return
            }
            runCatching {PlaybackService.editQueue(activity,action,song,at,to)}.onSuccess {changed();dialog.dismiss();message("Очередь изменена")}.onFailure {status.text=it.message}
        }
        private fun reaction(token:String,kind:String,set:Boolean){
            if(LibraryActions.react(activity,token,song,kind,set,this)){
                status.text=L10n.text(activity,"Сохраняем отметку…");actions.forEach {it.isEnabled=false}
            }else status.text=L10n.text(activity,"Дождитесь завершения предыдущего действия")
        }
        override fun reactionFinished(id:String,result:JSONObject){
            if(closed || activity.isDestroyed)return
            if(result.optBoolean("ok")){changed();message("Отметка сохранена");dialog.dismiss()}
            else {status.text=result.optString("error");actions.forEach {it.isEnabled=true}}
        }
    }
}
