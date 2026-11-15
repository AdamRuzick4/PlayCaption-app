package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class BraviaPolicyTest {
    @Test fun twoGiBTvDoesNotNeedVendorLowRamFlag() {
        assertTrue(BraviaPolicy.needsConservativeProfile(2L * 1024 * 1024 * 1024, false))
    }
    @Test fun lowRamFlagOverridesLargerReportedMemory() {
        assertTrue(BraviaPolicy.needsConservativeProfile(4L * 1024 * 1024 * 1024, true))
    }
    @Test fun normalFourGiBDeviceKeepsNormalProfile() {
        assertFalse(BraviaPolicy.needsConservativeProfile(4L * 1024 * 1024 * 1024, false))
    }
    @Test fun unknownMemoryUsesConservativeProfile() {
        assertTrue(BraviaPolicy.needsConservativeProfile(0, false))
    }
}
