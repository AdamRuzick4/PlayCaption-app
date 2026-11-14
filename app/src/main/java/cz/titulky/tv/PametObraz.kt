package cz.titulky.tv

import java.nio.ByteBuffer

/**
 * PS5 na telefonu: obraz přímo z dekodéru do paměti (bez grafického čipu), stejně jako u Xboxu.
 * Dělá to malá knihovna libpcobraz1.so, která sedí mezi Chiaki a systémovým dekodérem.
 * Na televizi se nepoužívá. Když něco nejde, obraz jde dál starou cestou přes OpenGL.
 */
object PametObraz {
    /** Načetla se upravená knihovna Chiaki (jen telefon) – nastavuje Chiaki.kt. */
    @Volatile var knihovnaPc = false

    /** Kam posílat snímky (vlákno dekodéru). */
    @Volatile var priSnimku: ((org.webrtc.VideoFrame) -> Unit)? = null

    @Volatile private var nacteno: Boolean? = null

    private fun pripraveno(): Boolean {
        if (!knihovnaPc) return false
        nacteno?.let { return it }
        val ok = runCatching { System.loadLibrary("pcobraz1") }.isSuccess
        nacteno = ok
        return ok
    }

    /** Zapnout/vypnout před připojením k PS5. Vrací true, když je knihovna k dispozici. */
    fun nastav(ano: Boolean, maxSirka: Int, maxVyska: Int): Boolean {
        if (!pripraveno()) return false
        return runCatching { zapni(ano, maxSirka, maxVyska) }.isSuccess
    }

    /** 1 = dekodér teď dává obraz do paměti. */
    fun bezi(): Boolean = pripraveno() && runCatching { stav() == 1 }.getOrDefault(false)
    fun formatBarev(): Int = if (pripraveno()) runCatching { barvy() }.getOrDefault(0) else 0

    @JvmStatic private external fun zapni(ano: Boolean, maxSirka: Int, maxVyska: Int)
    @JvmStatic private external fun stav(): Int
    @JvmStatic private external fun barvy(): Int

    /** Volá knihovna (vlákno dekodéru) u každého snímku. Data platí jen během volání. */
    @JvmStatic
    fun snimek(w: Int, h: Int, y: ByteBuffer, sy: Int, u: ByteBuffer, su: Int, v: ByteBuffer, sv: Int) {
        val cil = priSnimku ?: return
        val f = try {
            org.webrtc.VideoFrame(org.webrtc.JavaI420Buffer.wrap(w, h, y, sy, u, su, v, sv, null), 0, System.nanoTime())
        } catch (e: Throwable) { return }
        try { cil(f) } catch (_: Throwable) { } finally { f.release() }
    }
}
