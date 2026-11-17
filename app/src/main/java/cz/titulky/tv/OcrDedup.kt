package cz.titulky.tv

/** Normalizace a podobnost používaná společně pro OCR vstup i TTS deduplikaci. */
object OcrDedup {
    fun klic(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun podobne(a: String, b: String, agresivita: Int): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val ka = a.lowercase().filter { it.isLetter() }
        val kb = b.lowercase().filter { it.isLetter() }
        if (ka == kb) return true
        if (agresivita <= 0) return false
        val delka = maxOf(ka.length, kb.length)
        // U krátkých replik (Go!/No!/Hey!) by tolerance snadno sloučila dvě různé věty.
        if (delka < 7) return false
        val toleranceDelky = delka * agresivita.coerceIn(0, 100) / 400 + 1
        if (kotlin.math.abs(ka.length - kb.length) > toleranceDelky) return false
        var predchozi = IntArray(kb.length + 1) { it }
        for (i in 1..ka.length) {
            val radek = IntArray(kb.length + 1)
            radek[0] = i
            for (j in 1..kb.length) {
                val cena = if (ka[i - 1] == kb[j - 1]) 0 else 1
                radek[j] = minOf(predchozi[j] + 1, radek[j - 1] + 1, predchozi[j - 1] + cena)
            }
            predchozi = radek
        }
        val toleranceChyb = delka * agresivita.coerceIn(0, 100) / 500 + 1
        return predchozi[kb.length] <= toleranceChyb
    }
}
