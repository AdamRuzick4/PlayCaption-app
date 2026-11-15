package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.security.MessageDigest

class SamsungPrijimacTest {
    private val wgt = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 9, 9, 9, 9)
    private fun manifest(data: ByteArray = wgt, version: String = "1.3.0", extra: String = "\"signed\":false") =
        "{\"schema\":1,\"platform\":\"tizen\",\"id\":\"PCaption01.PlayCaptionTest\",\"version\":\"$version\",$extra," +
            "\"file\":\"PlayCaption-Samsung-$version-nepodpisany.wgt\",\"size\":${data.size}," +
            "\"sha256\":\"${MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }}\"}"

    @Test fun bundledPackageMustMatchManifest() {
        val b = SamsungBalicek.over(manifest(), wgt)
        assertEquals("1.3.0", b.verze)
        assertEquals("PlayCaption-Samsung-1.3.0-nepodpisany.wgt", b.soubor)
    }
    @Test fun changedOrForeignPackageIsRejected() {
        val changed = wgt.copyOf().also { it[7] = 1 }
        assertThrows(Exception::class.java) { SamsungBalicek.over(manifest(), changed) }
        assertThrows(Exception::class.java) { SamsungBalicek.over(manifest().replace("tizen", "webos"), wgt) }
        assertThrows(Exception::class.java) { SamsungBalicek.over(manifest(version = "1.3"), wgt) }
        // Podepsaný balíček platí jen pro TV z jeho certifikátu; nesmí se vydávat za univerzální.
        assertThrows(Exception::class.java) { SamsungBalicek.over(manifest(extra = "\"signed\":true"), wgt) }
        val notZip = "!<arch>\n".toByteArray()
        assertThrows(Exception::class.java) { SamsungBalicek.over(manifest(notZip), notZip) }
    }
    @Test fun receiverIdDecidesPlatform() {
        assertTrue(SamsungPrijimac.jeSamsung("samsung-0123456789ab"))
        for (id in listOf("lg-0123456789ab", "samsung-XYZ", "Samsung-0123456789ab", "samsung-0123456789abc", "")) assertFalse(SamsungPrijimac.jeSamsung(id))
    }
    @Test fun versionIsComparedNumericallyAndUnknownIsNeverCurrent() {
        assertEquals(SamsungStavVerze.AKTUALNI, SamsungPrijimac.stav("1.3.0", "1.3.0"))
        assertEquals(SamsungStavVerze.AKTUALIZACE, SamsungPrijimac.stav("1.2.9", "1.3.0"))
        assertEquals(SamsungStavVerze.AKTUALIZACE, SamsungPrijimac.stav("1.9.0", "1.10.0"))
        assertEquals(SamsungStavVerze.NOVEJSI, SamsungPrijimac.stav("1.10.0", "1.9.0"))
        assertEquals(SamsungStavVerze.NOVEJSI, SamsungPrijimac.stav("2.0.0", "1.3.0"))
        for (v in listOf(null, "", "1.3", "1.3.0-beta", "abc", "1.3.0.1")) assertEquals(SamsungStavVerze.NEHLASI, SamsungPrijimac.stav(v, "1.3.0"))
        assertThrows(Exception::class.java) { SamsungPrijimac.stav("1.3.0", "nesmysl") }
    }
    @Test fun hostPcIpIsHomeNetworkIpv4() {
        fun a(text: String) = InetAddress.getByName(text)
        assertEquals("192.168.1.20", SamsungPrijimac.adresaTelefonu(listOf(a("fe80::1"), a("127.0.0.1"), a("192.168.1.20"))))
        assertEquals("10.0.0.5", SamsungPrijimac.adresaTelefonu(listOf(a("10.0.0.5"))))
        assertNull(SamsungPrijimac.adresaTelefonu(listOf(a("8.8.8.8"), a("127.0.0.1"), a("fe80::1"))))
    }
    @Test fun guideStepsStayInRange() {
        assertEquals(0, SamsungPruvodceStav(-5).krok)
        assertEquals(SamsungPruvodceStav.POSLEDNI, SamsungPruvodceStav(99).krok)
        val s = SamsungPruvodceStav(0)
        assertFalse(s.zpet())
        repeat(SamsungPruvodceStav.POSLEDNI) { assertTrue(s.dalsi()) }
        assertFalse(s.dalsi())
        val skip = SamsungPruvodceStav(0); skip.prijimacMam()
        assertEquals(SamsungPruvodceStav.POSLEDNI, skip.krok)
    }
    @Test fun samsungIsDisplayOnlyLikeLg() {
        assertTrue(TvPlatforma.SAMSUNG.jenZobrazovac)
        assertTrue(TvPlatforma.WEBOS.jenZobrazovac)
        assertFalse(TvPlatforma.ANDROID_TV.jenZobrazovac)
        assertFalse(TvPlatforma.NEZNAMA.jenZobrazovac)
        // Uložené profily se čtou podle jména; původní pořadí hodnot se nesmí změnit.
        assertEquals(listOf("NEZNAMA", "ANDROID_TV", "WEBOS", "SAMSUNG"), TvPlatforma.values().map { it.name })
    }
}
