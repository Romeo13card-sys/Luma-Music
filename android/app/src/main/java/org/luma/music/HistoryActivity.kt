package org.luma.music

import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*
import org.json.*

class HistoryActivity:MaterialActivity(),PersonalLibrary.Observer {
    private val handler=Handler(Looper.getMainLooper())
    private var visible=false;private var seen=-1L;private var ticket=0
    private var bookmarks=false;private var items=JSONArray()
    private lateinit var adapter:BaseAdapter
    private lateinit var status:TextView
    private val tick=object:Runnable{override fun run(){if(!visible)return;if(seen!=PersonalLibrary.revision){seen=PersonalLibrary.revision;reload()};handler.postDelayed(this,1000)}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);bookmarks=state?.getBoolean("bookmarks") ?: intent.getBooleanExtra("bookmarks",false)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(AppThemes.active.background)}
        root.addView(MusicUi.button(this,"Назад"){finish()})
        val tabs=LinearLayout(this)
        tabs.addView(MusicUi.button(this,"История"){bookmarks=false;reload()},LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(MusicUi.button(this,"Закладки"){bookmarks=true;reload()},LinearLayout.LayoutParams(0,-2,1f));root.addView(tabs)
        status=MusicUi.text(this,"",14f);root.addView(status)
        root.addView(MusicUi.button(this,"Очистить список"){
            LumaDialog(this).setTitle(if(bookmarks)"Очистить закладки?" else "Очистить историю?")
                .setMessage("Скачанная музыка и плейлисты сохранятся.").setNegativeButton("Отмена",null)
                .setPositiveButton("Очистить"){_,_->PersonalLibrary.request(this,this,++ticket,if(bookmarks)"bookmark_clear" else "history_clear")}.show()
        })
        adapter=object:BaseAdapter(){
            override fun getCount()=items.length()
            override fun getItem(position:Int)=items.getJSONObject(position)
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val row=recycled as? SongRow ?: SongRow(this@HistoryActivity)
                val item=getItem(position);val song=Song.from(item.getJSONObject("song"));row.bind(song)
                val at=item.optInt("position")/1000
                row.artist.text=(if(bookmarks)item.optString("label").takeIf {it.isNotBlank()}?.plus(" · ").orEmpty() else "")+song.artist+" · "+(at/60)+":"+(at%60).toString().padStart(2,'0')
                row.more.setOnClickListener {actions(item,song)}
                row.setOnLongClickListener {actions(item,song);true}
                return row
            }
        }
        root.addView(ListView(this).apply {
            adapter=this@HistoryActivity.adapter;dividerHeight=MusicUi.dp(this@HistoryActivity,6)
            setOnItemClickListener {_,_,position,_->
                val record=items.getJSONObject(position);val song=Song.from(record.getJSONObject("song"))
                PlaybackService.select(this@HistoryActivity,listOf(song),0,false,record.optInt("position"))
                startActivity(Intent(this@HistoryActivity,MainActivity::class.java).putExtra("open_player",true))
            }
        },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);MusicUi.insets(root)
    }
    private fun actions(record:JSONObject,song:Song){
        LumaDialog(this).setTitle(song.title).setItems(arrayOf("Действия с треком","Удалить из списка")){_,which->
            if(which==0)TrackSheet.show(this,song)
            else PersonalLibrary.request(this,this,++ticket,if(bookmarks)"bookmark_remove" else "history_remove",JSONObject().put("id",record.getString("key")))
        }.show()
    }
    private fun reload(){
        title=L10n.text(this,if(bookmarks)"Закладки" else "История прослушивания")
        status.text=L10n.text(this,"Загрузка…")
        PersonalLibrary.request(this,this,++ticket,if(bookmarks)"bookmark_list" else "history_list")
    }
    override fun libraryResult(ticket:Int,operation:String,result:JSONObject){
        if(ticket!=this.ticket || isDestroyed)return
        if(!result.optBoolean("ok")){status.text=result.optString("error");return}
        if(operation.endsWith("_list")){
            items=result.optJSONArray("data") ?: JSONArray();adapter.notifyDataSetChanged()
            status.text=L10n.text(this,if(bookmarks)"Сохранённые моменты песен" else "Последние 100 треков на этом устройстве")+" · "+items.length()
            if(!bookmarks)PersonalLibrary.request(this,this,ticket,"history_stats")
        }else if(operation=="history_stats"){
            val stats=result.getJSONObject("data");status.text=status.text.toString()+" · "+L10n.text(this,"Запусков")+": "+stats.optLong("starts")
        }else reload()
    }
    override fun onStart(){super.onStart();visible=true;seen=-1;handler.post(tick)}
    override fun onStop(){visible=false;handler.removeCallbacks(tick);super.onStop()}
    override fun onSaveInstanceState(state:Bundle){state.putBoolean("bookmarks",bookmarks);super.onSaveInstanceState(state)}
}
