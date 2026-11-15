package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SitPrekladuTest {
    @Test fun dialogueCanStartWhileHintIsWaitingOnNetwork() {
        val pool = SitPrekladu.novaFronta()
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        try {
            repeat(2) { pool.execute { started.countDown(); finish.await() } }
            assertTrue("Both network requests must start without waiting for the other", started.await(3, TimeUnit.SECONDS))
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun slowNetworkCannotCreateUnlimitedQueue() {
        val pool = SitPrekladu.novaFronta()
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        try {
            repeat(2) { pool.execute { started.countDown(); finish.await() } }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repeat(8) { pool.execute {} }
            assertEquals(8, pool.queue.size)
            try {
                pool.execute {}
                fail("Queue must reject requests beyond its fixed capacity")
            } catch (_: RejectedExecutionException) { }
        } finally { finish.countDown(); pool.shutdownNow() }
    }
}
