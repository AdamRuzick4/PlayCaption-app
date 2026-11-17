package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class TelefonTtsUdalostTest {
    @Test fun storySpeechEventKeepsDisplayAndCarriesIdentityAndTimestamp() {
        val oldClock = TelefonTitulky.ttsCas
        val oldListener = TelefonTitulky.onTtsEvent
        val display = TelefonTitulky.text
        val version = TelefonTitulky.verzeTtsUdalosti
        var notified = 0
        try {
            TelefonTitulky.ttsCas = { 12345L }
            TelefonTitulky.onTtsEvent = { notified++ }
            TelefonTitulky.nastavPouzeProTts("Vybraná stránka", "pribeh:session.2#1", 17L)
            assertEquals(display, TelefonTitulky.text)
            assertEquals("Vybraná stránka", TelefonTitulky.textProTts)
            assertEquals("pribeh:session.2#1", TelefonTitulky.vstupOcr)
            assertEquals(12345L, TelefonTitulky.casTtsUdalostiMs)
            assertEquals(version + 1, TelefonTitulky.verzeTtsUdalosti)
            assertTrue(TelefonTitulky.pripravenoProTts)
            assertFalse(TelefonTitulky.deduplikovatTts)
            assertEquals(1, notified)
            TelefonTitulky.nastavPouzeProTts("", "", 18L, pripraveno = false)
            assertFalse(TelefonTitulky.pripravenoProTts)
            assertEquals(2, notified)
        } finally {
            TelefonTitulky.onTtsEvent = null
            TelefonTitulky.nastavPouzeProTts("", "", 0L, pripraveno = false)
            TelefonTitulky.ttsCas = oldClock
            TelefonTitulky.onTtsEvent = oldListener
        }
    }
}
