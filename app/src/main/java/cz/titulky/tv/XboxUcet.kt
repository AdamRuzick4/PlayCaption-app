package cz.titulky.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Přihlášení k Xbox účtu (Microsoft) – „zadej kód na microsoft.com/link“ (jako Netflix na televizi).
 * Postup podle otevřených projektů XStreaming (Geocld, AGPL-3.0) a xbox-xcloud-player (unknownskl, MIT):
 * kód zařízení → token Microsoft → Xbox Live (XSTS) → streamovací token „xhome“ pro vlastní konzoli.
 */
object XboxUcet {
    private const val KLIENT = "1f907974-e22b-4810-a9de-d9647380c97e"
    private const val ROZSAH = "xboxlive.signin openid profile offline_access"
    private const val PREFS = "xbox"

    data class Kod(
        val uzivatelskyKod: String,
        val adresa: String,
        val zarizeniKod: String,
        val interval: Int,
        val platiDo: Long
    )

    /** Streamovací přístup: token + adresa regionu (např. https://weu.core.gssv-play-prodxhome.xboxlive.com). */
    data class Pristup(val gsToken: String, val zakladniAdresa: String, val platiDo: Long)

    @Volatile private var pristup: Pristup? = null

    fun prihlasen(ctx: Context) = !prefs(ctx).getString("refresh", null).isNullOrEmpty()
    fun jmeno(ctx: Context) = prefs(ctx).getString("jmeno", "") ?: ""

    /** Připojený Xbox (id, jméno) – null = zatím žádný. */
    fun konzole(ctx: Context): Pair<String, String>? {
        if (!prihlasen(ctx)) return null
        val id = prefs(ctx).getString("konzoleId", null) ?: return null
        return id to (prefs(ctx).getString("konzoleJmeno", "Xbox") ?: "Xbox")
    }

    /**
     * Pro „Vyhledat televizi“ v telefonu: přihlášení Xboxu z televize poslat s údaji PS5
     * (připojí se na konec zprávy DATA: \tX:<refresh>\t<id>\t<jméno>, vše v base64). Bez Xboxu "".
     */
    fun proPredani(ctx: Context): String {
        val refresh = prefs(ctx).getString("refresh", null) ?: return ""
        val k = konzole(ctx) ?: return ""
        fun b(x: String) = android.util.Base64.encodeToString(x.toByteArray(), android.util.Base64.NO_WRAP)
        return "\tX:" + b(refresh) + "\t" + b(k.first) + "\t" + b(k.second) + "\t" + b(jmeno(ctx))
    }

    /** Telefon: převzít Xbox z televize (části zprávy DATA). Vrací true, když tam Xbox byl. */
    fun prevezmi(ctx: Context, casti: List<String>): Boolean {
        val i = casti.indexOfFirst { it.startsWith("X:") }
        if (i < 0 || casti.size < i + 3) return false
        fun d(x: String) = String(android.util.Base64.decode(x, android.util.Base64.NO_WRAP))
        return runCatching {
            prefs(ctx).edit()
                .putString("refresh", d(casti[i].removePrefix("X:")))
                .putString("konzoleId", d(casti[i + 1]))
                .putString("konzoleJmeno", d(casti[i + 2]))
                .putString("jmeno", casti.getOrNull(i + 3)?.let { d(it) } ?: "")
                .remove("pristupMs").remove("pristupMsDo")
                .apply()
            pristup = null
            true
        }.getOrDefault(false)
    }

    fun ulozKonzoli(ctx: Context, id: String, jmeno: String) {
        prefs(ctx).edit().putString("konzoleId", id).putString("konzoleJmeno", jmeno).apply()
    }

    fun odhlas(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        pristup = null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 1) kód pro microsoft.com/link

    /** Vyžádá kód, který člověk zadá na microsoft.com/link. Volat mimo hlavní vlákno. */
    fun novyKod(): Kod {
        val (stav, telo) = post(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
            "client_id=$KLIENT&scope=" + enc(ROZSAH),
            "application/x-www-form-urlencoded"
        )
        if (stav != 200) error("devicecode $stav: ${telo.take(200)}")
        val j = JSONObject(telo)
        return Kod(
            uzivatelskyKod = j.getString("user_code"),
            adresa = j.optString("verification_uri", "https://www.microsoft.com/link"),
            zarizeniKod = j.getString("device_code"),
            interval = j.optInt("interval", 5).coerceIn(1, 15),
            platiDo = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000
        )
    }

    /**
     * Čeká, až člověk kód zadá. true = přihlášeno, false = vypršelo / odmítnuto / zrušeno.
     * Volat mimo hlavní vlákno.
     */
    fun cekejNaPrihlaseni(ctx: Context, kod: Kod, zruseno: () -> Boolean): Boolean {
        var interval = kod.interval
        while (!zruseno() && System.currentTimeMillis() < kod.platiDo) {
            Thread.sleep(interval * 1000L)
            if (zruseno()) return false
            val (stav, telo) = runCatching {
                post(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code") +
                        "&client_id=$KLIENT&device_code=" + enc(kod.zarizeniKod),
                    "application/x-www-form-urlencoded"
                )
            }.getOrElse { 0 to "" }
            if (stav == 200) {
                val j = JSONObject(telo)
                ulozTokeny(ctx, j)
                XboxLog.zapis(ctx, "[Xbox] přihlášeno")
                return true
            }
            val chyba = runCatching { JSONObject(telo).optString("error") }.getOrDefault("")
            when (chyba) {
                "authorization_pending", "" -> {}
                "slow_down" -> interval += 5
                else -> { XboxLog.zapis(ctx, "[Xbox] přihlášení: $chyba"); return false }
            }
        }
        return false
    }

