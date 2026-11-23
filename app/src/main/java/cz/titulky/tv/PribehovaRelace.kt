package cz.titulky.tv

/** Napojení finálních překladů na jedinou čtecí frontu. Volat na jednom vlákně. */
internal class PribehovaRelace(
    private val preloz: (List<String>, String, (String) -> Unit, () -> Unit) -> Unit,
    private val hlaseni: (String) -> Unit,
    private val zKnihovny: (String) -> String? = { null }
) {
    private val fronta = CteciFronta()
    private val historie = KontextPrekladu()
    private var posledni: CteciFronta.Token? = null
    private val barvy = linkedMapOf<CteciFronta.Token, IntArray>()
    private var posledniOcr = -1L
    private var zdrojId = 0L
    private var zdrojRevize = 0
    private var posledniSamostatnyZdroj: Long? = null
    private var ocrText = ""
    private var cekaniPovoleno = true
    @Volatile var cekaNaVetu = false
        private set
    private val skladani = SkladaniVet { veta ->
        val usek = veta.zdroje.singleOrNull()
        posledniSamostatnyZdroj = usek?.takeIf { it.id == zdrojId && it.od == 0 && it.doZnaku == ocrText.length }?.id
        prijmi(listOf(veta.text), intArrayOf(veta.barva), false, casVstupu)
    }
    private var casVstupu = 0L

    /** cislo je pořadí OCR práce; stabilní ID vzniká jen pro událost nové repliky. */
    fun prijmiOcr(cislo: Long, text: List<String>, jmena: IntArray, oprava: Boolean, ted: Long) {
        if (cislo <= posledniOcr) return
        posledniOcr = cislo
        casVstupu = ted
        if (oprava) {
            if (zdrojId == 0L) return
            zdrojRevize++
        } else {
            zdrojId++; zdrojRevize = 0
        }
        ocrText = text.joinToString("\n")
        // Jednoduchý dosud nečtený titulek lze opravit původní revizní cestou; složené části znovu nevydávat.
        if (oprava && posledniSamostatnyZdroj == zdrojId) prijmi(text, jmena, true, ted)
        skladani.prijmi(SkladaniVet.Replika(zdrojId, zdrojRevize, ocrText,
            barvy = jmena.copyOf()), ted, cekaniPovoleno, celyPreklad = zKnihovny(ocrText) != null)
        cekaNaVetu = skladani.ceka
    }

    fun zmizelo(cislo: Long, ted: Long) {
        if (cislo < posledniOcr) return
        posledniOcr = cislo
        casVstupu = ted
        skladani.zmizelo(ted)
        cekaNaVetu = skladani.ceka
    }

    val generace get() = fronta.generace
    val pocet get() = fronta.pocet
    fun nastavCasovani(procenta: Int) = fronta.nastavCasovani(procenta)

    fun prijmi(text: List<String>, jmena: IntArray, oprava: Boolean, ted: Long) {
        val token = if (oprava && posledni != null) {
            val stary = posledni!!
            fronta.oprav(stary)?.also { barvy.remove(stary) } ?: return
        } else fronta.zarad(ted) ?: run { hlaseni("Příběhová fronta je plná — přepni do Standardního režimu."); return }
        posledni = token
        barvy[token] = jmena.copyOf()
        val kontext = historie.prijmi(token.poradi, text.joinToString("\n"), ted)
        val hotovo: (String) -> Unit = { cesky ->
            if (cesky.isBlank()) chyba(token)
            else if (fronta.priprav(token, listOf(cesky)) == CteciFronta.Prijeti.LIMIT_TEXTU) chyba(token)
        }
        val cesky = zKnihovny(text.joinToString("\n"))
        if (cesky != null) hotovo(cesky) else preloz(text.toList(), kontext, hotovo) { chyba(token) }
    }
    private fun chyba(token: CteciFronta.Token) {
        if (fronta.chyba(token)) { barvy.remove(token); hlaseni("Jednu repliku se nepodařilo přeložit.") }
    }
    fun krok(ted: Long, externi: Boolean, pauza: Boolean): CteciFronta.Stranka? {
        casVstupu = ted
        cekaniPovoleno = !pauza && fronta.pocet < 3
        if (cekaniPovoleno) skladani.krok(ted) else skladani.prerus(ted)
        cekaNaVetu = skladani.ceka
        fronta.pozastav(pauza, ted)
        val p = fronta.zobraz(ted, externi)
        barvy.keys.retainAll(barvy.keys.filter { fronta.platny(it) }.toSet())
        return p
    }
    fun barvy(token: CteciFronta.Token) = barvy[token]?.copyOf() ?: intArrayOf()
    fun potvrzeno(token: CteciFronta.Token) = fronta.potvrzeno(token)
    fun chybaVystupu(token: CteciFronta.Token) {
        if (fronta.chybaZobrazeni(token)) { barvy.remove(token); hlaseni("Replika se nevešla do přenosu na TV.") }
    }
    fun reset() {
        historie.reset()
        skladani.reset(); posledniSamostatnyZdroj = null; ocrText = ""; cekaNaVetu = false; posledniOcr = -1; zdrojId = 0; zdrojRevize = 0; casVstupu = 0; cekaniPovoleno = true
        fronta.reset(); barvy.clear(); posledni = null
    }
}
