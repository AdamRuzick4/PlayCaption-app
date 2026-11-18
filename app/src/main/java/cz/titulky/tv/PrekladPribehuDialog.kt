package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal class PrekladPribehuDialog(private val activity: Activity, private val nast: Nastaveni, private val zmena: () -> Unit) {
    private val uloziste = PrekladKlic(activity)
    fun ukaz(): AlertDialog {
        val motory = MotorPribehu.values()
        return AlertDialog.Builder(activity).setTitle("Překladač pro Story")
            .setSingleChoiceItems(motory.map { it.nazev }.toTypedArray(), motory.indexOf(nast.motorPribehu)) { d, i ->
                d.dismiss()
                if (motory[i] == MotorPribehu.DEEPL && !uloziste.ulozen) klic()
                else { nast.motorPribehu = motory[i]; zmena() }
            }
            .setNeutralButton("Klíč DeepL") { _, _ -> klic() }
            .setNegativeButton("Zavřít", null).show()
    }
    private fun klic() {
        val pole = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (uloziste.ulozen) "Klíč je uložený; prázdné pole ho zachová" else "API klíč DeepL"
            contentDescription = "API klíč DeepL"
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val zprava = TextView(activity).apply {
            text = "Platí pro nové dialogy Story; rozpracované repliky doběhnou. DeepL dostane nový anglický text a krátkou historii dialogu. Použije tvůj API účet a jeho limity; při chybě se přepne na místní ML Kit. Uložení klíče samo neověřuje účet."
            textSize = 16f
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 12, 30, 12)
            addView(zprava); addView(pole)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("DeepL pro Story").setView(box)
            .setPositiveButton("Uložit", null).setNegativeButton("Zrušit", null)
            .setNeutralButton("Smazat klíč", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = pole.text.toString().trim()
            if (value.isNotEmpty() && !DeepLPreklad.platnyKlic(value)) { pole.error = "Zadej API klíč bez mezer a zalomení řádků"; return@setOnClickListener }
            val ulozen = runCatching { if (value.isEmpty()) uloziste.nacti() != null else uloziste.uloz(value) }.getOrDefault(false)
            if (!ulozen) { pole.error = "Klíč nelze bezpečně uložit; zadej jej znovu"; return@setOnClickListener }
            nast.motorPribehu = MotorPribehu.DEEPL
            pole.text.clear(); zmena(); dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            isEnabled = uloziste.ulozen
            setOnClickListener {
                if (!runCatching { uloziste.smaz() }.getOrDefault(false)) { pole.error = "Klíč nelze smazat"; return@setOnClickListener }
                if (nast.motorPribehu == MotorPribehu.DEEPL) nast.motorPribehu = MotorPribehu.OFFLINE
                pole.text.clear(); zmena(); dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { pole.text.clear() }
    }
}
