package cz.titulky.tv

import android.content.Context
import java.io.File

/** Když aplikace spadne, zapíše důvod do files/pad.txt (na telefonu ho ukáže podržení loga). */
object Pady {
    @Volatile private var nastaveno = false
    private var rezerva: ByteArray? = ByteArray(64 * 1024)

    fun hlidej(context: Context) {
        if (nastaveno) return
        nastaveno = true
        val soubor = File(context.applicationContext.filesDir, "pad.txt")
        val puvodni = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { vlakno, chyba ->
            rezerva = null
            runCatching {
                val cas = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                // Write directly: building the entire trace String can fail during OOM.
                soubor.printWriter().use { w ->
                    w.println("$cas vlákno ${vlakno.name}; ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; API ${android.os.Build.VERSION.SDK_INT}")
                    w.println("FÁZE: ${Diagnostika.phase}")
                    w.println(Diagnostika.lastMemory)
                    chyba.printStackTrace(w)
                }
            }
            // zapamatovat, že aplikace spadla – při dalším otevření se v režimu „Jen televize“ nepřipojí sama
            runCatching {
                context.applicationContext.getSharedPreferences("uspora", Context.MODE_PRIVATE)
                    .edit().putBoolean("padlo", true).commit()
            }
            puvodni?.uncaughtException(vlakno, chyba)
        }
    }
}
