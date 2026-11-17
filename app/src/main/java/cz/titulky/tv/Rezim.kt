package cz.titulky.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Pozná, jestli aplikace běží na televizi, nebo na telefonu (zařízení to o sobě hlásí samo). */
object Rezim {
    @Volatile private var ulozeno: Boolean? = null

    /** Už zjištěný režim (true = telefon), nebo null, když se ještě nezjišťoval. */
    fun ulozeny(): Boolean? = ulozeno

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
    /** Monotonic Android time in production; injectable for JVM protocol tests. */
    internal var ttsCas: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    @Volatile var text: String = ""
        private set
    /** Nejnovější vybraný text pro TTS; nezávisí na tom, zda titulky vykresluje telefon nebo TV. */
    @Volatile var textProTts: String = ""
        private set
    /** English OCR source used to gate speech and suppress repeated subtitle readings. */
    @Volatile var vstupOcr: String = ""
        private set
    @Volatile var idOcr: Long = 0L
        private set
    @Volatile var pripravenoProTts: Boolean = false
        private set
    @Volatile var deduplikovatTts: Boolean = true
        private set
    @Volatile var verzeTtsUdalosti: Long = 0L
        private set
    @Volatile var casTtsUdalostiMs: Long = 0L
        private set
    /** Notifies the active phone screen immediately when a final subtitle becomes available. */
    @Volatile var onTtsEvent: (() -> Unit)? = null
    @Volatile var napoveda: String = ""
        set(v) {
            field = v
            // režim "telefon + televize": nápovědu poslat i do televize
            Propojeni.Vysilac.zmena(text, posledniDoba, v, barvy)
        }
    @Volatile private var posledniDoba = 0L
    /** PS5 si vzalo jiné zařízení (televize) → obrazovku zavřít. */
    @Volatile var odpojeno = false
    /** Do kdy text platí (0 = dokud ho nic nepřepíše). */
    @Volatile private var doKdy = 0L

    /** Barva jména postavy u každého řádku (0 = bez barvy) – jako ve hře ("Vivian:" zeleně). */
    @Volatile var barvy: IntArray? = null
        private set

    fun nastav(
        t: String,
        doba: Long,
        barvyJmen: IntArray? = null,
        ocrVstup: String = "",
        cisloOcr: Long = 0L,
        finalniPreklad: Boolean = false,
        deduplikovat: Boolean = true
    ) {
        barvy = barvyJmen
        text = t
        nastavUdalostTts(t, ocrVstup, cisloOcr, finalniPreklad, deduplikovat)
        posledniDoba = doba
        doKdy = if (t.isNotEmpty() && doba > 0) System.currentTimeMillis() + doba else 0L
        // režim "telefon + televize": titulek poslat i do televize
        Propojeni.Vysilac.zmena(t, doba, napoveda, barvyJmen)
    }

    /** Příběhový text posílaný do externí TV se nevykresluje jako běžný titulek telefonu. */
    fun nastavPouzeProTts(
        t: String,
        zdroj: String,
        id: Long,
        pripraveno: Boolean = true,
        deduplikovat: Boolean = false
    ) {
        nastavUdalostTts(t, zdroj, id, pripraveno, deduplikovat)
    }

    private fun nastavUdalostTts(t: String, zdroj: String, id: Long, pripraveno: Boolean, deduplikovat: Boolean) {
        textProTts = t
        vstupOcr = zdroj
        idOcr = id
        pripravenoProTts = pripraveno
        deduplikovatTts = deduplikovat
        casTtsUdalostiMs = ttsCas()
        verzeTtsUdalosti++
        runCatching { onTtsEvent?.invoke() }
    }

    /** Text, který se má teď ukazovat (prošlý = prázdný). */
    fun aktualni(): String =
        if (doKdy != 0L && System.currentTimeMillis() > doKdy) "" else text
}
