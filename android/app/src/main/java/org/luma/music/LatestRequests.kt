package org.luma.music

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One in-flight read and one latest pending read; obsolete work never starts a request. */
class LatestRequests:AutoCloseable {
    private val epoch=AtomicLong()
    private val closed=AtomicBoolean()
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1))
    class Ticket internal constructor(private val check:()->Boolean) {
        val valid get()=check()
    }
    @Synchronized fun submit(action:(Ticket)->Unit):Boolean {
        if(closed.get()) return false
        val id=epoch.incrementAndGet()
        worker.queue.clear()
        val ticket=Ticket {!closed.get() && epoch.get()==id}
        worker.execute {if(ticket.valid) action(ticket)}
        return true
    }
    @Synchronized fun invalidate() {epoch.incrementAndGet();worker.queue.clear()}
    @Synchronized override fun close() {
        closed.set(true);invalidate();worker.shutdownNow()
    }
}
