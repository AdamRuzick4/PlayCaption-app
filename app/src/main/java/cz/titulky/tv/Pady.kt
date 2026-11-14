package cz.titulky.tv

import android.content.Context
import java.io.File

/** Když aplikace spadne, zapíše důvod do files/pad.txt (na telefonu ho ukáže podržení loga). */
object Pady {
    @Volatile private var nastaveno = false

    fun hlidej(context: Context) {
        if (nastaveno) return
        nastaveno = true
        val soubor = File(context.applicationContext.filesDir, "pad.txt")
        val puvodni = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { vlakno, chyba ->
            runCatching {
                val cas = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val stopa = chyba.stackTraceToString().lines().take(18).joinToString("\n")
                soubor.writeText("$cas vlákno ${vlakno.name}\n$stopa\n")
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
