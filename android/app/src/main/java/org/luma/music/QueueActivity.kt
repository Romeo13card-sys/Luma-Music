package org.luma.music

import android.os.*
import android.view.*
import android.widget.*

class QueueActivity:MaterialActivity(){
    private lateinit var adapter:BaseAdapter
    private lateinit var heading:TextView
    private var seen=Long.MIN_VALUE
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object:Runnable{override fun run(){
        if(seen!=PlaybackService.revision){seen=PlaybackService.revision;adapter.notifyDataSetChanged();heading.text=L10n.text(this@QueueActivity,"Очередь")+" · "+PlaybackService.queue.size}
        handler.postDelayed(this,1000)
    }}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        AppThemes.active=AppThemes.resolve(SettingsStore.get(this).getString("theme_mode","system").orEmpty(),resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(AppThemes.active.background)}
        val bar=LinearLayout(this)
        bar.addView(MusicUi.button(this,"Назад"){finish()},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(MusicUi.button(this,"Очистить очередь"){
            LumaDialog(this).setTitle("Очистить очередь?").setNegativeButton("Отмена",null).setPositiveButton("Очистить"){_,_->
                stopService(android.content.Intent(this,PlaybackService::class.java));PlaybackService.forgetQueue(this)
            }.show()
        },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(bar)
        heading=MusicUi.text(this,"Очередь",26f,true);root.addView(heading)
        root.addView(MusicUi.text(this,"Нажмите трек для воспроизведения. Меню ⋯ меняет порядок.",13f))
        adapter=object:BaseAdapter(){
            override fun getCount()=PlaybackService.queue.size
            override fun getItem(position:Int)=PlaybackService.queue[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val row=recycled as? SongRow ?: SongRow(this@QueueActivity)
                val song=getItem(position);val boundRevision=PlaybackService.revision
                row.tag=boundRevision;row.bind(song,position==PlaybackService.index)
                row.more.setOnClickListener {if(currentRow(boundRevision))TrackSheet.show(this@QueueActivity,song,position){seen=Long.MIN_VALUE}}
                row.setOnLongClickListener {if(currentRow(boundRevision))TrackSheet.show(this@QueueActivity,song,position){seen=Long.MIN_VALUE};true}
                return row
            }
        }
        val list=ListView(this).apply {
            adapter=this@QueueActivity.adapter;dividerHeight=MusicUi.dp(this@QueueActivity,6)
            setOnItemClickListener {_,view,position,_->if(currentRow(view.tag as? Long)){
                if(position==PlaybackService.index && PlaybackService.instance!=null)PlaybackService.command(this@QueueActivity,"toggle")
                else PlaybackService.command(this@QueueActivity,"play",position)
            }}
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);MusicUi.insets(root)
    }
    private fun currentRow(boundRevision:Long?):Boolean {
        if(boundRevision==PlaybackService.revision)return true
        seen=Long.MIN_VALUE;adapter.notifyDataSetChanged()
        Toast.makeText(this,L10n.text(this,"Очередь изменилась. Откройте меню заново."),Toast.LENGTH_SHORT).show()
        return false
    }
    override fun onStart(){super.onStart();seen=Long.MIN_VALUE;handler.post(tick)}
    override fun onStop(){handler.removeCallbacks(tick);super.onStop()}
}
