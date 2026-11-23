package cz.titulky.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Pouze oficiální DeepL API, bez přesměrování a bez zapisování klíče nebo odpovědi do diagnostiky. */
internal class DeepLPreklad(
    private val cas: () -> Long = { System.nanoTime() / 1000000 },
    private val otevri: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    @Volatile var chyba: String? = null
        private set
    private var pauzaDo = 0L
    private var nastaveni = ""

    fun preloz(text: String, kontext: String, jazyk: String, klic: String): String? {
        if (!platnyKlic(klic)) { chyba = "Chybí platný API klíč DeepL"; return null }
        if (nastaveni != "$klic:$jazyk") { nastaveni = "$klic:$jazyk"; pauzaDo = 0; chyba = null }
        if (cas() < pauzaDo) return null
        var c: HttpURLConnection? = null
        return try {
            val host = if (klic.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            c = otevri(URL("https://$host/v2/translate"))
            c.instanceFollowRedirects = false
            c.connectTimeout = 1500; c.readTimeout = 2000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "DeepL-Auth-Key $klic")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val cil = when (jazyk.lowercase(Locale.ROOT)) { "en" -> "EN-GB"; "pt" -> "PT-PT"; else -> jazyk.uppercase(Locale.ROOT) }
            val telo = JSONObject().put("text", JSONArray().put(text)).put("source_lang", "EN").put("target_lang", cil)
            if (kontext.isNotBlank()) telo.put("context", kontext)
            c.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) {
                chyba = when (c.responseCode) {
                    401, 403 -> "DeepL odmítl API klíč"
                    429 -> "DeepL dočasně omezuje požadavky"
                    456 -> "Vyčerpaný limit DeepL"
                    else -> "DeepL není dostupný (HTTP ${c.responseCode})"
                }
                val pauza = c.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 300) ?: 60
                pauzaDo = cas() + pauza * 1000
                null
            } else {
                val odpoved = c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val znaky = CharArray(65537); var delka = 0
                    while (delka < znaky.size) {
                        val n = reader.read(znaky, delka, znaky.size - delka)
                        if (n < 0) break
                        delka += n
                    }
                    require(delka < znaky.size)
                    String(znaky, 0, delka)
                }
                val pole = JSONObject(odpoved).getJSONArray("translations")
                require(pole.length() == 1)
                val preklad = pole.getJSONObject(0).getString("text")
                require(preklad.isNotBlank() && preklad.length <= 12000)
                chyba = null
                preklad
            }
        } catch (_: Exception) {
            chyba = "DeepL neodpověděl nebo vrátil neplatnou odpověď"
            pauzaDo = cas() + 60000
            null
        } finally { c?.disconnect() }
    }
    companion object {
        fun platnyKlic(klic: String) = klic.length in 16..256 && klic.all { it in '!'..'~' }
    }
}
