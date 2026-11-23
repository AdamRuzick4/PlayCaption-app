package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.concurrent.Executors

internal class ImportPrekladu(private val activity: Activity, private val nast: Nastaveni) {
    private val knihovna = VlastniKnihovna.pro(activity)

    fun ukaz() {
        val pocet = knihovna.pocet()
        val stav = if (nast.vlastniPreklady) "Zapnuto" else "Vypnuto"
        val dialog = AlertDialog.Builder(activity).setTitle("Vlastní knihovna překladů")
            .setMessage("$stav · načteno replik: $pocet\nVyber JSON s poli original a czech. Nový soubor nahradí předchozí knihovnu.")
            .setPositiveButton("Načíst soubor") { _, _ ->
                val vyber = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                try { activity.startActivityForResult(vyber, SOUBOR) }
                catch (_: android.content.ActivityNotFoundException) { oznam("Výběr souboru není dostupný.") }
            }
        if (pocet > 0) {
            dialog.setNeutralButton(if (nast.vlastniPreklady) "Vypnout" else "Zapnout") { _, _ ->
                nast.vlastniPreklady = !nast.vlastniPreklady
            }
            dialog.setNegativeButton("Smazat") { _, _ ->
                AlertDialog.Builder(activity).setMessage("Smazat načtené překlady ze zařízení?")
                    .setNegativeButton("Zrušit", null).setPositiveButton("Smazat") { _, _ ->
                        pracuj("Knihovnu se nepodařilo smazat.") {
                            knihovna.smaz()
                            nast.vlastniPreklady = false
                            "Knihovna byla smazána."
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    fun nacti(uri: Uri) {
        oznam("Načítám knihovnu…")
        pracuj("Import se nezdařil. Zkontroluj soubor JSON a volné místo v zařízení.") {
            val pocet = requireNotNull(activity.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8)
                .use { knihovna.nacti(it) }
            nast.vlastniPreklady = true
            "Načteno $pocet replik."
        }
    }

    private fun pracuj(chyba: String, akce: () -> String) {
        zapis.execute {
            val vysledek = try { akce() } catch (_: Exception) { chyba }
            activity.runOnUiThread { oznam(vysledek) }
        }
    }

    private fun oznam(text: String) = Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        const val SOUBOR = 721
        private val zapis = Executors.newSingleThreadExecutor()
    }
}
