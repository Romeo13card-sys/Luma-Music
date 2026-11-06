package org.luma.music

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LatestRequestsTest {
    @Test fun onlyNewestQueuedRequestRunsAndStaleResultCannotPublish() {
        val requests=LatestRequests()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val old=AtomicInteger();val current=AtomicInteger();val stale=AtomicInteger()
        try {
            requests.submit {ticket ->
                entered.countDown();release.await(3,TimeUnit.SECONDS)
                if(ticket.valid) stale.incrementAndGet()
            }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            repeat(100) {requests.submit {old.incrementAndGet()}}
            requests.submit {current.incrementAndGet();finished.countDown()}
            release.countDown()
            assertTrue(finished.await(3,TimeUnit.SECONDS))
            assertEquals(0,old.get());assertEquals(0,stale.get());assertEquals(1,current.get())
        } finally {release.countDown();requests.close()}
    }
    @Test fun closingRejectsNewRequests() {
        val requests=LatestRequests();requests.close()
        assertFalse(requests.submit {error("Must not run")})
    }
}
