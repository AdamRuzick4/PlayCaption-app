package cz.titulky.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Pozná, jestli aplikace běží na televizi, nebo na telefonu (zařízení to o sobě hlásí samo). */
object Rezim {
    @Volatile private var ulozeno: Boolean? = null

    fun jeTelefon(c: Context): Boolean {
        ulozeno?.let { return it }
        val um = c.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val televize = um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return (!televize).also { ulozeno = it }
    }
}

/**
 * Telefonní režim: místo kreslení přes obraz si služba sem uloží, co se má ukázat,
 * a obrazovka TelefonActivity si to každou chvíli přečte.
 */
object TelefonTitulky {
    @Volatile var text: String = ""
        private set
    @Volatile var napoveda: String = ""
        set(v) {
            field = v
            // režim "telefon + televize": nápovědu poslat i do televize
            Propojeni.Vysilac.zmena(text, posledniDoba, v)
        }
    @Volatile private var posledniDoba = 0L
    /** PS5 si vzalo jiné zařízení (televize) → obrazovku zavřít. */
    @Volatile var odpojeno = false
    /** Do kdy text platí (0 = dokud ho nic nepřepíše). */
    @Volatile private var doKdy = 0L

    fun nastav(t: String, doba: Long) {
        text = t
        posledniDoba = doba
        doKdy = if (t.isNotEmpty() && doba > 0) System.currentTimeMillis() + doba else 0L
        // režim "telefon + televize": titulek poslat i do televize
        Propojeni.Vysilac.zmena(t, doba, napoveda)
    }

    /** Text, který se má teď ukazovat (prošlý = prázdný). */
    fun aktualni(): String =
        if (doKdy != 0L && System.currentTimeMillis() > doKdy) "" else text
}
