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
    @Synchronized fun zapisTts(context: android.content.Context, udalost: String) {
        runCatching {
            val soubor = File(context.applicationContext.filesDir, "tts.log")
            if (soubor.length() > 500_000) {
                val zachovat = runCatching { soubor.readText().takeLast(250_000) }.getOrDefault("")
                soubor.writeText("--- starší část logu odstraněna kvůli limitu velikosti ---\n$zachovat")
            }
            val cas = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            soubor.appendText("$cas $udalost\n")
        }
    }
    fun ukaz(a: Activity, sVyvojem: Boolean = false) {
        fun konec(jmeno: String, radku: Int): String = runCatching {
            val f = File(a.filesDir, jmeno)
            if (!f.exists()) "(soubor $jmeno neexistuje)"
            else f.readLines().takeLast(radku).joinToString("\n").ifBlank { "(prázdný)" }
        }.getOrDefault("(nejde přečíst $jmeno)")
        // chiaki.log: opakované "re-sending" řádky jen spočítat, ať je vidět i to ostatní
        fun chiaki(): String = runCatching {
            val f = File(a.filesDir, "chiaki.log")
            if (!f.exists()) return@runCatching "(soubor chiaki.log neexistuje)"
            val radky = f.readLines().takeLast(3000)
            val znovu = radky.count { it.contains("re-sending") }
            "(posledních ${radky.size} řádků, z toho opakované odeslání: $znovu×)\n" +
                radky.filter { !it.contains("re-sending") }.takeLast(40).joinToString("\n")
        }.getOrDefault("(nejde přečíst chiaki.log)")
        // připojení a odpojení zvlášť – v titulky.log by je titulky rychle odsunuly z dohledu
        fun spojeni(): String = runCatching {
            val f = File(a.filesDir, "titulky.log")
            if (!f.exists()) return@runCatching "(nic)"
            val klice = listOf("připojuji", "PŘIPOJENO", "UKONČENO", "žádný obraz", "převz", "odpoj", "Hotovo – hrát",
                "Xbox:", "staré spojení", "kodek", "obraz: ", "jiné zařízení", "ukončeno v aplikaci")
            f.readLines().filter { r -> klice.any { it in r } }.takeLast(30).joinToString("\n").ifBlank { "(nic)" }
        }.getOrDefault("(nejde přečíst)")
        val verze = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull()
        val obsah = "Verze: $verze   Stav: ${RemotePlayService.stav}\n\n=== pád ===\n" + konec("pad.txt", 25) +
            "\n\n=== spojení (připojení / odpojení) ===\n" + spojeni() +
            "\n\n=== titulky.log ===\n" + konec("titulky.log", 120) +
            "\n\n=== tts.log ===\n" + konec("tts.log", 300) +
            "\n\n=== xbox.log ===\n" + konec("xbox.log", 15) +
            "\n\n=== chiaki.log ===\n" + chiaki()
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
            .setNeutralButton("Uložit TTS log…") { _, _ -> exportujTts(a) }
        if (sVyvojem) b.setNegativeButton("Vývoj") { _, _ -> a.startActivity(Intent(a, VyvojActivity::class.java)) }
        b.show()
    }

    private fun exportujTts(a: Activity) {
        if (runCatching { File(a.filesDir, "tts.log").length() }.getOrDefault(0L) <= 0L) {
            android.widget.Toast.makeText(a, "TTS log je prázdný", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { a.startActivity(Intent(a, TtsLogExportActivity::class.java)) }
            .onFailure { android.widget.Toast.makeText(a, "Výběr umístění pro TTS log se nepodařil", android.widget.Toast.LENGTH_LONG).show() }
    }
}
