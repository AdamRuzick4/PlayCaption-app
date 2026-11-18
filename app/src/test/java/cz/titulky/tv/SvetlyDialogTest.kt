package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class SvetlyDialogTest {
    private fun seda(v: Int) = -0x1000000 or (v * 0x010101)
    private fun radek(p: IntArray, top: Int) {
        for (y in top until top + 6) for (x in 8..40 step 8) {
            p[y * 64 + x - 2] = seda(40)
            p[y * 64 + x] = seda(244)
        }
    }
    @Test fun zachovaGeometriiMezeryIPuvodniObraz() {
        val p = IntArray(64 * 32) { seda(180) }
        radek(p, 6); radek(p, 21)
        val original = p.copyOf()
        val out = requireNotNull(SvetlyDialog.priprav(p, 64, 32))
        assertArrayEquals(original, p)
        assertEquals(p.size, out.size)
        assertEquals(seda(255), out[6 * 64 + 8])
        assertEquals(seda(64), out[21 * 64 + 10])
        assertEquals(seda(180), out[16 * 64 + 8])
        assertEquals(seda(180), out[21 * 64 + 55])
    }

    @Test fun bezBilychTahuNeboNaTmavemPozadiNevznikaDruhyObraz() {
        val svetle = IntArray(64 * 32) { seda(245) }
        assertNull(SvetlyDialog.priprav(svetle, 64, 32))
        val tmave = IntArray(64 * 32) { seda(25) }
        radek(tmave, 0)
        assertNull(SvetlyDialog.priprav(tmave, 64, 32))
        val slabe = IntArray(64 * 32) { seda(180) }
        radek(slabe, 26)
        for (i in slabe.indices) if (slabe[i] == seda(244)) slabe[i] = seda(225)
        assertNull(SvetlyDialog.priprav(slabe, 64, 32))
    }
}
