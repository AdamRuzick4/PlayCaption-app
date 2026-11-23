package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class GtaProfilPribehuTest {
    private fun preloz(text: String, mluvci: String? = null, kontext: String = "") =
        GtaProfilPribehu.preloz(text, mluvci, kontext)

    @Test fun jenZapnutyProfilVCeskemStory() {
        assertTrue(GtaProfilPribehu.aktivni(pribeh = true, zapnuto = true, cil = "cs"))
        assertFalse(GtaProfilPribehu.aktivni(pribeh = false, zapnuto = true, cil = "cs"))
        assertFalse(GtaProfilPribehu.aktivni(pribeh = true, zapnuto = false, cil = "cs"))
        assertFalse(GtaProfilPribehu.aktivni(pribeh = true, zapnuto = true, cil = "sk"))
    }

    @Test fun slangPatriJenCeleReplice() {
        assertEquals("Má u sebe bouchačku.", preloz("HE’S  PACKING HEAT."))
        assertEquals("Kurva jo!", preloz("Hell yeah!"))
        assertEquals("Padáme!", preloz("Let's bounce!"))
        assertNull(preloz("She's not packing heat."))
        assertNull(preloz("He's packing heat?"))
        assertNull(preloz("Let's bounce the ball."))
        assertNull(preloz("Tell me more."))
    }

    @Test fun rodUrciJenJmenovkaAktualniRepliky() {
        val historie = "Lucia: I'm ready."
        assertEquals("Byla jsem tam.", preloz("I was there.", "Lucia Caminos"))
        assertEquals("Byl jsem tam.", preloz("I was there.", "JASON", historie))
        assertNull(preloz("I was there.", kontext = historie))
        assertNull(preloz("I was there.", "Unknown", historie))
        assertNull(preloz("Lucia, I was there."))
    }

    @Test fun priChybejicimNeboNejasnemVoditkuRozhodnePrekladac() {
        for (pred in listOf("", "Did you shift gears?", "Only ten minutes left on our shift.")) {
            assertNull(preloz("We're on the clock.", kontext = pred))
        }
        assertNull(preloz("We're not on the clock.", kontext = "My shift starts now."))
    }

    @Test fun priSplaceniDluhuAZraneniMaStejnaOdpovedJinyVyznam() {
        val text = "We’re good."
        assertEquals("Jsme vyrovnaní.", preloz(text, kontext = "Do I still owe you?"))
        assertEquals("Jsme v pořádku.", preloz(text, kontext = "Is anyone hurt?"))
        assertNull(preloz(text, kontext = "What about dinner?"))
    }

    @Test fun stejnaVetaPoZmeneScenyDostaneJinyVyznam() {
        val vysledky = mutableListOf<String?>()
        val relace = PribehovaRelace({ repliky, historie, hotovo, _ ->
            val text = repliky.single()
            val cesky = preloz(text, kontext = historie)
            if (text == "We're on the clock.") vysledky += cesky
            hotovo(cesky ?: text)
        }, { fail(it) })
        fun ocr(id: Long, text: String, cas: Long) =
            relace.prijmiOcr(id, listOf(text), intArrayOf(0), false, cas)

        ocr(1, "My shift starts now.", 0)
        ocr(2, "We're on the clock.", 500)
        ocr(3, "Only ten seconds left!", 1000)
        ocr(4, "We're on the clock.", 1500)
        ocr(5, "We're on the clock.", 25000)
        relace.reset()
        ocr(1, "We're on the clock.", 0)
        assertEquals(listOf("Máme směnu.", "Tlačí nás čas.", null, null), vysledky)
    }
}
