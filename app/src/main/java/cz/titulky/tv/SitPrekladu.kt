package cz.titulky.tv

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object SitPrekladu {
    fun novaFronta(): ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { r -> Thread(r, "online-preklad").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
}
