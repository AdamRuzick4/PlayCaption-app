package cz.titulky.tv

import android.content.Context
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** Čas i volitelné prodloužení ověřujeme přímo u LG. */
internal object LgDevMode {
    private val pokusy = ConcurrentHashMap<String, Pair<String, Long>>()
    private val bezi = ConcurrentHashMap.newKeySet<String>()
    private val chyby = ConcurrentHashMap.newKeySet<String>()
    fun bezi(id: String) = id in bezi
    fun chyba(id: String) = id in chyby

    fun obnov(context: Context, id: String, rucne: Boolean = false) {
        val app = context.applicationContext
        val store = LgDevUloziste(app)
        val verze = store.verze(id) ?: return
        val now = System.currentTimeMillis()
        val posledni = pokusy[id]?.takeIf { it.first == verze }?.second ?: store.cas(id)?.overeno ?: 0
        if (!rucne && now >= posledni && now - posledni < 3600_000) return
        if (!bezi.add(id)) return
        pokusy[id] = verze to now
        Thread {
            try {
                val token = store.token(id)
                fun zkontroluj() = LgDevCas.zOdpovedi(dotaz(token, false), System.currentTimeMillis())
                val cas = zkontroluj()
                if (store.cas(id, verze, cas)) {
                    chyby.remove(id)
                    if (cas.sekundy > TYDEN && store.chybaProdlouzeni(id)) store.vysledekProdlouzeni(id, verze, true)
                    if (maSeProdlouzit(cas) && store.zahajProdlouzeni(id, verze, now)) {
                        val novy = runCatching { prodluz(cas, { dotaz(token, true) }, ::zkontroluj) }.getOrNull()
                        if (novy != null) store.cas(id, verze, novy)
                        store.vysledekProdlouzeni(id, verze, novy != null)
                    }
                }
            } catch (_: Exception) { if (store.verze(id) == verze) chyby.add(id) }
            finally { bezi.remove(id) }
        }.apply { isDaemon = true }.start()
    }

    const val TYDEN = 7 * 24 * 3600L
    internal fun maSeProdlouzit(cas: LgDevCas) = cas.sekundy in 1..TYDEN
    internal fun povolenPokus(posledni: Long, ted: Long) = posledni <= 0 || ted < posledni || ted - posledni >= 24 * 3600_000L

    internal fun prodluz(stary: LgDevCas, pozadavek: () -> String, kontrola: () -> LgDevCas): LgDevCas {
        val odpoved = org.json.JSONObject(pozadavek())
        val vysledek = odpoved.optString("result")
        require(vysledek == "success" || (vysledek.isEmpty() && odpoved.optString("errorCode") == "200"))
        return kontrola().also { require(it.sekundy > stary.sekundy) }
    }

    private fun dotaz(token: String, prodlouzit: Boolean): String {
        require(token.matches(Regex("[A-Za-z0-9]{8,256}")))
        val akce = if (prodlouzit) "ResetDevModeSession" else "CheckDevModeSession"
        val conn = URL("https://developer.lge.com/secure/${akce}.dev?sessionToken=$token")
            .openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            require(conn.responseCode == 200)
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                val limit = System.nanoTime() + 16_000_000_000L
                while (true) {
                    require(System.nanoTime() < limit)
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 16384)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { conn.disconnect() }
    }
}
