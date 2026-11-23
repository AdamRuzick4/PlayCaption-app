package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PribehovaRelaceTest {
    @Test fun kontextPatriPozadavkuNezavisleNaPoradiDokonceniAPoResetuZmizi() {
        val pozadavky = mutableListOf<Pair<List<String>, String>>()
        val hotovo = mutableListOf<(String) -> Unit>()
        val r = PribehovaRelace({ text, kontext, ok, _ -> pozadavky += text to kontext; hotovo += ok }, { fail(it) })
        r.prijmi(listOf("Arthur: Come here."), intArrayOf(), false, 0)
        r.prijmi(listOf("John: I don't knovv."), intArrayOf(), false, 100)
        r.prijmi(listOf("John: I don't know."), intArrayOf(), true, 200)
        assertEquals(listOf("", "Arthur: Come here.", "Arthur: Come here."), pozadavky.map { it.second })
        hotovo[2]("Johne, nevím."); hotovo[0]("Pojď sem."); hotovo[1]("Stará revize.")
        val first = r.krok(300, true, false)!!
        assertEquals("Pojď sem.", first.text); r.potvrzeno(first.token)
        assertEquals("Johne, nevím.", r.krok(400, true, false)!!.text)
        r.reset()
        r.prijmi(listOf("New scene."), intArrayOf(), false, 500)
        assertEquals("", pozadavky.last().second)
        hotovo[2]("Pozdní starý překlad.")
        assertNull(r.krok(600, true, false))
    }
    private class Scena {
        val hotovo = mutableListOf<(String) -> Unit>()
        val chyba = mutableListOf<() -> Unit>()
        val zpravy = mutableListOf<String>()
        val r = PribehovaRelace({ _, _, ok, err -> hotovo += ok; chyba += err }, { zpravy += it })
        fun nova(text: String, oprava: Boolean = false) = r.prijmi(listOf(text), intArrayOf(-65536), oprava, 0)
    }
    @Test fun skladaniJePredPreklademAVysledkyZustanouVPoradi() {
        val anglicky = mutableListOf<List<String>>()
        val preklady = mutableListOf<(String) -> Unit>()
        val r = PribehovaRelace({ en, _, ok, _ -> anglicky += en; preklady += ok }, { fail(it) })
        r.prijmiOcr(10, listOf("I'm drunk and you are such"), intArrayOf(), false, 0)
        assertTrue(anglicky.isEmpty()); assertNull(r.krok(500, true, false))
        r.prijmiOcr(11, listOf("a downer. I'm going home."), intArrayOf(), false, 1000)
        assertEquals(listOf(listOf("I'm drunk and you are such a downer."), listOf("I'm going home.")), anglicky)
        preklady[1]("Jdu domů."); assertNull(r.krok(1100, true, false))
        preklady[0]("Jsem opilý a ty jsi takový kazisvět.")
        val a = r.krok(1200, true, false)!!
        assertEquals("Jsem opilý a ty jsi takový kazisvět.", a.text)
        assertTrue(r.potvrzeno(a.token)); assertEquals("Jdu domů.", r.krok(1300, true, false)!!.text)
    }
    @Test fun revizeStareCteniAPozdniPrekladNevytvoriDalsiPolozku() {
        val anglicky = mutableListOf<List<String>>()
        val callback = mutableListOf<(String) -> Unit>()
        val r = PribehovaRelace({ en, _, ok, _ -> anglicky += en; callback += ok }, { fail(it) })
        r.prijmiOcr(10, listOf("We are such"), intArrayOf(), false, 0)
        r.prijmiOcr(11, listOf("We were such"), intArrayOf(), true, 100)
        r.prijmiOcr(9, listOf("Old unrelated frame."), intArrayOf(), false, 200)
        r.prijmiOcr(12, listOf("a nuisance."), intArrayOf(), false, 300)
        assertEquals(listOf(listOf("We were such a nuisance.")), anglicky)
        r.reset(); callback[0]("Stará odpověď.")
        assertNull(r.krok(400, true, false)); assertEquals(0, r.pocet)
    }
    @Test fun volbyUvolniZasobnikAPozastaviCteniNikolivPreklad() {
        val en = mutableListOf<List<String>>()
        val r = PribehovaRelace({ text, _, ok, _ -> en += text; ok("Česky.") }, { fail(it) })
        r.prijmiOcr(1, listOf("We are such"), intArrayOf(), false, 0)
        assertNull(r.krok(500, true, true)); assertEquals(listOf(listOf("We are such")), en)
        r.prijmiOcr(2, listOf("a nuisance."), intArrayOf(), false, 600)
        assertEquals(listOf(listOf("We are such"), listOf("a nuisance.")), en)
        assertNull(r.krok(700, true, true)); assertEquals(2, r.pocet)
        assertEquals("Česky.", r.krok(800, true, false)!!.text)
    }
    @Test fun viceMluvcichVJednomBlokuZachovaJmenaBarvyAObsah() {
        val anglicky = mutableListOf<List<String>>()
        val r = PribehovaRelace({ en, _, ok, _ -> anglicky += en; ok(en.single()) }, { fail(it) })
        r.prijmiOcr(1, listOf("Trevor: Hello.", "Michael: Bye."), intArrayOf(-65536, -16711936), false, 0)
        assertEquals(listOf(listOf("Trevor: Hello."), listOf("Michael: Bye.")), anglicky)
        val a = r.krok(0, true, false)!!; assertArrayEquals(intArrayOf(-65536), r.barvy(a.token))
        r.potvrzeno(a.token)
        val b = r.krok(1, true, false)!!; assertArrayEquals(intArrayOf(-16711936), r.barvy(b.token))
    }
    @Test fun priRostouciFronteNedrzetDalsiNedokoncenouVetu() {
        val en = mutableListOf<List<String>>()
        val r = PribehovaRelace({ text, _, _, _ -> en += text }, { fail(it) })
        for (n in 1L..3L) r.prijmiOcr(n, listOf("Whole sentence $n."), intArrayOf(), false, n)
        r.krok(4, true, false)
        r.prijmiOcr(4, listOf("We are such"), intArrayOf(), false, 5)
        assertEquals(listOf("We are such"), en.last()); assertEquals(4, en.size)
    }
    @Test fun starePrazdneOcrNesmiZkratitCekaniNovehoDialogu() {
        val en = mutableListOf<List<String>>()
        val r = PribehovaRelace({ text, _, _, _ -> en += text }, { fail(it) })
        r.prijmiOcr(10, listOf("We are such"), intArrayOf(), false, 0)
        r.zmizelo(9, 100); r.krok(1000, true, false)
        assertTrue(en.isEmpty()); r.krok(2500, true, false); assertEquals(1, en.size)
    }

    @Test fun jednoduchaOcrRevizeZachovaDosavadniOpravuPredZobrazenim() {
        val callbacks = mutableListOf<(String) -> Unit>()
        val r = PribehovaRelace({ _, _, ok, _ -> callbacks += ok }, { fail(it) })
        r.prijmiOcr(1, listOf("I don't knovv."), intArrayOf(), false, 0)
        r.prijmiOcr(2, listOf("I don't know."), intArrayOf(), true, 100)
        assertEquals(2, callbacks.size); assertEquals(1, r.pocet)
        callbacks[0]("Staré."); assertNull(r.krok(100, true, false))
        callbacks[1]("Nevím."); val p = r.krok(200, true, false)!!
        assertEquals("Nevím.", p.text)
        r.prijmiOcr(3, listOf("I don't know!"), intArrayOf(), true, 300)
        assertEquals(2, callbacks.size); assertEquals(p, r.krok(300, true, false))
    }

    @Test fun skutecnyAdapterCekaNaDokonceniLgIAckSeNeopakuje() {
        val s = Scena(); s.nova("A"); s.nova("B")
        s.hotovo[1]("Druhá.")
        assertNull(s.r.krok(0, true, false))
        s.hotovo[0]("První.")
        val a = s.r.krok(100, true, false)!!
        assertEquals("První.", a.text)
        assertEquals(a, s.r.krok(60_000, true, false))
        assertArrayEquals(intArrayOf(-65536), s.r.barvy(a.token))
        assertTrue(s.r.potvrzeno(a.token)); assertFalse(s.r.potvrzeno(a.token))
        assertEquals("Druhá.", s.r.krok(60_000, true, false)!!.text)
    }
    @Test fun opravenaHypotezaNeprejmePozdniStarouOdpoved() {
        val s = Scena(); s.nova("Old"); s.nova("New", true)
        s.hotovo[1]("Správně."); s.hotovo[0]("Stará chyba."); s.chyba[0]()
        assertEquals("Správně.", s.r.krok(0, true, false)!!.text)
        assertTrue(s.zpravy.isEmpty())
    }
    @Test fun poResetuNesmimeOzivitStarouRelaci() {
        val s = Scena(); s.nova("Old"); s.r.reset(); s.nova("New")
        s.hotovo[0]("Staré."); s.chyba[0]()
        assertNull(s.r.krok(0, true, false))
        s.hotovo[1]("Nové.")
        assertEquals("Nové.", s.r.krok(10, true, false)!!.text)
    }
    @Test fun rozectenaOpravaNevytvoriDalsiPrekladAniPolozku() {
        val s = Scena(); s.nova("A"); s.hotovo[0]("Čtu."); s.r.krok(0, true, false)
        s.nova("A revised", true)
        assertEquals(1, s.hotovo.size); assertEquals(1, s.r.pocet)
    }
    @Test fun chybaPrekladuNesmiZablokovatDalsiRepliku() {
        val s = Scena(); s.nova("A"); s.nova("B"); s.hotovo[1]("Pokračuj."); s.chyba[0]()
        assertEquals("Pokračuj.", s.r.krok(0, true, false)!!.text)
        assertEquals(1, s.zpravy.size)
    }
    @Test fun lokalniVystupPouzivaCasovaniFronty() {
        val s = Scena(); s.nova("A"); s.nova("B"); s.hotovo[0]("Ano."); s.hotovo[1]("Jdu.")
        assertEquals("Ano.", s.r.krok(0, false, false)!!.text)
        assertEquals("Jdu.", s.r.krok(1200, false, false)!!.text)
        assertNull(s.r.krok(2400, false, false))
    }
    @Test fun selhaniVystupuSeNepocitaJakoDokonceneCteni() {
        val f=CteciFronta();val t=f.zarad(0)!!;f.priprav(t,listOf("Připravená."));f.zobraz(0,true)
        assertTrue(f.chybaZobrazeni(t));assertFalse(f.potvrzeno(t))
        assertEquals(0,f.dokoncenych);assertEquals(1,f.selhanych);assertEquals(0,f.pocet)
    }
    @Test fun prazdnyPrekladJeViditelnaChybaNeAnglickyFallback() {
        val s = Scena(); s.nova("English"); s.hotovo[0](" ")
        assertNull(s.r.krok(0, true, false)); assertEquals(1, s.zpravy.size)
    }
    @Test fun chybyProtokoluANadmernyPaketJsouOdmitnute() {
        val id = "a".repeat(32) + ".1.2.3"
        assertEquals("done", PribehProtokol.odpoved("PCA1\t$id\tdone")!!.stav)
        for (p in listOf("PCA1\tx\tdone", "PCA1\t$id\tother", "PCA1\t$id\tdone\textra")) assertNull(PribehProtokol.odpoved(p))
        val packet = PribehProtokol.titulek(id, "První\nDruhý\\konec", false, floatArrayOf(.5f,.9f,.8f,1f), intArrayOf(-65536))
        assertTrue(packet.contains("První\\nDruhý\\\\konec"))
        try { PribehProtokol.titulek(id, "ž".repeat(5000), false, floatArrayOf(.5f,.9f,.8f,1f), intArrayOf()); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test(timeout = 4000) fun obousmernyUdpSocketPrijmePouzeOdpovedNaAktualniId() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { tv ->
            tv.soTimeout = 2000
            val id = "b".repeat(32) + ".1.0.0"
            PribehSpojeni("127.0.0.1", tv.localPort, { 100L }).use { spojeni ->
                spojeni.zprava = PribehProtokol.dotaz(id)
                val packet = DatagramPacket(ByteArray(1024), 1024); tv.receive(packet)
                assertEquals("PCQ1\t$id", String(packet.data,0,packet.length,Charsets.UTF_8))
                for (reply in listOf("PCA1\t" + "c".repeat(32) + ".1.0.0\tdone", "PCA1\t$id\tready")) {
                    val data = reply.toByteArray(); tv.send(DatagramPacket(data,data.size,packet.address,packet.port))
                }
                val deadline = System.nanoTime() + 2_000_000_000
                while (spojeni.odpoved == null && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(PribehProtokol.Odpoved(id,"ready") to 100L, spojeni.odpoved)
            }
        }
    }
}
