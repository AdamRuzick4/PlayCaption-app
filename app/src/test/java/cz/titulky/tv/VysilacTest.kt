package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class VysilacTest {
    private val puvodniTtsCas = TelefonTitulky.ttsCas
    @org.junit.Before fun nastavTtsCas() { TelefonTitulky.ttsCas = { System.nanoTime() / 1_000_000 } }
    @org.junit.After fun obnovTtsCas() { TelefonTitulky.ttsCas = puvodniTtsCas }

    @Test fun prvniStoryVymazeStaryStandardAleZachovaVolbyANapovedu() {
        DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName("127.0.0.1")).use { tv ->
            tv.soTimeout = 100
            fun nacti(ms: Long): List<String> {
                val pakety = mutableListOf<String>()
                val doKdy = System.nanoTime() + ms * 1_000_000
                while (System.nanoTime() < doKdy) {
                    val p = DatagramPacket(ByteArray(8192), 8192)
                    try { tv.receive(p); pakety += String(p.data, 0, p.length, Charsets.UTF_8) }
                    catch (_: SocketTimeoutException) {}
                }
                return pakety
            }
            try {
                Propojeni.Vysilac.pribehRidi = false
                Propojeni.Vysilac.nastavCil("127.0.0.1", "Story TV")
                TelefonTitulky.napoveda = "Aktuální nápověda"
                TelefonTitulky.nastav("Původní Standard", 6000)
                assertTrue(nacti(200).any { "Původní Standard" in it })
                Propojeni.Vysilac.pribehRidi = true
                TelefonTitulky.nastav("", 0)
                Propojeni.Vysilac.nastavVolby(listOf("Volba během Story"), 0)
                val story = nacti(1200)
                assertFalse(story.any { it.startsWith("PCT1\t") })
                assertTrue(story.any { it.startsWith("PCV1\t") && "Volba během Story" in it })
                Propojeni.Vysilac.pribehRidi = false
                val standard = nacti(1200)
                assertTrue(standard.any { it.startsWith("PCT1\t") && "Aktuální nápověda" in it })
                assertFalse(standard.any { "Původní Standard" in it })
            } finally {
                Propojeni.Vysilac.pribehRidi = false
                Propojeni.Vysilac.nastavCil(null, "")
                TelefonTitulky.napoveda = ""
                TelefonTitulky.nastav("", 0)
            }
        }
    }

    @Test fun storyPozadovaneProAndroidTvOdeslePct1IVolby() {
        DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName("127.0.0.1")).use { tv ->
            tv.soTimeout = 100
            try {
                Propojeni.Vysilac.nastavCil("127.0.0.1", "Android TV")
                val pozadovanyPribeh = true
                Propojeni.Vysilac.pribehRidi = pozadovanyPribeh &&
                    PodporaPribehu.pouzit("127.0.0.1", TvPlatforma.ANDROID_TV, null)
                Propojeni.Vysilac.zmena("Celý český titulek.", 6000, "")
                Propojeni.Vysilac.nastavVolby(listOf("První odpověď"), 0)
                val pakety = mutableListOf<String>()
                val doKdy = System.nanoTime() + 1_500_000_000L
                while (System.nanoTime() < doKdy) {
                    val p = DatagramPacket(ByteArray(8192), 8192)
                    try { tv.receive(p); pakety += String(p.data, 0, p.length, Charsets.UTF_8) }
                    catch (_: SocketTimeoutException) {}
                }
                assertTrue("Story nesmí vypnout standardní výstup na Android TV", pakety.any {
                    it.startsWith("PCT1\t") && it.split('\t')[3] == "Celý český titulek."
                })
                assertTrue(pakety.any { it.startsWith("PCV1\t") && "První odpověď" in it })
                assertFalse(pakety.any { it.startsWith("PCS1\t") })
            } finally {
                Propojeni.Vysilac.pribehRidi = false
                Propojeni.Vysilac.nastavCil(null, "")
            }
        }
    }

    @Test fun storySuppressesOnlyStandardSubtitlesAndRestoresThemAfterSwitch() {
        DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName("127.0.0.1")).use { tv ->
            tv.soTimeout = 100
            fun receive(ms: Long): List<String> {
                val packets = mutableListOf<String>()
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try { tv.receive(packet); packets.add(String(packet.data, 0, packet.length, Charsets.UTF_8)) }
                    catch (_: SocketTimeoutException) {}
                }
                return packets
            }
            try {
                Propojeni.Vysilac.pribehRidi = true
                Propojeni.Vysilac.nastavCil("127.0.0.1", "Story")
                Propojeni.Vysilac.zmena("Standard čeká", 6000, "")
                Propojeni.Vysilac.nastavVolby(listOf("Volba během Story"), 0)
                val story = receive(1300)
                assertFalse(story.any { it.startsWith("PCT1") })
                assertTrue(story.any { it.startsWith("PCV1") && "Volba během Story" in it })
                Propojeni.Vysilac.pribehRidi = false
                assertTrue(receive(1300).any { it.startsWith("PCT1") && "Standard čeká" in it })
            } finally {
                Propojeni.Vysilac.pribehRidi = false
                Propojeni.Vysilac.nastavCil(null, "")
            }
        }
    }

    @Test fun switchingClearsOldReceiverAndNeverReplaysOldTextOnNewReceiver() {
        val a = DatagramSocket(Propojeni.PORT_TITULKY, InetAddress.getByName("127.0.0.1"))
        // Dva cíle na jednom loopbacku fungují i na macOS bez dalšího síťového aliasu.
        val b = a
        a.soTimeout = 100
        b.soTimeout = 100
        fun read(s: DatagramSocket, ms: Long): List<String> {
            val out = mutableListOf<String>()
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                val p = DatagramPacket(ByteArray(8192), 8192)
                try { s.receive(p); out.add(String(p.data, 0, p.length, Charsets.UTF_8)) }
                catch (_: SocketTimeoutException) {}
            }
            return out
        }
        try {
            Propojeni.Vysilac.nastavCil("127.0.0.1", "A")
            Propojeni.Vysilac.zmena("Jen na A", 6000, "Stará nápověda")
            Propojeni.Vysilac.nastavVolby(listOf("Staré volby"), 0)
            assertTrue(read(a, 200).any { "Jen na A" in it })
            Propojeni.Vysilac.nastavCil("localhost", "B")
            val cleared = read(a, 300)
            assertTrue(cleared.any { it.startsWith("PCT1") && it.split("\t")[3].isEmpty() })
            assertTrue(cleared.any { it.startsWith("PCV1") && it.split("\t")[8].isEmpty() })
            assertTrue(cleared.any { it.startsWith("PCO1\t0") })
            Propojeni.Vysilac.zmena("Jen na B", 6000, "")
            val new = read(b, 1300)
            assertTrue(new.any { "Jen na B" in it })
            assertFalse(new.any { "Jen na A" in it || "Stará nápověda" in it || "Staré volby" in it })
            assertTrue(read(a, 200).isEmpty())
            Propojeni.Vysilac.nastavCil(null, "")
            assertTrue(read(b, 300).any { it.startsWith("PCT1") && it.split("\t")[3].isEmpty() })
            assertTrue(read(b, 1200).isEmpty())
        } finally {
            Propojeni.Vysilac.nastavCil(null, "")
            a.close()
        }
    }
}
