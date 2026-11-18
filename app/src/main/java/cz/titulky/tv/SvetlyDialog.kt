package cz.titulky.tv

/** Záchranný vstup OCR. Zachová souřadnice a kontrast tmavých řádků.
 * Na LG řádky vybere PP-OCR. Tady použijeme světlé tahy s tmavým okrajem v pásu dialogu.
 */
internal object SvetlyDialog {
    fun priprav(jas: IntArray, w: Int, h: Int): IntArray? {
        var pixely: IntArray? = null
        var zacatek = -1
        var levy = w
        var pravy = -1
        fun hodnota(i: Int) = jas[i] and 255
        fun seda(v: Int) = -0x1000000 or (v * 0x010101)
        for (radek in 0..h) {
            var prvni = w
            var posledni = -1
            var hrany = 0
            if (radek < h) {
                val od = radek * w
                for (sloupec in 2 until w - 2 step 2) {
                    if (hodnota(od + sloupec) >= 240 &&
                        minOf(hodnota(od + sloupec - 2), hodnota(od + sloupec + 2)) < 160) {
                        if (hrany == 0) prvni = sloupec
                        posledni = sloupec
                        hrany++
                    }
                }
            }
            if (hrany >= 4) {
                if (zacatek < 0) zacatek = radek
                levy = minOf(levy, prvni)
                pravy = maxOf(pravy, posledni)
                continue
            }
            if (zacatek < 0) continue
            if (radek - zacatek >= 3) {
                val l = (levy - 4).coerceAtLeast(0)
                val r = (pravy + 5).coerceAtMost(w)
                val t = (zacatek - 3).coerceAtLeast(0)
                val b = (radek + 3).coerceAtMost(h)
                val plocha = (r - l) * (b - t)
                var svetle = 0
                var bile = 0
                for (j in t until b) for (i in l until r) {
                    val v = hodnota(j * w + i)
                    if (v >= 100) svetle++
                    if (v >= 240) bile++
                }
                if (svetle * 4 >= plocha && bile * 50 >= plocha) {
                    val out = pixely ?: jas.copyOf().also { pixely = it }
                    for (j in t until b) for (i in l until r) {
                        val v = hodnota(j * w + i)
                        out[j * w + i] = seda(((v - 160).coerceIn(0, 80) * 255 + 40) / 80)
                    }
                }
            }
            zacatek = -1
            levy = w
            pravy = -1
        }
        return pixely
    }
}