    private fun ulozTokeny(ctx: Context, j: JSONObject) {
        prefs(ctx).edit()
            .putString("refresh", j.optString("refresh_token"))
            .putString("pristupMs", j.optString("access_token"))
            .putLong("pristupMsDo", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000)
            .apply()
    }

    // ------------------------------------------------------------------ 2) tokeny pro streamování

    /** Platný token Microsoft (obnoví ho přes refresh token). */
    private fun tokenMs(ctx: Context): String {
        val p = prefs(ctx)
        val ulozeny = p.getString("pristupMs", null)
        if (!ulozeny.isNullOrEmpty() && System.currentTimeMillis() < p.getLong("pristupMsDo", 0)) return ulozeny
        val refresh = p.getString("refresh", null) ?: error("Xbox: nepřihlášeno")
        val (stav, telo) = post(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            "client_id=$KLIENT&grant_type=refresh_token&refresh_token=" + enc(refresh) + "&scope=" + enc(ROZSAH),
            "application/x-www-form-urlencoded"
        )
        if (stav == 400) { odhlas(ctx); error("Xbox: přihlášení vypršelo – přihlas se znovu") }
        if (stav != 200) error("refresh $stav")
        val j = JSONObject(telo)
        ulozTokeny(ctx, j)
        return j.getString("access_token")
    }

    private val hlavickyXbl = mapOf(
        "x-xbl-contract-version" to "1",
        "Cache-Control" to "no-cache",
        "Origin" to "https://www.xbox.com",
        "Referer" to "https://www.xbox.com/"
    )

    /** Token pro streamování z vlastní konzole (platí ~4 h, drží se v paměti). Volat mimo hlavní vlákno. */
    fun pristup(ctx: Context): Pristup {
        pristup?.let { if (System.currentTimeMillis() < it.platiDo) return it }
        // Microsoft → Xbox Live uživatel
        val (s1, t1) = post(
            "https://user.auth.xboxlive.com/user/authenticate",
            JSONObject()
                .put("Properties", JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("RpsTicket", "d=" + tokenMs(ctx))
                    .put("SiteName", "user.auth.xboxlive.com"))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT").toString(),
            "application/json", hlavickyXbl
        )
        if (s1 != 200) error("user/authenticate $s1")
        val uzivatel = JSONObject(t1).getString("Token")

        // Xbox Live → oprávnění pro streamování (gssv)
        val (s2, t2) = post(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            JSONObject()
                .put("Properties", JSONObject()
                    .put("SandboxId", "RETAIL")
                    .put("UserTokens", JSONArray().put(uzivatel)))
                .put("RelyingParty", "http://gssv.xboxlive.com/")
                .put("TokenType", "JWT").toString(),
            "application/json", hlavickyXbl
        )
        if (s2 == 401) error("Tento Microsoft účet nemá Xbox profil (401)")
        if (s2 != 200) error("xsts $s2")
        val xsts = JSONObject(t2)
        runCatching {
            val gtg = xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).optString("gtg")
            if (gtg.isNotEmpty()) prefs(ctx).edit().putString("jmeno", gtg).apply()
        }

        // streamovací token pro vlastní konzoli (xhome)
        val (s3, t3) = post(
            "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
            JSONObject().put("token", xsts.getString("Token")).put("offeringId", "xhome").toString(),
            "application/json",
            mapOf("Cache-Control" to "no-store, must-revalidate, no-cache", "x-gssv-client" to "XboxComBrowser")
        )
        if (s3 != 200) error("xhome login $s3: ${t3.take(200)}")
        val j = JSONObject(t3)
        val regiony = j.getJSONObject("offeringSettings").getJSONArray("regions")
        var adresa = ""
        for (i in 0 until regiony.length()) {
            val r = regiony.getJSONObject(i)
            if (adresa.isEmpty() || r.optBoolean("isDefault")) adresa = r.getString("baseUri")
            if (r.optBoolean("isDefault")) break
        }
        if (adresa.isEmpty()) error("xhome: žádný region")
        val p = Pristup(
            j.getString("gsToken"), adresa.trimEnd('/'),
            System.currentTimeMillis() + j.optLong("durationInSeconds", 3600) * 1000 - 120_000
        )
        pristup = p
        return p
    }

    /** Token „transfer“ – konzole ho chce, když relace hlásí ReadyToConnect. */
    fun tokenPropojeni(ctx: Context): String {
        tokenMs(ctx) // obnoví refresh token, ať je čerstvý
        val refresh = prefs(ctx).getString("refresh", null) ?: error("Xbox: nepřihlášeno")
        val (stav, telo) = post(
            "https://login.live.com/oauth20_token.srf",
            "client_id=$KLIENT&grant_type=refresh_token&refresh_token=" + enc(refresh) +
                "&scope=" + enc("service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"),
            "application/x-www-form-urlencoded"
        )
        if (stav != 200) error("transfer token $stav")
        return JSONObject(telo).getString("access_token")
    }

    // ------------------------------------------------------------------ HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun post(adresa: String, telo: String, typ: String, hlavicky: Map<String, String> = emptyMap()): Pair<Int, String> =
        pozadavek("POST", adresa, telo, typ, hlavicky)

    fun pozadavek(metoda: String, adresa: String, telo: String?, typ: String = "application/json",
                  hlavicky: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(adresa).openConnection() as HttpURLConnection
        c.requestMethod = metoda
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        for ((k, v) in hlavicky) c.setRequestProperty(k, v)
        if (telo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", typ)
            c.outputStream.use { it.write(telo.toByteArray()) }
        }
        val kod = c.responseCode
        val text = runCatching {
            (if (kod in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        }.getOrDefault("")
        c.disconnect()
        return kod to text
    }
}
