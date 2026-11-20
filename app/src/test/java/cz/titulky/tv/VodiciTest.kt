package cz.titulky.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Přichycení rámečků na střed. */
class VodiciTest {
    @Test fun blizkoStreduSePrichyti() {
        assertEquals(0.5f, Vodici.prichyt(0.48f))
        assertEquals(0.5f, Vodici.prichyt(0.524f))
        assertEquals(0.45f, Vodici.prichyt(0.45f))
        assertEquals(0.6f, Vodici.prichyt(0.6f))
    }

    @Test fun ramecekSeVycentrujeIPriZaokrouhleni() {
        val sirka = 0.37f
        val levy = Vodici.prichyt(0.31f + sirka / 2) - sirka / 2
        assertTrue(Vodici.naStredu(levy + sirka / 2))
        assertFalse(Vodici.naStredu(0.47f))
    }
}
