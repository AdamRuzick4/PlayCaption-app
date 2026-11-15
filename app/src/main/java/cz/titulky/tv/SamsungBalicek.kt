package cz.titulky.tv

import android.content.Context
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest

internal const val SAMSUNG_APP_ID = "PCaption01.PlayCaptionTest"

/**
 * Přibalený Samsung přijímač. Na rozdíl od LG IPK je záměrně NEPODEPSANÝ: Samsung TV přijme
 * jen balíček podepsaný certifikátem s DUID konkrétní televize, takže jeden podepsaný soubor
 * pro všechny uživatele nejde vyrobit. Podpis proto vzniká až při instalaci na dané TV.
 */
internal data class SamsungBalicek(val verze: String, val sha256: String, val data: ByteArray) {
    val soubor get() = "PlayCaption-Samsung-$verze-nepodpisany.wgt"

    companion object {
        fun over(manifest: String, data: ByteArray): SamsungBalicek {
            val m = JSONObject(manifest)
            val v = m.getString("version")
            require(m.getInt("schema") == 1 && m.getString("platform") == "tizen" && m.getString("id") == SAMSUNG_APP_ID)
            require(SamsungPrijimac.verze(v) != null)
            require(!m.getBoolean("signed"))
            require(m.getString("file") == "PlayCaption-Samsung-$v-nepodpisany.wgt")
            require(data.size.toLong() == m.getLong("size") && data.size > 4)
            // WGT je ZIP
            require(data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte())
            val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            require(m.getString("sha256") == hash)
            return SamsungBalicek(v, hash, data)
        }

        fun zAssets(context: Context): SamsungBalicek {
            val assets = context.applicationContext.assets
            val manifest = assets.open("samsung/receiver-manifest.json").bufferedReader().use { it.readText() }
            val data = assets.open("samsung/receiver.wgt").use { it.readBytes() }
            return over(manifest, data)
        }
    }
}

/** Co o běžícím přijímači víme z jeho vlastního ohlášení v síti. */
internal enum class SamsungStavVerze { NEHLASI, AKTUALIZACE, AKTUALNI, NOVEJSI }

internal object SamsungPrijimac {
    private val ID = Regex("samsung-[a-f0-9]{12}")
    private val VERZE = Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}")

    fun jeSamsung(id: String) = ID.matches(id)

    /** Jen číselná verze x.y.z; cokoli jiného je "neznámá", nikdy ne nula. */
    fun verze(text: String?): String? = text?.trim()?.takeIf { VERZE.matches(it) }

    /**
     * Přijímače do 1.2.0 verzi nehlásily → NEHLASI (nelze tvrdit, že je aktuální).
     * Novější přijímač než přibalený se nikdy nedoporučuje přepsat starším.
     */
    fun stav(hlasena: String?, dostupna: String): SamsungStavVerze {
        val b = requireNotNull(verze(dostupna)).split('.').map { it.toInt() }
        val a = verze(hlasena)?.split('.')?.map { it.toInt() } ?: return SamsungStavVerze.NEHLASI
        for (i in 0..2) if (a[i] != b[i]) return if (a[i] > b[i]) SamsungStavVerze.NOVEJSI else SamsungStavVerze.AKTUALIZACE
        return SamsungStavVerze.AKTUALNI
    }

    /** Adresa tohoto zařízení v domácí síti; zadává se na TV jako "Host PC IP". */
    fun adresaTelefonu(adresy: List<InetAddress>): String? =
        adresy.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress

    fun adresaTelefonu(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            // Wi-Fi má přednost před mobilní sítí a VPN
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .firstNotNullOfOrNull { adresaTelefonu(it.inetAddresses.toList()) }
    }.getOrNull()
}
