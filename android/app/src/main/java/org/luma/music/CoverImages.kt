package org.luma.music

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android only decodes/draws bitmaps. Rust owns CDN URLs, transfers, disk limits and colours. */
object CoverImages {
    interface Target { fun artworkLoaded(key:String, image:Image?) }
    data class Tone(val surface:Int,val accent:Int,val onAccent:Int)
    data class Image(val bitmap:Bitmap,val light:Tone,val dark:Tone,val oled:Tone)
    private const val MAX_MEMORY=2*1024*1024
    private val main=Handler(Looper.getMainLooper())
    private val registered=AtomicBoolean()
    private val lock=Any()
    private val waiting=mutableMapOf<String,MutableList<WeakReference<Target>>>()
    private val failures=object:LinkedHashMap<String,Long>(64,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Long>?)=size>128
    }
    private val cache=object:LruCache<String,Image>(MAX_MEMORY) {
        override fun sizeOf(key:String,image:Image)=image.bitmap.allocationByteCount
    }
    private class Fetch(val root:String,val cover:String,val size:Int,val key:String,val network:Boolean,val priority:Boolean):Runnable {
        override fun run(){fetch(this)}
    }
    private val jobs=object:LinkedBlockingDeque<Runnable>(63) {
        override fun offer(job:Runnable)=if(job is Fetch && job.priority)offerFirst(job) else offerLast(job)
    }
    private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,jobs).apply {allowCoreThreadTimeOut(true)}
    fun key(cover:String,size:Int)="$cover|$size"
    fun memoryBytes()=cache.size()
    fun queuedJobs()=jobs.size
    fun clearMemory(){cache.evictAll()}
    fun clearDisk(context:Context) {
        NativeCore.call("cover_clear",JSONObject().put("root",File(context.cacheDir,"covers").absolutePath).toString())
        clearMemory()
        synchronized(lock){failures.clear()}
    }
    private fun install(context:Context) {
        if(!registered.compareAndSet(false,true))return
        context.applicationContext.registerComponentCallbacks(object:ComponentCallbacks2 {
            override fun onConfigurationChanged(config:Configuration){}
            override fun onLowMemory(){clearMemory()}
            override fun onTrimMemory(level:Int){if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)clearMemory()}
        })
    }
    fun cancel(target:Target) {
        synchronized(lock) {
            val entries=waiting.entries.iterator()
            while(entries.hasNext()) {
                val entry=entries.next()
                entry.value.removeAll {it.get()==null || it.get()===target}
                if(entry.value.isEmpty()) {
                    jobs.removeIf {it is Fetch && it.key==entry.key}
                    entries.remove()
                }
            }
        }
    }
    fun request(context:Context,cover:String,size:Int,target:Target,priority:Boolean=false) {
        val key=key(cover,size)
        val weak=WeakReference(target)
        fun deliver(image:Image?){main.post {weak.get()?.artworkLoaded(key,image)}}
        if(cover.isBlank() || cover.length>2048 || size !in intArrayOf(100,400)){deliver(null);return}
        install(context)
        cache.get(key)?.let {deliver(it);return}
        synchronized(lock) {
            if(failures[key]?.let {SystemClock.elapsedRealtime()-it<15000}==true){deliver(null);return}
            waiting[key]?.let {list->
                if(list.none {it.get()===target})list.add(weak)
                if(priority)jobs.firstOrNull {it is Fetch && it.key==key}?.let {if(jobs.remove(it))jobs.offerFirst(it)}
                return
            }
            if(waiting.size>=64){deliver(null);return}
            waiting[key]=mutableListOf(weak)
            val network=context.getSystemService(android.net.ConnectivityManager::class.java)
            val online=network.getNetworkCapabilities(network.activeNetwork)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
            try {worker.execute(Fetch(File(context.cacheDir,"covers").absolutePath,cover,size,key,online,priority || size==400))}
            catch(_:java.util.concurrent.RejectedExecutionException){waiting.remove(key);deliver(null)}
        }
    }
    private fun fetch(job:Fetch) {
        val active=synchronized(lock) {
            val alive=waiting[job.key]?.any {it.get()!=null}==true
            if(!alive)waiting.remove(job.key)
            alive
        }
        if(!active)return
        val args=JSONObject().put("root",job.root).put("cover",job.cover).put("size",job.size).put("network",job.network)
        val image=try {
            val path=NativeCore.call("cover_load",args.toString()) as? String
            path?.let {decode(it,job.size)}.also {
                if(path!=null && it==null)runCatching {NativeCore.call("cover_forget",args.toString())}
            }
        }catch(_:Exception){null}catch(_:OutOfMemoryError){clearMemory();null}
        if(image!=null)cache.put(job.key,image)
        val targets=synchronized(lock) {
            if(image==null)failures[job.key]=SystemClock.elapsedRealtime() else failures.remove(job.key)
            waiting.remove(job.key).orEmpty().toList()
        }
        main.post {targets.forEach {it.get()?.artworkLoaded(job.key,image)}}
    }
    private fun decode(path:String,size:Int):Image? {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(path,bounds)
        if(bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096)return null
        var sample=1
        while(bounds.outWidth/sample>size || bounds.outHeight/sample>size)sample*=2
        val bitmap=BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565
        }) ?: return null
        val pixels=JSONArray()
        for(y in 0..7)for(x in 0..7)pixels.put(bitmap.getPixel(x*(bitmap.width-1)/7,y*(bitmap.height-1)/7))
        val tones=NativeCore.call("cover_tones",JSONObject().put("pixels",pixels).toString()) as JSONObject
        fun tone(name:String)=tones.getJSONObject(name).let {Tone(it.getLong("surface").toInt(),it.getLong("accent").toInt(),it.getLong("onAccent").toInt())}
        return Image(bitmap,tone("light"),tone("dark"),tone("oled"))
    }
}
