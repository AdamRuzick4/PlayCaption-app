package cz.titulky.tv

import android.app.ActivityManager
import android.content.Context

/** Keep the TV decoder and OCR resident; avoid a second ML translation engine. */
object BraviaPolicy {
    fun constrained(context: Context?): Boolean {
        if (context == null || Rezim.jeTelefon(context)) return false
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            needsConservativeProfile(memory.totalMem, am.isLowRamDevice)
        }.getOrDefault(true)
    }

    fun needsConservativeProfile(totalBytes: Long, lowRam: Boolean): Boolean =
        lowRam || totalBytes <= 0 || totalBytes < 2500L * 1024 * 1024
}
