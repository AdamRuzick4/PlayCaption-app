package cz.titulky.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, bounded breadcrumbs. Does not record credentials or subtitle text. */
object Diagnostika {
    private var context: Context? = null
    private val lock = Any()
    @Volatile var phase = "start procesu"
        private set
    @Volatile var lastMemory = "paměť zatím nezměřena"
        private set

    fun start(c: Context) {
        context = c.applicationContext
        note("START ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}, PID ${Process.myPid()}")
        Thread({
            runCatching { collectExits(c.applicationContext) }
                .onFailure { note("historie ukončení nedostupná: ${it.javaClass.simpleName}") }
            memory()
        }, "historie-ukonceni").start()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                memory()
                handler.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    fun phase(value: String) { phase = value; note("FÁZE $value") }

    fun note(value: String) {
        val c = context ?: return
        synchronized(lock) {
            runCatching {
                val f = File(c.filesDir, "diagnostika.log")
                if (f.length() > 192 * 1024) {
                    val old = File(c.filesDir, "diagnostika-predchozi.log")
                    old.delete()
                    f.renameTo(old)
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                f.appendText("$time $value\n")
            }
        }
    }

    fun memory() {
        val c = context ?: return
        runCatching {
            val am = c.getSystemService(ActivityManager::class.java)
            val m = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val rt = Runtime.getRuntime()
            lastMemory = "RAM total=${m.totalMem / 1048576} MiB volná=${m.availMem / 1048576} MiB low=${m.lowMemory}; heap=${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MiB; native=${Debug.getNativeHeapAllocatedSize() / 1048576} MiB; memoryClass=${am.memoryClass} MiB"
            note("PAMĚŤ $lastMemory; fáze=$phase")
        }
    }

    private fun collectExits(c: Context) {
        val out = File(c.filesDir, "ukonceni-system.log")
        if (Build.VERSION.SDK_INT < 30) {
            out.writeText("Android ${Build.VERSION.RELEASE}: systémovou historii ukončení poskytuje až Android 11. Nativní pád / ukončení systémem zde vyžaduje adb logcat nebo bugreport.\n")
            return
        }
        val am = c.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(c.packageName, 0, 8)
        val report = StringBuilder("Historie předchozích procesů (čas je čas ukončení):\n")
        for (e in exits) {
            val reason = when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "NEDOSTATEK PAMĚTI"
                ApplicationExitInfo.REASON_CRASH -> "JAVA/KOTLIN CHYBA"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVNÍ PÁD"
                ApplicationExitInfo.REASON_ANR -> "APLIKACE NEREAGOVALA"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNÁL (SIGKILL sám o sobě nedokazuje nedostatek paměti)"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "UKONČENO UŽIVATELEM / SYSTÉMOVÝM NASTAVENÍM"
                else -> "jiný důvod"
            }
            report.append("${Date(e.timestamp)} pid=${e.pid} reason=${e.reason} $reason status=${e.status} importance=${e.importance} PSS=${e.pss} KiB RSS=${e.rss} KiB popis=${e.description}\n")
        }
        if (exits.isEmpty()) report.append("Systém zatím nevrátil žádný záznam.\n")
        out.writeText(report.toString())
        // Android 12+ may supply a binary protobuf tombstone. Preserve it as binary.
        val latest = exits.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
        if (latest != null) runCatching {
            latest.traceInputStream?.use { input ->
                File(c.filesDir, "system-trace-${latest.timestamp}.bin").outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var remaining = 256 * 1024
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            c.filesDir.listFiles()?.filter { it.name.startsWith("system-trace-") }
                ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        }
    }
}
