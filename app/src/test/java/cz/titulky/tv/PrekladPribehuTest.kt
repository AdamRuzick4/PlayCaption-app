package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class PrekladPribehuTest {
    private class Sim(val obsazeno: Boolean = false, offlineRezim: Boolean = false) {
        var online: (String?) -> Unit = {}
        var offline: (String) -> Unit = {}
        var chybaOffline: () -> Unit = {}
        var zalohy = 0
        var onlinePokusu = 0
        var chyby = 0
        val vysledky = mutableListOf<String>()
        private val ulohy = mutableListOf<Pair<Long, () -> Unit>>()
        val preklad = PrekladPribehu({ _, callback -> onlinePokusu++; online = callback; !obsazeno }, { _, ok, err ->
            zalohy++; offline = ok; chybaOffline = err
        }, { ms, callback -> ulohy += ms to callback }, onlinePovoleno = !offlineRezim)
        init { preklad.preloz("English", { vysledky += it }, { chyby++ }) }
        fun doCas(ms: Long) {
            while (ulohy.any { it.first <= ms }) {
                val i = ulohy.indices.minBy { ulohy[it].first }
                ulohy.removeAt(i).second()
            }
        }
    }
    @Test fun rychlyOnlineJeJedinyFinalniVysledek() {
        val s=Sim(); s.online("Český."); s.doCas(10000)
        assertEquals(listOf("Český."),s.vysledky);assertEquals(0,s.zalohy);assertEquals(0,s.chyby)
    }
    @Test fun offlineRezimNikdyNespustiOnlinePrekladAniPoVyprseniCasu() {
        val s = Sim(offlineRezim = true)
        assertEquals(0, s.onlinePokusu); assertEquals(1, s.zalohy)
        s.offline("Místní překlad."); s.doCas(10000)
        assertEquals(listOf("Místní překlad."), s.vysledky)
        assertEquals(0, s.onlinePokusu); assertEquals(0, s.chyby)
    }
    @Test fun obsazenaSitPouzijeOfflineBezDalsiSitoveFronty() {
        val s=Sim(true);assertEquals(1,s.zalohy);s.offline("Záloha.");s.doCas(10000)
        assertEquals(listOf("Záloha."),s.vysledky);assertEquals(0,s.chyby)
    }
    @Test fun pozdniOnlineNeprepiseDokoncenyOffline() {
        val s=Sim();s.doCas(2500);s.offline("Záloha.");s.online("Jiný překlad.");s.doCas(10000)
        assertEquals(listOf("Záloha."),s.vysledky);assertEquals(1,s.zalohy)
    }
    @Test fun selhaniOnlineNespustiOfflineDvakrat() {
        val s=Sim();s.online(null);s.doCas(2500);assertEquals(1,s.zalohy)
        s.offline("Česky.");assertEquals(listOf("Česky."),s.vysledky)
    }
    @Test fun offlineChybaNezahodiJestePrichazejiciOnlineVysledek() {
        val s=Sim();s.doCas(2500);s.chybaOffline();assertEquals(0,s.chyby)
        s.online("Platný.");assertEquals(listOf("Platný."),s.vysledky)
    }
    @Test fun obeChybyVratiPraveJednuChybu() {
        val s=Sim();s.online(null);s.chybaOffline();s.doCas(10000)
        assertEquals(1,s.chyby);assertTrue(s.vysledky.isEmpty())
    }
    @Test fun nedokoncenePozadavkyMajiPevnyRozpocet() {
        val s=Sim();s.doCas(7999);assertEquals(0,s.chyby)
        s.doCas(8000);s.online("Pozdě.");s.offline("Také pozdě.")
        assertEquals(1,s.chyby);assertTrue(s.vysledky.isEmpty())
    }
}
