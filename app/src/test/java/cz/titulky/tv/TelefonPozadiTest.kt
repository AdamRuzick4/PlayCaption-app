package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class TelefonPozadiTest {
    @Test fun selectedLgKeepsActiveSenderAfterScreenOff() {
        assertTrue(TelefonPozadi.pokracovatNaLg(true, "lg-001122334455", TvPlatforma.WEBOS))
    }

    @Test fun androidTvLocalSamsungAndUnknownKeepOriginalStopPolicy() {
        for (platforma in listOf(TvPlatforma.ANDROID_TV, TvPlatforma.SAMSUNG, TvPlatforma.NEZNAMA, null)) {
            assertFalse(TelefonPozadi.pokracovatNaLg(true, "lg-001122334455", platforma))
        }
    }

    @Test fun stoppingOrRemovingLgOutputReleasesBackgroundExemption() {
        assertTrue(TelefonPozadi.pokracovatNaLg(true, "lg-001122334455", TvPlatforma.WEBOS))
        assertFalse(TelefonPozadi.pokracovatNaLg(false, "lg-001122334455", TvPlatforma.WEBOS))
        assertFalse(TelefonPozadi.pokracovatNaLg(true, null, TvPlatforma.WEBOS))
        assertFalse(TelefonPozadi.pokracovatNaLg(true, "", TvPlatforma.WEBOS))
    }
}
