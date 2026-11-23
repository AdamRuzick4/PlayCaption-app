package cz.titulky.tv

/** Jen dosud nepředané anglické části. Nepřekládá a neřídí dobu čtení. Volat na jednom vlákně. */
internal class SkladaniVet(private val hotovo: (Veta) -> Unit) {
    data class Replika(val id: Long, val revize: Int, val text: String, val barva: Int = 0, val barvy: IntArray = intArrayOf(barva))
    data class Usek(val id: Long, val revize: Int, val od: Int, val doZnaku: Int)
    data class Veta(val text: String, val barva: Int, val zdroje: List<Usek>)
    private data class Cast(val zdroj: Replika, val od: Int, val doZnaku: Int, val text: String, val jmeno: String?, val barva: Int)
    private enum class Konec { SUCH, CLEN, TO }
    private val casti = mutableListOf<Cast>()
    private var posledniId = -1L
    private var revize = -1
    private var uvolneneId = -1L
    private var doKdy = 0L
    private var cas = 0L

    val ceka get() = casti.isNotEmpty()

    fun prijmi(replika: Replika, ted: Long, cekat: Boolean = true, celyPreklad: Boolean = false) {
        require(replika.id >= 0 && replika.revize >= 0)
        krok(ted)
        if (replika.id < posledniId || (replika.id == posledniId && replika.revize <= revize)) return
        val oprava = replika.id == posledniId
        posledniId = replika.id
        revize = replika.revize
        if (oprava && replika.id <= uvolneneId) return
        val puvodniTermin = if (oprava && casti.any { it.zdroj.id == replika.id }) doKdy else null
        if (oprava && puvodniTermin == null) return
        if (oprava) casti.removeAll { it.zdroj.id == replika.id }
        if (celyPreklad) {
            uvolni()
            casti += Cast(replika, 0, replika.text.length, replika.text, null, replika.barvy.firstOrNull() ?: replika.barva)
            uvolni()
            return
        }
        if (!cekat) uvolni()
        for (cast in rozdel(replika)) {
            if (casti.isNotEmpty() && !navazuje(cast)) uvolni()
            if (casti.isNotEmpty() && (casti.map { it.zdroj.id }.distinct().size >= 3 ||
                    casti.sumOf { it.text.length } + cast.text.length > 800)) uvolni()
            if (casti.isEmpty()) doKdy = puvodniTermin ?: ted + 2500
            casti += cast
            if (!cekat || casti.sumOf { it.text.length } > 800 || hotova(cast.text) || otevreny() == null) uvolni()
        }
    }

    fun krok(ted: Long) {
        require(ted >= cas) { "Skládání vět vyžaduje monotónní čas" }
        cas = ted
        if (casti.isNotEmpty() && ted >= doKdy) uvolni()
    }

    fun zmizelo(ted: Long) {
        krok(ted)
        if (casti.isNotEmpty()) doKdy = minOf(doKdy, ted + 600)
    }

    fun prerus(ted: Long) { krok(ted); uvolni() }

    fun reset() {
        casti.clear(); posledniId = -1; revize = -1; uvolneneId = -1; doKdy = 0; cas = 0
    }

    private fun uvolni() {
        if (casti.isEmpty()) return
        val prvni = casti.first()
        val text = casti.joinToString(" ") { it.text }
        val veta = Veta((prvni.jmeno?.let { "$it: " } ?: "") + text, prvni.barva,
            casti.map { Usek(it.zdroj.id, it.zdroj.revize, it.od, it.doZnaku) })
        uvolneneId = maxOf(uvolneneId, casti.maxOf { it.zdroj.id })
        casti.clear()
        hotovo(veta)
    }

    private fun otevreny(): Konec? {
        val text = casti.joinToString(" ") { it.text }.trimEnd()
        if (text.split(Regex("\\s+")).size < 3 || hotova(text)) return null
        // shortcut: jen silné konstrukce, ne obecný anglický parser; rozšiřovat podle negativních testů.
        if (Regex("(?i)\\b(?:am|are|is|was|were|be|been)\\s+such$").containsMatchIn(text)) return Konec.SUCH
        if (Regex("(?i)\\b(?:a|an|the)$").containsMatchIn(text) &&
            (casti.first().jmeno != null || Regex("(?i)\\bsuch\\s+(?:a|an)$").containsMatchIn(text))) return Konec.CLEN
        if (casti.first().jmeno != null && Regex("(?i)\\b(?:need|needs|needed|want|wants|wanted|going|trying)\\s+to$").containsMatchIn(text)) return Konec.TO
        return null
    }

    private fun navazuje(nova: Cast): Boolean {
        val prvni = casti.first()
        if (prvni.jmeno != nova.jmeno) return false
        if (prvni.barva != 0 && nova.barva != 0 && prvni.barva != nova.barva) return false
        if (nova.text.firstOrNull()?.isLowerCase() != true) return false
        if (Regex("^(?:i|you|he|she|it|we|they)\\b", RegexOption.IGNORE_CASE).containsMatchIn(nova.text)) return false
        if (Regex("(?i)\\b(?:am|is|are|was|were|have|has|had|will|would|can|could|shall|should|must|do|does|did)\\b").containsMatchIn(nova.text)) return false
        return when (otevreny()) {
            Konec.SUCH -> Regex("^(?:a|an)(?:\\s|$)").containsMatchIn(nova.text)
            Konec.CLEN -> true
            Konec.TO -> !Regex("^(?:a|an|the|my|your|his|her|our|their)(?:\\s|$)").containsMatchIn(nova.text)
            null -> false
        }
    }

    private fun rozdel(r: Replika): List<Cast> {
        val vysledek = mutableListOf<Cast>()
        var offset = 0
        for ((index, radek) in r.text.split('\n').withIndex()) {
            val jmeno = VyberTitulku.JMENO.find(radek)
            val nazev = jmeno?.value?.substringBefore(':')?.trim()
            val zacatek = jmeno?.range?.let { it.last + 1 } ?: 0
            var od = zacatek
            fun pridej(konec: Int) {
                val telo = radek.substring(od, konec).trim()
                if (telo.isNotEmpty()) vysledek += Cast(r, offset + if (od == zacatek) 0 else od,
                    offset + konec, telo, nazev, r.barvy.getOrElse(index) { 0 })
                od = konec
            }
            for (m in Regex("[.!?]+[\\\"'”’)]*(?=\\s|$)").findAll(radek, zacatek)) {
                val konec = m.range.last + 1
                if (hotova(radek.substring(od, konec))) pridej(konec)
            }
            if (od < radek.length) pridej(radek.length)
            offset += radek.length + 1
        }
        return vysledek
    }

    private fun hotova(text: String): Boolean {
        val t = text.trimEnd().trimEnd('"', '\'', '”', '’', ')')
        if (t.endsWith("..") || t.endsWith('…') || t.lastOrNull() !in setOf('.', '!', '?')) return false
        if (!t.endsWith('.')) return true
        // Tituly, iniciály a nejednoznačné zkratky nejsou bezpečná hranice věty.
        return !Regex("(?i)(?:\\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc|e\\.g|i\\.e)|\\b[A-Z])\\.$").containsMatchIn(t)
    }
}
