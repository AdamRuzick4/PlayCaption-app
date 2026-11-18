package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class StoryKontextTest {
    @Test fun aktualniReplikaAniJejiRevizeNeniVlastnimKontextem() {
        val k = KontextPrekladu()
        assertEquals("", k.prijmi(1, "Arthur: Come here.", 0))
        assertEquals("Arthur: Come here.", k.prijmi(2, "John: I don't knovv.", 500))
        assertEquals("Arthur: Come here.", k.prijmi(2, "John: I don't know.", 600))
        assertEquals("Arthur: Come here.\nJohn: I don't know.", k.prijmi(3, "Fine.", 1000))
    }
    @Test fun historieMaLimitPauzuAReset() {
        val k = KontextPrekladu()
        repeat(5) { k.prijmi(it.toLong(), "Řádek $it.", it.toLong()) }
        assertEquals("Řádek 2.\nŘádek 3.\nŘádek 4.", k.prijmi(6, "Nový.", 100))
        assertEquals("", k.prijmi(7, "Po pauze.", 20101))
        k.reset(); assertEquals("", k.prijmi(1, "Nová relace.", 0))
        k.prijmi(2, "x".repeat(1601), 1)
        assertEquals("Nová relace.", k.prijmi(3, "Další.", 2))
    }
    @Test fun stejnyTextSeNesdiliMeziKontextyJazykyAMotory() {
        val m = PametPribehu(2)
        val k = PametPribehu.Klic(MotorPribehu.DEEPL, "cs", "Fine.", "Weather.")
        m.uloz(k, "Pěkné.")
        assertEquals("Pěkné.", m.najdi(k))
        assertNull(m.najdi(k.copy(kontext = "Punishment.")))
        assertNull(m.najdi(k.copy(jazyk = "sk")))
        assertNull(m.najdi(k.copy(motor = MotorPribehu.AUTO)))
        m.uloz(k.copy(text = "2"), "Druhý."); m.najdi(k)
        m.uloz(k.copy(text = "3"), "Třetí.")
        assertNull(m.najdi(k.copy(text = "2")))
        assertEquals("Pěkné.", m.najdi(k))
        m.reset(); assertNull(m.najdi(k))
    }
    @Test fun neznameNastaveniZachovaAutomatickyPreklad() { assertEquals(MotorPribehu.AUTO, MotorPribehu.zId("unknown")) }
    @Test fun staryVysledekPoZmeneNastaveniNedoplniNovouPamet() {
        val m = PametPribehu()
        val k = PametPribehu.Klic(MotorPribehu.DEEPL, "cs", "Fine.", "Punishment.")
        val stara = m.generace
        m.reset(); m.uloz(k, "Starý účet.", stara)
        assertNull(m.najdi(k))
        m.uloz(k, "Nový účet.", m.generace)
        assertEquals("Nový účet.", m.najdi(k))
    }
    @Test fun volbaStoryPlatiIVDobecasnehoStandardnihoPrenosuAPuvodniStandardZustava() {
        for (motor in MotorPribehu.values()) {
            assertTrue(motor.zahrivatGoogle(false))
            assertFalse(motor.pouzitProDialog(false, false, false))
            assertFalse(motor.pouzitProDialog(false, true, true))
            assertTrue(motor.pouzitProDialog(true, true, false))
        }
        assertFalse(MotorPribehu.AUTO.pouzitProDialog(false, true, false))
        assertTrue(MotorPribehu.OFFLINE.pouzitProDialog(false, true, false))
        assertTrue(MotorPribehu.DEEPL.pouzitProDialog(false, true, false))
        assertTrue(MotorPribehu.AUTO.zahrivatGoogle(true))
        assertFalse(MotorPribehu.OFFLINE.zahrivatGoogle(true))
        assertFalse(MotorPribehu.DEEPL.zahrivatGoogle(true))
    }
}
