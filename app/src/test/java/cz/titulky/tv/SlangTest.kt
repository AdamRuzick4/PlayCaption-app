package cz.titulky.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Převod floridského slangu (GTA 6) na běžnou angličtinu před překladem. */
class SlangTest {
    @Test fun slangSePrevede() {
        assertEquals("you all going to get freaking out with the police",
            Prekladac.upravSlang("y'all finna get trippin' with the po-po"))
        assertEquals("I'm going to call my buddy", Prekladac.upravSlang("Imma call my homie"))
    }

    @Test fun bezneVetyZustanou() {
        for (veta in listOf("I bet the boutique is open", "We rely on God and on each other.",
                "Have mercy on God's children.", "There's no cap on how much we can take.",
                "Ima is my cousin.", "Opps, I dropped it.", "Those planes might crash out at sea.",
                "Whatcha got for me?", "Lil Wayne is playing.", "The ion engine is down."))
            assertEquals(veta, Prekladac.upravSlang(veta))
    }
}
