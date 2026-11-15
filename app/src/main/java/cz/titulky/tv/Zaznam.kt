package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Okno se záznamem (pád, titulky.log, chiaki.log) – stačí vyfotit a poslat. */
object Zaznam {
    const val EXPORT_REQUEST = 19471

    fun export(a: Activity) {
        runCatching {
            a.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "PlayCaption-Bravia-zaznam.zip")
            }, EXPORT_REQUEST)
        }.onFailure {
            AlertDialog.Builder(a).setMessage("Televize nemá systémový výběr souboru. Záznam lze vyfotit nebo získat přes adb z interní složky aplikace.").setPositiveButton("OK", null).show()
        }
    }

    fun uloz(a: Activity, uri: android.net.Uri) {
        val context = a.applicationContext
        Thread({
            val result = runCatching {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("Soubor nejde otevřít")
                java.util.zip.ZipOutputStream(stream).use { zip ->
                    val names = listOf("pad.txt", "ukonceni-system.log", "diagnostika.log", "diagnostika-predchozi.log", "titulky.log", "chiaki.log")
                    for (name in names) {
                        val file = File(context.filesDir, name)
                        if (!file.exists()) continue
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(LogTail.read(file, 3000, 256 * 1024).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    context.filesDir.listFiles()?.filter { it.name.startsWith("system-trace-") && it.length() <= 256 * 1024 }
                        ?.forEach { file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            }
            a.runOnUiThread {
                if (!a.isFinishing && !a.isDestroyed)
                    AlertDialog.Builder(a).setMessage(if (result.isSuccess) "Záznam uložen." else "Záznam se nepodařilo uložit: ${result.exceptionOrNull()?.message}").setPositiveButton("OK", null).show()
            }
        }, "export-zaznamu").start()
    }
    fun ukaz(a: Activity, sVyvojem: Boolean = false) {
        val cekani = AlertDialog.Builder(a).setTitle("Záznam")
            .setMessage("Načítám záznam…")
            .setNegativeButton("Zrušit", null).show()
        val context = a.applicationContext
        Thread({
            val obsah = runCatching { nacti(context) }
                .getOrElse { "Záznam se nepodařilo načíst: ${it.javaClass.simpleName}" }
            a.runOnUiThread {
                if (!a.isFinishing && !a.isDestroyed && cekani.isShowing) {
                    cekani.dismiss()
                    zobraz(a, obsah, sVyvojem)
                }
            }
        }, "nacitani-zaznamu").apply { isDaemon = true }.start()
    }

    private fun nacti(a: android.content.Context): String {
        fun konec(jmeno: String, radku: Int): String = runCatching {
            val f = File(a.filesDir, jmeno)
            if (!f.exists()) "(soubor $jmeno neexistuje)"
            else LogTail.read(f, radku)
        }.getOrDefault("(nejde přečíst $jmeno)")
        // chiaki.log: opakované "re-sending" řádky jen spočítat, ať je vidět i to ostatní
        fun chiaki(): String = runCatching {
            val f = File(a.filesDir, "chiaki.log")
            if (!f.exists()) return@runCatching "(soubor chiaki.log neexistuje)"
            val radky = LogTail.read(f, 3000).lines()
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
            LogTail.read(f, 1000).lines().filter { r -> klice.any { it in r } }.takeLast(30).joinToString("\n").ifBlank { "(nic)" }
        }.getOrDefault("(nejde přečíst)")
        val verze = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull()
        val obsah = "Verze: $verze   Stav: ${RemotePlayService.stav}\n\n=== pád ===\n" + konec("pad.txt", 100) +
            "\n\n=== důvod ukončení od Androidu ===\n" + konec("ukonceni-system.log", 30) +
            "\n\n=== starší diagnostika ===\n" + konec("diagnostika-predchozi.log", 25) +
            "\n\n=== paměť a poslední kroky ===\n" + konec("diagnostika.log", 100) +
            "\n\n=== spojení (připojení / odpojení) ===\n" + spojeni() +
            "\n\n=== titulky.log ===\n" + konec("titulky.log", 120) +
            "\n\n=== xbox.log ===\n" + konec("xbox.log", 15) +
            "\n\n=== chiaki.log ===\n" + chiaki()
        return obsah
    }

    private fun zobraz(a: Activity, obsah: String, sVyvojem: Boolean) {
        val d = a.resources.displayMetrics.density
        val televize = !Rezim.jeTelefon(a)
        val tv = TextView(a).apply {
            text = obsah
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            // Selectable text captures D-pad focus on Android TV.
            setTextIsSelectable(!televize)
            isFocusable = !televize
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(a).apply {
            id = View.generateViewId()
            addView(tv)
            if (televize) {
                isFocusable = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
        val b = AlertDialog.Builder(a)
            .setTitle("Záznam")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .setNegativeButton("Uložit ZIP") { _, _ -> export(a) }
        if (sVyvojem) b.setNeutralButton("Vývoj") { _, _ -> a.startActivity(Intent(a, VyvojActivity::class.java)) }
        val dialog = b.create()
        if (televize) dialog.setOnShowListener {
            val buttons = listOfNotNull(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            ).filter { it.visibility == View.VISIBLE }
            val export = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            buttons.forEach { button ->
                button.isFocusable = true
                button.nextFocusUpId = scroll.id
                button.setOnFocusChangeListener { _, focused ->
                    button.setTextColor(if (focused) Color.YELLOW else Color.WHITE)
                }
            }
            scroll.nextFocusDownId = export.id
            dialog.setOnKeyListener { _, key, event ->
                val focusedButton = buttons.indexOfFirst { it === dialog.currentFocus }
                when {
                    key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val visualOrder = buttons.sortedBy { it.left }
                            val index = visualOrder.indexOfFirst { it === dialog.currentFocus }
                            if (index < 0) export.requestFocus()
                            else visualOrder[(index + (if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else visualOrder.size - 1)) % visualOrder.size].requestFocus()
                        }
                        true
                    }
                    focusedButton >= 0 && key == KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN) scroll.requestFocus()
                        true
                    }
                    focusedButton >= 0 && key == KeyEvent.KEYCODE_DPAD_DOWN -> true
                    dialog.currentFocus === scroll && key == KeyEvent.KEYCODE_DPAD_DOWN && !scroll.canScrollVertically(1) -> {
                        if (event.action == KeyEvent.ACTION_DOWN) export.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            // Start on the action the user needs; no scrolling through the log required.
            export.post { export.requestFocus() }
        }
        dialog.show()
    }
}
