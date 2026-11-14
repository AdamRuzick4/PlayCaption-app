package cz.titulky.tv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Záznam pokusů s Xboxem (soubor xbox.log + živý výpis na testovací obrazovce). */
object XboxLog {
    @Volatile var posluchac: ((String) -> Unit)? = null
    private val cas = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun zapis(ctx: Context, radek: String) {
        val r = cas.format(Date()) + "  " + radek
        android.util.Log.i("PlayCaptionXbox", r)
        runCatching {
            val f = File(ctx.filesDir, "xbox.log")
            if (f.length() > 200_000) f.writeText("")
            f.appendText(r + "\n")
        }
        runCatching { posluchac?.invoke(r) }
    }
}
