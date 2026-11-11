package cz.titulky.tv

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Získání PSN Account ID přímo v televizi (bez počítače).
 * Stejný postup jako skript psn-account-id.ps1 / Chiaki (AGPL-3.0):
 * přihlášení v prohlížeči → adresa "redirect?code=…" → výměna kódu za token → user_id.
 */
object PsnPrihlaseni {
    private const val KLIENT = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val TAJNE = "mvaiZkRsAsI1IBkY"
    const val PRESMEROVANI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val TOKEN = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

    const val ADRESA_PRIHLASENI =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
            "&response_type=code&client_id=$KLIENT&redirect_uri=$PRESMEROVANI&scope=psn:clientapp" +
            "&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

    /** Vytáhne z adresy kód přihlášení, nebo null. */
    fun kodZAdresy(adresa: String): String? {
        if (!adresa.startsWith(PRESMEROVANI)) return null
        return Regex("[?&]code=([^&]+)").find(adresa)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    /** Vymění kód za Account ID (base64 z 8 bajtů). Volat mimo hlavní vlákno. */
    fun accountId(kod: String): String {
        val basic = "Basic " + Base64.encodeToString("$KLIENT:$TAJNE".toByteArray(), Base64.NO_WRAP)

        val s1 = URL(TOKEN).openConnection() as HttpURLConnection
        s1.requestMethod = "POST"
        s1.doOutput = true
        s1.connectTimeout = 8000; s1.readTimeout = 8000
        s1.setRequestProperty("Authorization", basic)
        s1.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val telo = "grant_type=authorization_code&code=" + URLEncoder.encode(kod, "UTF-8") + "&redirect_uri=$PRESMEROVANI&"
        s1.outputStream.use { it.write(telo.toByteArray()) }
        if (s1.responseCode != 200) error("token ${s1.responseCode}")
        val token = JSONObject(s1.inputStream.bufferedReader().readText()).getString("access_token")

        val s2 = URL(TOKEN + "/" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
        s2.connectTimeout = 8000; s2.readTimeout = 8000
        s2.setRequestProperty("Authorization", basic)
        if (s2.responseCode != 200) error("info ${s2.responseCode}")
        val userId = JSONObject(s2.inputStream.bufferedReader().readText()).getString("user_id")

        val bajty = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(BigInteger(userId).toLong()).array()
        return Base64.encodeToString(bajty, Base64.NO_WRAP)
    }
}
