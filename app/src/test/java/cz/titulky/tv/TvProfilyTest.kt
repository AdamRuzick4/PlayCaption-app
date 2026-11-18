package cz.titulky.tv

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class TvProfilyTest {
    @Test fun lgCannotEnterAndroidCredentialHandoffEvenWithUnknownPlatform() {
        val p = store()
        val lg = tv("lg-001122334455")
        assertFalse(p.muzePrevzitSparovani(lg))
        assertEquals(TvPlatforma.WEBOS, p.platformaPrijimace(lg.id))
        assertTrue(p.muzePrevzitSparovani(tv("legacy-android")))
        p.zapamatuj("android", "Android TV", "10.0.0.2", TvPlatforma.ANDROID_TV)
        assertTrue(p.muzePrevzitSparovani(tv("android")))
        p.zapamatuj(lg.id, lg.jmeno, lg.ip)
        assertEquals(TvPlatforma.WEBOS, p.najdi(lg.id)!!.platforma)
        assertTrue(p.muzePrevzitSparovani(tv("legacy-android"))) // DHCP mohl starou LG IP přidělit Android TV.
        assertFalse(p.muzePrevzitSparovani(Propojeni.Televize("rucne", "TV", lg.ip)))
    }
    @Test fun samsungCannotEnterAndroidCredentialHandoff() {
        val p = store()
        val samsung = tv("samsung-001122334455")
        assertEquals(TvPlatforma.SAMSUNG, p.platformaPrijimace(samsung.id))
        assertFalse(p.muzePrevzitSparovani(samsung))
        p.zapamatuj(samsung.id, samsung.jmeno, samsung.ip)
        assertEquals(TvPlatforma.SAMSUNG, p.najdi(samsung.id)!!.platforma)
        assertFalse(p.muzePrevzitSparovani(Propojeni.Televize("rucne", "TV", samsung.ip)))
    }
    private val files = mutableMapOf<String, SharedPreferences>()
    private val legacy = memory()
    private val metadata = memory()
    private fun store() = TvProfily(metadata, legacy) { files.getOrPut(it) { memory() } }
    private fun tv(id: String, ip: String = "127.0.0.1") = Propojeni.Televize(id, "TV $id", ip)

    @Test fun twoTvsKeepSeparateLayoutAndFramesAfterRestart() {
        val p = store()
        p.zapamatuj("A", "Obývák", "10.0.0.1", TvPlatforma.WEBOS)
        p.vyber("A")
        p.nastaveni("A").edit().putFloat("tvX", .3f).putBoolean("titRucne", true).apply()
        p.zapamatuj("B", "Ložnice", "10.0.0.2", TvPlatforma.WEBOS)
        p.vyber("B")
        assertFalse(p.nastaveni("B").contains("tvX"))
        p.nastaveni("B").edit().putFloat("tvX", .8f).apply()
        val restarted = store()
        restarted.vyber("A")
        assertEquals(.3f, restarted.nastaveni("A").getFloat("tvX", 0f))
        assertTrue(restarted.nastaveni("A").getBoolean("titRucne", false))
        assertEquals(.8f, restarted.nastaveni("B").getFloat("tvX", 0f))
        assertEquals(2, restarted.seznam().size)
        TvProfily.lokalne()
        assertNull(TvProfily.aktivniId)
        assertEquals("A", restarted.posledniId)
    }

    @Test fun legacySettingsMigrateOnlyToPreviousIdAndDoNotGuessPlatform() {
        legacy.edit().putString("tvId", "A").putFloat("tvX", .4f).putString("jazyk", "cs").putString("psnToken", "private").apply()
        val p = store()
        p.zapamatuj("B", "LG jiná", "10.0.0.2")
        assertFalse(p.nastaveni("B").contains("tvX"))
        p.zapamatuj("A", "LG PlayCaption", "10.0.0.1")
        assertEquals(TvPlatforma.NEZNAMA, p.najdi("A")!!.platforma)
        assertEquals(.4f, p.nastaveni("A").getFloat("tvX", 0f))
        assertFalse(p.nastaveni("A").contains("jazyk"))
        assertFalse(p.nastaveni("A").contains("psnToken"))
        p.nastaveni("A").edit().putFloat("tvX", .7f).apply()
        p.zapamatuj("A", "Obývák", "10.0.0.8", TvPlatforma.WEBOS)
        assertEquals(.7f, p.nastaveni("A").getFloat("tvX", 0f))
        assertEquals("cs", legacy.getString("jazyk", null))
        assertEquals(.4f, legacy.getFloat("tvX", 0f))
    }

    @Test fun dhcpMovesSubtitlesOnlyToTheSelectedLgEvenWhenAnotherLgTakesItsAddress() {
        val profily = store()
        val vysilac = Propojeni.Vysilac
        val drive = tv("lg-001122334455", "127.0.0.2").copy(jmeno = "Obývák")
        val jina = drive.copy(id = "lg-ffeeddccbbaa")
        val stejna = drive.copy(ip = "127.0.0.1")
        val jinyCil = jina.copy(ip = "127.0.0.3")
        for (obrazovka in listOf(drive, jinyCil)) profily.zapamatuj(obrazovka.id, obrazovka.jmeno, obrazovka.ip)
        profily.vyber(drive.id)
        try {
            vysilac.nastavCil(drive.ip, drive.jmeno)
            assertFalse(profily.obnovAdresuLg(drive.id, drive.ip, listOf(jinyCil)))
            assertEquals(drive.ip, vysilac.cilIp)
            assertTrue(profily.obnovAdresuLg(drive.id, drive.ip, listOf(jina)))
            assertNull(vysilac.cilIp)
            vysilac.zmena("Titulek pro původní LG", 6000, "")
            assertFalse(profily.obnovAdresuLg(drive.id, null, listOf(jina)))
            assertFalse(profily.obnovAdresuLg(drive.id, null, listOf(jina, stejna.copy(ip = jina.ip))))
            DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName(stejna.ip)).use { tv ->
                tv.soTimeout = 2000
                assertTrue(profily.obnovAdresuLg(drive.id, null, listOf(jina, stejna)))
                val packet = DatagramPacket(ByteArray(8192), 8192)
                tv.receive(packet)
                assertEquals("Titulek pro původní LG", String(packet.data, 0, packet.length, Charsets.UTF_8).split('\t')[3])
            }
            assertEquals(stejna.ip, vysilac.cilIp)
            assertEquals(stejna.ip, profily.najdi(drive.id)?.ip)
            assertEquals(drive.id, profily.posledniId)
            assertEquals(jinyCil.ip, profily.najdi(jina.id)?.ip)
            assertFalse(profily.obnovAdresuLg(drive.id, drive.ip, listOf(drive))) // opožděný výsledek
            profily.vyber(jina.id)
            assertFalse(profily.obnovAdresuLg(drive.id, stejna.ip, listOf(drive)))
        } finally {
            vysilac.nastavCil(null, "")
            TvProfily.lokalne()
        }
    }

    @Test fun newAddressOfSelectedLgWinsOverFirstDiscoveryResultWithoutDisconnecting() {
        val p = store()
        val puvodni = tv("lg-aabbccddeeff", "127.0.0.1")
        val presunuta = puvodni.copy(ip = "127.0.0.2")
        val jina = puvodni.copy(id = "lg-001122334455")
        p.zapamatuj(puvodni.id, puvodni.jmeno, puvodni.ip)
        p.vyber(puvodni.id)
        try {
            Propojeni.Vysilac.nastavCil(puvodni.ip, puvodni.jmeno)
            assertTrue(p.obnovAdresuLg(puvodni.id, puvodni.ip, listOf(jina, presunuta)))
            assertEquals(presunuta.ip, Propojeni.Vysilac.cilIp)
            // Na původní adrese nyní naslouchá druhá LG. Nesmí dostat ani opakovaný titulek.
            DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName(jina.ip)).use { druhaTv ->
                druhaTv.soTimeout = 1200
                Propojeni.Vysilac.zmena("Jen na přesunutou LG", 6000, "")
                try {
                    druhaTv.receive(DatagramPacket(ByteArray(8192), 8192))
                    fail("Telefon po přesměrování stále posílá na původní adresu.")
                } catch (_: SocketTimeoutException) { /* očekávané ticho */ }
            }
            assertFalse(p.obnovAdresuLg(puvodni.id, presunuta.ip, listOf(jina, presunuta)))
            TvProfily.lokalne()
            Propojeni.Vysilac.nastavCil(null, "")
            assertFalse(p.obnovAdresuLg(puvodni.id, presunuta.ip, listOf(puvodni)))
            assertNull(Propojeni.Vysilac.cilIp)
        } finally {
            Propojeni.Vysilac.nastavCil(null, "")
            TvProfily.lokalne()
        }
    }

    @Test fun changedAddressKeepsIdPlatformAndSettings() {
        val p = store()
        p.zapamatuj("A", "Obývák", "10.0.0.1", TvPlatforma.WEBOS)
        p.nastaveni("A").edit().putFloat("tvSirka", .9f).apply()
        p.zapamatuj("A", "Nové jméno", "10.0.0.9")
        assertEquals(1, p.seznam().size)
        assertEquals(TvPlatforma.WEBOS, p.najdi("A")!!.platforma)
        assertEquals("10.0.0.9", p.najdi("A")!!.ip)
        assertEquals(.9f, p.nastaveni("A").getFloat("tvSirka", 0f))
    }

    @Test fun noAutomaticRedirectToDifferentTvOrUnknownPlatform() {
        val p = store()
        assertEquals("A", p.automaticky(listOf(tv("A")))?.id)
        assertNull(p.automaticky(listOf(tv("A"), tv("B"))))
        p.zapamatuj("A", "A", "10.0.0.1", TvPlatforma.WEBOS)
        p.vyber("A")
        assertNull(p.automaticky(listOf(tv("B"))))
        p.platforma = TvPlatforma.ANDROID_TV
        assertNull(p.automaticky(listOf(tv("A"))))
        p.platforma = TvPlatforma.WEBOS
        assertEquals("10.0.0.8", p.automaticky(listOf(tv("A", "10.0.0.8")))?.ip)
        p.pouzeTelefon = true
        assertNull(store().automaticky(listOf(tv("A"))))
        p.zapamatujVyber("A")
        assertFalse(p.pouzeTelefon)
        TvProfily.lokalne()
    }

    @Test fun legacyIdDoesNotFallBackToOnlyOtherTv() {
        legacy.edit().putString("tvId", "A").apply()
        assertNull(store().automaticky(listOf(tv("B"))))
        assertEquals("A", store().automaticky(listOf(tv("A")))?.id)
    }

    /** Malý paměťový SharedPreferences, bez Android runtime a nové testovací knihovny. */
    private fun memory(): SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        lateinit var prefs: SharedPreferences
        lateinit var editor: SharedPreferences.Editor
        val pending = mutableMapOf<String, Any?>()
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, a ->
            when (m.name) {
                "apply", "commit" -> { pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }; pending.clear(); if (m.name == "commit") true else null }
                "remove" -> { pending[a!![0] as String] = null; editor }
                else -> { pending[a!![0] as String] = a[1]; editor }
            }
        } as SharedPreferences.Editor
        prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            when (m.name) {
                "edit" -> editor
                "getAll" -> data.toMap()
                "contains" -> data.containsKey(a!![0])
                else -> data[a!![0]] ?: a[1]
            }
        } as SharedPreferences
        return prefs
    }
}
