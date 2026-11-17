package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PribehPodporaSitTest {
    private val puvodniTtsCas = TelefonTitulky.ttsCas
    @org.junit.Before fun nastavTtsCas() { TelefonTitulky.ttsCas = { System.nanoTime() / 1_000_000 } }
    @org.junit.After fun obnovTtsCas() { TelefonTitulky.ttsCas = puvodniTtsCas }

    private val ip = "127.0.0.1"
    private val token = "0123456789abcdef0123456789abcdef.1.0.0"

    private fun prijmi(tv: DatagramSocket, prefix: String, obsah: String = ""): DatagramPacket {
        val doKdy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        do {
            val p = DatagramPacket(ByteArray(8192), 8192)
            tv.receive(p)
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            if (text.startsWith(prefix) && obsah in text) return p
        } while (System.nanoTime() < doKdy)
        error("Paket $prefix nedorazil")
    }

    private fun odpovez(tv: DatagramSocket, dotaz: DatagramPacket, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        tv.send(DatagramPacket(data, data.size, dotaz.address, dotaz.port))
    }

    private fun pockejNaPodporu(sit: PribehSpojeni) {
        val doKdy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!sit.podporujePribeh && System.nanoTime() < doKdy) Thread.sleep(10)
        assertTrue("Chybí platné potvrzení PCA1", sit.podporujePribeh)
    }

    @Test fun lgASamsungPoPca1PrejdouZeStandardnihoNaStory() {
        for (platforma in listOf(TvPlatforma.WEBOS, TvPlatforma.SAMSUNG)) {
            DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName(ip)).use { tv ->
                tv.soTimeout = 3000
                PribehSpojeni(ip, cas = { System.nanoTime() / 1_000_000 }).use { sit ->
                    try {
                        Propojeni.Vysilac.nastavCil(ip, platforma.name)
                        Propojeni.Vysilac.pribehRidi = PodporaPribehu.pouzit(ip, platforma, null)
                        sit.zprava = PribehProtokol.dotaz(token)
                        val dotaz = prijmi(tv, "PCQ1\t")
                        assertFalse(sit.podporujePribeh)
                        Propojeni.Vysilac.zmena("Standardní během ověření", 6000, "")
                        val standard = prijmi(tv, "PCT1\t", "Standardní během ověření")
                        assertTrue(String(standard.data, 0, standard.length, Charsets.UTF_8).contains("Standardní během ověření"))
                        odpovez(tv, dotaz, "PCA1\t$token\tready")
                        pockejNaPodporu(sit)
                        Propojeni.Vysilac.pribehRidi = PodporaPribehu.pouzit(ip, platforma, sit.ip)
                        TelefonTitulky.nastav("", 0)
                        assertTrue(Propojeni.Vysilac.pribehRidi)
                        sit.zprava = PribehProtokol.titulek(token, "Celá Story replika", false,
                            floatArrayOf(0.5f, 0.9f, 0.8f, 1f), intArrayOf())
                        val story = prijmi(tv, "PCS1\t")
                        assertTrue(String(story.data, 0, story.length, Charsets.UTF_8).contains("Celá Story replika"))
                    } finally {
                        Propojeni.Vysilac.pribehRidi = false
                        Propojeni.Vysilac.nastavCil(null, "")
                    }
                }
            }
        }
    }

    @Test fun neodpovidajiciPrijimacNevypnePct1() {
        DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName(ip)).use { tv ->
            tv.soTimeout = 3000
            PribehSpojeni(ip, cas = { 0L }).use { sit ->
                try {
                    Propojeni.Vysilac.nastavCil(ip, "Neznámá starší TV")
                    sit.zprava = PribehProtokol.dotaz(token)
                    repeat(2) { prijmi(tv, "PCQ1\t") }
                    assertFalse(sit.podporujePribeh)
                    Propojeni.Vysilac.pribehRidi = PodporaPribehu.pouzit(ip, TvPlatforma.NEZNAMA, null)
                    Propojeni.Vysilac.zmena("Titulky se neztratí", 6000, "")
                    val p = prijmi(tv, "PCT1\t", "Titulky se neztratí")
                    assertTrue(String(p.data, 0, p.length, Charsets.UTF_8).contains("Titulky se neztratí"))
                } finally {
                    Propojeni.Vysilac.pribehRidi = false
                    Propojeni.Vysilac.nastavCil(null, "")
                }
            }
        }
    }

    @Test fun ciziAdresaStaryTokenAniParovaciOdpovedNepotvrdiStory() {
        DatagramSocket(0, InetAddress.getByName(ip)).use { tv ->
            tv.soTimeout = 3000
            PribehSpojeni(ip, tv.localPort, cas = { 0L }).use { sit ->
                sit.zprava = PribehProtokol.dotaz(token)
                val dotaz = prijmi(tv, "PCQ1\t")
                DatagramSocket().use { cizi -> odpovez(cizi, dotaz, "PCA1\t$token\tready") }
                prijmi(tv, "PCQ1\t")
                assertFalse(sit.podporujePribeh)
                odpovez(tv, dotaz, "PCA1\t${token.replace(".1.", ".2.")}\tready")
                prijmi(tv, "PCQ1\t")
                assertFalse(sit.podporujePribeh)
                odpovez(tv, dotaz, "PCS1\tCHYBA\tnesparovano")
                prijmi(tv, "PCQ1\t")
                assertFalse(sit.podporujePribeh)
                odpovez(tv, dotaz, "PCA1\t$token\tready")
                pockejNaPodporu(sit)
            }
        }
    }
}
