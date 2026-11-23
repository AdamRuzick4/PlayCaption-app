package cz.titulky.tv

import org.json.JSONObject
import java.net.InetAddress

internal enum class LgChyba { ADRESA, BALICEK, KEY_SERVER, PASSPHRASE, IDENTITA, SSH, PRENOS, INSTALACE, OVERENI, CAS, ZRUSENO, RELACE, PAROVANI, KOMUNIKACE, ODPOVED }
internal class LgInstalacniChyba(val kod: LgChyba, val detail: String? = null) : Exception(kod.name + detail?.let { ": $it" }.orEmpty())
internal enum class LgFaze { BALICEK, KLIC, PRIPOJENI, PRENOS, INSTALACE, OVERENI, KONTROLA }

internal object LgInstalacniProtokol {
    fun adresaZPole(value: String) = adresa(value.replace(',', '.'))

    fun adresa(value: String): String {
        val ip = value.trim()
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 }) throw LgInstalacniChyba(LgChyba.ADRESA)
        val a = InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
        if ((!a.isSiteLocalAddress && !a.isLinkLocalAddress) || a.isLoopbackAddress || a.isMulticastAddress)
            throw LgInstalacniChyba(LgChyba.ADRESA)
        return ip
    }

    fun klicHttp(data: ByteArray): ByteArray {
        val text = data.toString(Charsets.US_ASCII)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0 || split > 8192 || !text.lineSequence().first().matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
        val headers = text.substring(0, split).lowercase()
        if ("transfer-encoding:" in headers) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
        val body = data.copyOfRange(split + 4, data.size)
        val length = Regex("(?im)^content-length: *([0-9]+)\\r?$").find(text.substring(0, split))?.groupValues?.get(1)?.toLongOrNull()
        if (length != null && length != body.size.toLong()) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
        if (!body.toString(Charsets.US_ASCII).contains("-----END RSA PRIVATE KEY-----")) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
        if (!body.toString(Charsets.US_ASCII).startsWith("-----BEGIN RSA PRIVATE KEY-----") || body.size > 16384) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
        return body
    }

    /** null = stále probíhá; true teprve pro koncový stav instalační služby. */
    fun instalace(json: JSONObject): Boolean? {
        val details = json.optJSONObject("details")
        if ((json.has("returnValue") && !json.optBoolean("returnValue")) ||
            json.optInt("errorCode", 0) != 0 || (details?.optInt("errorCode", 0) ?: 0) != 0) throw chybaOdpovedi(json)
        // LG subscription události po úvodním ACK nemají returnValue, ale nesou ID instalované aplikace.
        if (!json.has("returnValue") && (json.optString("id") != LG_APP_ID || json.optJSONObject("details") == null))
            throw chybaOdpovedi(json)
        val state = json.optJSONObject("details")?.optString("state").orEmpty().uppercase()
        if ("FAILED" in state || "ERROR" in state || state in listOf("CANCELLED", "CANCELED")) throw chybaOdpovedi(json)
        return if (state == "INSTALLED" || state == "SUCCESS" || state.startsWith("SUCCESS_")) true else null
    }

    /** Jen chybová pole LG, nikdy celý JSON, SSH výpis ani přihlašovací údaje. */
    fun chybaOdpovedi(json: JSONObject): LgInstalacniChyba {
        val details = json.optJSONObject("details")
        fun text(value: Any?, limit: Int) = (value as? String)?.replace(Regex("[\\p{C}\\s]+"), " ")?.trim()?.take(limit)?.takeIf { it.isNotEmpty() }
        val code = sequenceOf(details?.opt("errorCode"), json.opt("errorCode"))
            .mapNotNull { it?.toString()?.takeIf { code -> code.matches(Regex("-?[0-9]{1,10}")) && code != "0" } }.firstOrNull()
        val reason = sequenceOf(details?.opt("reason"), details?.opt("errorText"), json.opt("errorText"), json.opt("reason"))
            .mapNotNull { text(it, 240) }.firstOrNull()
        val state = text(details?.opt("state"), 80)
        val detail = listOfNotNull(code?.let { "errorCode=$it" }, reason?.let { "reason=$it" }, state?.let { "state=$it" })
            .joinToString(" · ").takeIf { it.isNotEmpty() }
        return LgInstalacniChyba(LgChyba.INSTALACE, detail)
    }

    fun identita(value: String, ocekavaneId: String?): String {
        if (!value.matches(Regex("lg-[a-f0-9]{12}")) || (ocekavaneId != null && value != ocekavaneId))
            throw LgInstalacniChyba(LgChyba.IDENTITA)
        return value
    }

    /** Číselné části, nikoli lexikografické řazení; neznámou verzi nikdy nepřepisujeme. */
    fun porovnejVerze(instalovana: String, dostupna: String): Int {
        fun casti(v: String): List<Int> {
            if (!v.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}"))) throw LgInstalacniChyba(LgChyba.OVERENI)
            return v.split('.').map { it.toInt() }
        }
        val a = casti(instalovana); val b = casti(dostupna)
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    fun stavVerze(instalovana: String?, dostupna: String): LgStavVerze {
        porovnejVerze(dostupna, dostupna)
        if (instalovana == null) return LgStavVerze.CHYBI
        val rozdil = porovnejVerze(instalovana, dostupna)
        return when {
            rozdil > 0 -> LgStavVerze.NOVEJSI
            rozdil == 0 -> LgStavVerze.AKTUALNI
            else -> LgStavVerze.AKTUALIZACE
        }
    }

    fun verze(json: JSONObject, balicek: LgBalicek) {
        if (json.optString("id") != LG_APP_ID || json.optString("version") != balicek.verze) throw LgInstalacniChyba(LgChyba.OVERENI)
    }
}
