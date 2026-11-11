package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Okno se záznamem (pád, titulky.log, chiaki.log) – stačí vyfotit a poslat. */
object Zaznam {
    fun ukaz(a: Activity, sVyvojem: Boolean = false) {
        fun konec(jmeno: String, radku: Int): String = runCatching {
            val f = File(a.filesDir, jmeno)
            if (!f.exists()) "(soubor $jmeno neexistuje)"
            else f.readLines().takeLast(radku).joinToString("\n").ifBlank { "(prázdný)" }
        }.getOrDefault("(nejde přečíst $jmeno)")
        val verze = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull()
        val obsah = "Verze: $verze   Stav: ${RemotePlayService.stav}\n\n=== pád ===\n" + konec("pad.txt", 25) +
            "\n\n=== titulky.log ===\n" + konec("titulky.log", 25) +
            "\n\n=== chiaki.log ===\n" + konec("chiaki.log", 40)
        val d = a.resources.displayMetrics.density
        val tv = TextView(a).apply {
            text = obsah
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setTextIsSelectable(true)
            isFocusable = true
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        val b = AlertDialog.Builder(a)
            .setTitle("Záznam")
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("OK", null)
        if (sVyvojem) b.setNeutralButton("Vývoj") { _, _ -> a.startActivity(Intent(a, VyvojActivity::class.java)) }
        b.show()
    }
}
