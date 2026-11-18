package cz.titulky.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DeepLPrekladTest {
    private class Spojeni(url: URL, private val stav: Int = 200, private val telo: String = """{"translations":[{"text":"Kazisvět."}]}""") : HttpURLConnection(url) {
        val odeslano = ByteArrayOutputStream()
        var odpojeno = false
        override fun getOutputStream() = odeslano
        override fun getInputStream() = ByteArrayInputStream(telo.toByteArray())
        override fun getResponseCode() = stav
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "2" else null
        override fun disconnect() { odpojeno = true }
        override fun usingProxy() = false
        override fun connect() {}
    }
    @Test fun odesilaPouzeNovyTextKontextJeZvlastAJazykNeniNapevno() {
        lateinit var c: Spojeni
        val d = DeepLPreklad(otevri = { Spojeni(it).also { c = it } })
        assertEquals("Kazisvět.", d.preloz("a downer.", "I'm drunk and you are such", "cs", "test-secret-key:fx"))
        val body = JSONObject(c.odeslano.toString("UTF-8"))
        assertEquals("a downer.", body.getJSONArray("text").getString(0))
        assertEquals("I'm drunk and you are such", body.getString("context"))
        assertEquals("CS", body.getString("target_lang")); assertEquals("EN", body.getString("source_lang"))
        assertEquals("api-free.deepl.com", c.url.host)
        assertEquals("DeepL-Auth-Key test-secret-key:fx", c.getRequestProperty("Authorization"))
        assertFalse(c.instanceFollowRedirects); assertTrue(c.odpojeno)
        d.preloz("Go.", "", "sk", "test-secret-paid-key")
        assertEquals("api.deepl.com", c.url.host)
        assertEquals("SK", JSONObject(c.odeslano.toString("UTF-8")).getString("target_lang"))
        assertFalse(JSONObject(c.odeslano.toString("UTF-8")).has("context"))
    }
    @Test fun chybyLimityANeplatneOdpovediJsouBezTajnychDetailuASProdlevou() {
        for (status in listOf(302, 400, 403, 429, 456, 500)) {
            var ted = 0L; var pokusy = 0
            val d = DeepLPreklad({ ted }, { pokusy++; Spojeni(it, status) })
            assertNull(d.preloz("English.", "", "cs", "test-secret-key:fx"))
            assertFalse(d.chyba!!.contains("secret"))
            d.preloz("Další.", "", "cs", "test-secret-key:fx"); assertEquals(1, pokusy)
            ted = 2000; d.preloz("Další.", "", "cs", "test-secret-key:fx"); assertEquals(2, pokusy)
            d.preloz("Nový klíč.", "", "cs", "different-secret-key:fx"); assertEquals(3, pokusy)
        }
        for (body in listOf("secret raw error", """{"translations":[]}""", """{"translations":[{"text":""}]}""", "x".repeat(65537))) {
            val d = DeepLPreklad(otevri = { Spojeni(it, 200, body) })
            assertNull(d.preloz("Text.", "", "cs", "test-secret-key:fx"))
            assertFalse(d.chyba!!.contains("secret"))
        }
    }
    @Test fun chybejiciNeboNebezpecnyKlicNeodeslePozadavek() {
        val d = DeepLPreklad(otevri = { fail("Neplatný klíč nesmí na síť"); error("unused") })
        for (key in listOf("", "short", "test-secret-key\nHeader", "test-secret key:fx"))
            assertNull(d.preloz("Text.", "", "cs", key))
    }
}
