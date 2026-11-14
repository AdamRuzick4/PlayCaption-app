package cz.titulky.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Po zapnutí televize spustí titulky samo (pokud je spárováno a uživatel je nevypnul).
 * Služba pak sama čeká, až se zapne PS5, a připojí se.
 */
class AutoStart : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val akce = intent.action ?: return
        if (akce != Intent.ACTION_BOOT_COMPLETED &&
            akce != "android.intent.action.QUICKBOOT_POWERON" &&
            akce != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val duvod = when {
            Rezim.jeTelefon(context) -> "telefon (spouští se jen ručně)"
            !jeZapnuto(context) -> "vypnuto uživatelem"
            !RemotePlayUdaje(context).sparovano && XboxUcet.konzole(context) == null -> "není spárováno"
            !Settings.canDrawOverlays(context) -> "chybí povolení překrytí"
            // pojistka pro slabé televize: minulá automatická spuštění neskončila úspěšným
            // připojením (televize se přetížila / spadla) → nespouštět dokola, jen ručně z aplikace
            akce != Intent.ACTION_MY_PACKAGE_REPLACED && neuspesnychStartu(context) >= 2 ->
                "minulá spuštění se nepovedla (možná přetížená televize) – spusť PlayCaption ručně"
            else -> null
        }
        zapis(context, "[autostart: $akce" + (duvod?.let { " – nespouštím: $it" } ?: " – spouštím") + "]")
        if (duvod != null) return
        prefs(context).edit().putInt("neuspesne", neuspesnychStartu(context) + 1).apply()
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            context.startForegroundService(Intent(context, RemotePlayService::class.java))
        } catch (e: Throwable) {
            zapis(context, "[autostart CHYBA: ${e.javaClass.simpleName}: ${e.message}]")
        }
    }

    companion object {
        private fun zapis(c: Context, t: String) = runCatching {
            val cas = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            java.io.File(c.filesDir, "titulky.log").appendText("$cas $t\n")
        }
        private fun prefs(c: Context) = c.getSharedPreferences("auto", Context.MODE_PRIVATE)
        /** Uživatel chce titulky automaticky (vypne se tlačítkem 8 → Zastavit). */
        fun jeZapnuto(c: Context) = prefs(c).getBoolean("zapnuto", true)
        fun nastavZapnuto(c: Context, ano: Boolean) = prefs(c).edit().putBoolean("zapnuto", ano).apply()
        fun neuspesnychStartu(c: Context) = prefs(c).getInt("neuspesne", 0)
        /** Spojení s PS5 vydrželo → automatické spouštění je zase bezpečné. */
        fun uspech(c: Context) { if (neuspesnychStartu(c) != 0) prefs(c).edit().putInt("neuspesne", 0).apply() }
    }
}
