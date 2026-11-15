package cz.titulky.tv

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

enum class TvPlatforma {
    NEZNAMA, ANDROID_TV, WEBOS, SAMSUNG;

    /** LG a Samsung jen zobrazují titulky; přihlášení ke konzoli telefonu předat neumějí. */
    val jenZobrazovac get() = this == WEBOS || this == SAMSUNG
}

data class TvProfil(val id: String, val jmeno: String, val ip: String, val platforma: TvPlatforma)

/** Profily patří odesílajícímu telefonu; místní Android TV používá původní nastavení. */
class TvProfily internal constructor(
    private val prefs: SharedPreferences,
    private val puvodni: SharedPreferences,
    private val soubor: (String) -> SharedPreferences
) {
    constructor(c: Context) : this(
        c.getSharedPreferences("tv_profily", Context.MODE_PRIVATE),
        c.getSharedPreferences("gamelingo", Context.MODE_PRIVATE),
        { c.getSharedPreferences(it, Context.MODE_PRIVATE) }
    )

    var platforma: TvPlatforma
        get() = runCatching { TvPlatforma.valueOf(prefs.getString("platforma", "NEZNAMA")!!) }.getOrDefault(TvPlatforma.NEZNAMA)
        set(v) { prefs.edit().putString("platforma", v.name).apply() }

    var pouzeTelefon: Boolean
        get() = prefs.getBoolean("pouzeTelefon", false)
        set(v) { prefs.edit().putBoolean("pouzeTelefon", v).apply() }

    val posledniId: String? get() = prefs.getString("posledni", null) ?: puvodni.getString("tvId", null)

    fun seznam(): List<TvProfil> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { najdi(it) }.sortedBy { it.jmeno }

    fun najdi(id: String): TvProfil? {
        if (id !in prefs.getStringSet("ids", emptySet()).orEmpty()) return null
        val k = klic(id)
        val p = runCatching { TvPlatforma.valueOf(prefs.getString("$k.platforma", "NEZNAMA")!!) }.getOrDefault(TvPlatforma.NEZNAMA)
        return TvProfil(id, prefs.getString("$k.jmeno", id)!!, prefs.getString("$k.ip", "")!!, p)
    }

    fun zapamatuj(id: String, jmeno: String, ip: String, zvolena: TvPlatforma? = null): TvProfil {
        val stary = najdi(id)
        val p = zvolena ?: stary?.platforma ?: TvPlatforma.NEZNAMA
        val k = klic(id)
        // Staré společné nastavení patří jen původní TV, ne každému novému profilu.
        val puvodniId = puvodni.getString("tvId", null)
        if (stary == null && (id == puvodniId || (puvodniId == null && seznam().isEmpty()))) {
            val editor = nastaveni(id).edit()
            puvodni.all.forEach { (key, value) ->
                if (key in KLICE) when (value) {
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                }
            }
            editor.apply()
        }
        prefs.edit().putStringSet("ids", prefs.getStringSet("ids", emptySet()).orEmpty() + id)
            .putString("$k.jmeno", jmeno).putString("$k.ip", ip).putString("$k.platforma", p.name).apply()
        return TvProfil(id, jmeno, ip, p)
    }

    fun vyber(id: String) {
        require(najdi(id) != null)
        aktivniId = id
        zapamatujVyber(id)
    }

    fun zapamatujVyber(id: String) {
        require(najdi(id) != null)
        prefs.edit().putString("posledni", id).putBoolean("pouzeTelefon", false).apply()
    }

    fun automaticky(nalezene: List<Propojeni.Televize>): Propojeni.Televize? {
        if (pouzeTelefon) return null
        val vhodne = nalezene.filter { platforma == TvPlatforma.NEZNAMA || najdi(it.id)?.platforma == platforma }
        // Po výběru konkrétní TV nepřesměrovat automaticky na jinou, když je původní vypnutá.
        return posledniId?.let { id -> vhodne.firstOrNull { it.id == id } } ?: vhodne.singleOrNull().takeIf { posledniId == null }
    }

    internal fun nastaveni(id: String) = soubor("tv_nastaveni_" + klic(id))

    companion object {
        @Volatile var aktivniId: String? = null
            private set
        fun lokalne() { aktivniId = null }
        private fun klic(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        private val KLICE = setOf("tvX", "tvY", "tvSirka", "tvPismo", "rozvrhZamek", "volbyZap", "volbyUkazat", "volbyZamek", "volbyRezimText", "volbyL", "volbyT", "volbyR", "volbyB", "titRucne", "titZamek", "titL", "titT", "titR", "titB")
        fun nastaveni(c: Context): SharedPreferences {
            val id = aktivniId
            return if (id != null && Rezim.jeTelefon(c)) TvProfily(c).nastaveni(id)
                else c.getSharedPreferences("gamelingo", Context.MODE_PRIVATE)
        }
    }
}
