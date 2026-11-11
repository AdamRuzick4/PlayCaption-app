package cz.titulky.tv

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.vision.common.InputImage
import java.text.Normalizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Čtení textu z obrázku (Google ML Kit, přímo v televizi, zdarma).
 * Titulky bývají dole, takže čteme jen spodní část obrazu.
 */
class Ctecka {

    /** Každá čtečka má vlastní vlákno, aby dvě čtečky mohly číst současně. */
    private val vlakno = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.Builder().setExecutor(vlakno).build()
    )

    /** Jak velkou spodní část obrazu číst (0.3 = spodních 30 %). */
    var spodniCast = 0.3f

    fun prectiTitulek(obraz: Bitmap, hotovo: (String) -> Unit, chyba: (String) -> Unit) {
        val vyska = (obraz.height * spodniCast).toInt().coerceAtLeast(1)
        val vyrez = Bitmap.createBitmap(obraz, 0, obraz.height - vyska, obraz.width, vyska)
        prectiVse(vyrez, hotovo, chyba)
    }

    /** Přečte text z celého předaného obrázku (už oříznutého na oblast titulků). */
    fun prectiVse(obraz: Bitmap, hotovo: (String) -> Unit, chyba: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(obraz, 0))
            .addOnSuccessListener { vysledek ->
                val radky = vysledek.textBlocks
                    .flatMap { it.lines }
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .map { it.text.trim() }
                    // zahodíme šum: řádky s méně než 3 písmeny
                    .filter { r -> r.count { it.isLetter() } >= 3 }
                hotovo(vycisti(radky.joinToString(" ")))
            }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /**
     * Volby odpovědí: přečte všechny řádky v oblasti voleb, seřazené shora dolů,
     * s jejich polohou na obrázku (kvůli poznání, která volba je zvýrazněná).
     * Kusy na stejné výšce se spojí do jednoho řádku.
     */
    fun prectiVolby(obraz: Bitmap, hotovo: (List<Pair<String, Rect>>) -> Unit, chyba: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(obraz, 0))
            .addOnSuccessListener { vysledek ->
                val kusy = vysledek.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { l -> l.boundingBox?.let { Kus(l.text.trim(), it.left, it.top, it.right, it.bottom) } }
                    .sortedBy { it.top }
                val radky = mutableListOf<MutableList<Kus>>()
                for (k in kusy) {
                    val posledni = radky.lastOrNull()
                    if (posledni != null && k.stred in (posledni.minOf { it.top }..posledni.maxOf { it.bottom })) posledni += k
                    else radky += mutableListOf(k)
                }
                hotovo(radky.map { r ->
                    val serazene = r.sortedBy { it.left }
                    serazene.joinToString(" ") { it.text } to
                        Rect(r.minOf { it.left }, r.minOf { it.top }, r.maxOf { it.right }, r.maxOf { it.bottom })
                })
            }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /**
     * Výsledek čtení titulku: jednotlivé repliky (např. "Lucia: …", "Jason: …"),
     * kde přesně na obrázku byly a kolik měly řádků.
     */
    class Titulek(
        val repliky: List<String>,
        val box: Rect?,
        val radku: Int,
        /** Kde na obrázku je jméno postavy u každé repliky (null = bez jména) – kvůli barvě jména. */
        val jmenaBoxy: List<Rect?> = emptyList(),
        /** Spodní nápověda / úkol mise ("Investigate the barn", "Hold □ near items…"), jinak null. */
        val napoveda: String? = null,
        val napovedaBox: Rect? = null
    ) {
        val text get() = repliky.joinToString("\n")
        companion object { val NIC = Titulek(emptyList(), null, 0) }
    }

    /**
     * Přečte jen NEJNOVĚJŠÍ ANGLICKÝ titulek z oblasti titulků.
     * - Některé hry (např. Cyberpunk) nechávají na obrazovce i předchozí repliky nad sebou:
     *   řádky seskupíme, vezmeme ten nejspodnější a přidáváme řádky nad ním, dokud nenarazíme
     *   na řádek se jménem postavy ("Jméno: ...") nebo na velkou mezeru.
     * - Text, který není anglicky (české menu, nápověda k ovládání), se vrátí jako prázdný.
     */
    fun prectiPosledniTitulek(
        obraz: Bitmap, hotovo: (Titulek) -> Unit, chyba: (String) -> Unit,
        /** Výšky (v pixelech tohoto obrázku), kde se dřív ukazovala nápověda k ovládání. */
        pasmaNapovedy: List<IntRange> = emptyList()
    ) {
        recognizer.process(InputImage.fromBitmap(obraz, 0))
            .addOnSuccessListener { vysledek ->
                val kusy = vysledek.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { l -> l.boundingBox?.let { Kus(l.text.trim(), it.left, it.top, it.right, it.bottom) } }
                    .filter { k -> k.text.count { it.isLetter() } >= 2 }
                    .sortedBy { it.top }

                // 1) seskupit kusy na stejné výšce do jednoho vizuálního řádku (jméno + text bývají zvlášť)
                val radky = mutableListOf<MutableList<Kus>>()
                for (k in kusy) {
                    val posledni = radky.lastOrNull()
                    if (posledni != null && k.stred in (posledni.minOf { it.top }..posledni.maxOf { it.bottom })) {
                        posledni += k
                    } else {
                        radky += mutableListOf(k)
                    }
                }
                // kusy na stejné výšce, ale daleko od sebe (menu: vlevo popis, vpravo "Select ⓧ Back ◯")
                // nejsou jeden řádek → rozdělit na samostatné skupiny
                val skupiny = radky.flatMap { r ->
                    val serazene = r.sortedBy { it.left }
                    val vyska = (r.maxOf { it.bottom } - r.minOf { it.top }).coerceAtLeast(1)
                    val vysledek = mutableListOf(mutableListOf(serazene.first()))
                    for (k in serazene.drop(1)) {
                        if (k.left - vysledek.last().last().right > vyska * 2.5f) vysledek += mutableListOf(k)
                        else vysledek.last() += k
                    }
                    vysledek
                }
                var hotoveRadky = skupiny.map { r ->
                    Radek(
                        doplnJmeno(r.joinToString(" ") { it.text }),
                        r.minOf { it.left }, r.minOf { it.top }, r.maxOf { it.right }, r.maxOf { it.bottom },
                        r
                    )
                }.filter { r -> r.text.count { it.isLetter() } >= 3 }
                    // titulky bývají uprostřed – menu vlevo a nápověda vpravo ne
                    .filter { r ->
                        val stred = (r.left + r.right) / 2f / obraz.width
                        stred in 0.3f..0.7f
                    }
                    // krátké řádky velkými písmeny bez interpunkce = položky menu ("STORY", "SETTINGS")
                    .filterNot { r -> jePolozkaMenu(r.text) }

                // spodní nápověda / úkol mise: nejspodnější řádek, který je nápověda k ovládání,
                // nebo nekončí tečkou a je oddělený mezerou od řádků nad ním
                fun konci(t: String) = t.trimEnd().lastOrNull() in setOf('.', '!', '?', '"', '…', '\'')
                val napovedaRadek = hotoveRadky.lastOrNull()?.takeIf { r ->
                    if (NAPOVEDA.containsMatchIn(r.text)) return@takeIf true
                    // ve výšce, kde už dřív byla nápověda, a bez interpunkce → nápověda (i zkomolená:
                    // "G neur iterms to pick therm up…")
                    val stredY = (r.top + r.bottom) / 2
                    if (pasmaNapovedy.any { stredY in it } && r.text.none { it in ".!?…" }) return@takeIf true
                    // úkol mise: krátký, bez jakékoli interpunkce ("Lead the horse to the hitching post");
                    // dialog má skoro vždy čárku, tečku nebo apostrof ("We're fixing to rob some train, …")
                    if (konci(r.text) || pocetSlov(r.text) < 2 || pocetSlov(r.text) > 9 ||
                        r.text.any { it in ".,!?\"…:;" } || STAZENINA.containsMatchIn(r.text) ||
                        JMENO.containsMatchIn(r.text)) return@takeIf false
                    // první řádek dlouhé repliky končí spojkou/předložkou ("…a lot less intimidating than")
                    // → pokračuje dál, je to dialog, ne úkol
                    val posledniSlovo = r.text.trim().substringAfterLast(' ').lowercase()
                    if (posledniSlovo in NEDOKONCENE) return@takeIf false
                    // řádek začínající malým písmenem = pokračování předchozí věty, ne úkol
                    if (r.text.trim().firstOrNull()?.isLowerCase() == true) return@takeIf false
                    val nad = hotoveRadky.getOrNull(hotoveRadky.size - 2) ?: return@takeIf true
                    r.top - nad.bottom > (r.bottom - r.top) * 0.6
                }
                // nápovědy k ovládání nejsou dialog
                hotoveRadky = hotoveRadky.filterNot { r -> r === napovedaRadek || NAPOVEDA.containsMatchIn(r.text) }
                val napovedaText = napovedaRadek?.text
                val napovedaBox = napovedaRadek?.let { Rect(it.left, it.top, it.right, it.bottom) }

                if (hotoveRadky.isEmpty()) {
                    if (napovedaText == null) { diagnostika = ""; hotovo(Titulek.NIC); return@addOnSuccessListener }
                    jazyk.identifyLanguage(napovedaText)
                        .addOnSuccessListener { kod ->
                            diagnostika = "nápověda jazyk=$kod | $napovedaText"
                            val bezDiakritiky = napovedaText.none { it.isLetter() && it.code > 127 }
                            hotovo(if (kod == "en" || (kod != "cs" && kod != "sk" && kod != cilovyJazyk && bezDiakritiky && anglickeSlovo(napovedaText))) Titulek(emptyList(), null, 0, emptyList(), vycisti(napovedaText), napovedaBox) else Titulek.NIC)
                        }
                        .addOnFailureListener { hotovo(Titulek.NIC) }
                    return@addOnSuccessListener
                }

                // úkol mise pod dialogem ("Catch up to Micah") nekončí tečkou, dialog nad ním ano
                // → spodní řádky bez interpunkce zahodit, když je nad nimi věta s tečkou/otazníkem
                run {
                    fun konciVetou(t: String) = t.trimEnd().lastOrNull() in setOf('.', '!', '?', '"', '…')
                    val posledniVeta = hotoveRadky.indexOfLast { konciVetou(it.text) && pocetSlov(it.text) >= 2 }
                    if (posledniVeta >= 0 && posledniVeta < hotoveRadky.lastIndex) {
                        val pod = hotoveRadky.subList(posledniVeta + 1, hotoveRadky.size)
                        val nad = hotoveRadky[posledniVeta]
                        val vyska = (nad.bottom - nad.top).coerceAtLeast(1)
                        // jen když je úkol oddělený mezerou (pokračování repliky na dalším řádku navazuje těsně)
                        if (pod.first().top - nad.bottom > vyska * 0.6 && pod.none { JMENO.containsMatchIn(it.text) }) {
                            hotoveRadky = hotoveRadky.subList(0, posledniVeta + 1)
                        }
                    }
                }

                // 2) od spodu nahoru, dokud nenajdeme začátek repliky
                val vybrane = ArrayDeque<Radek>()
                var spodni = hotoveRadky.last()
                vybrane.addFirst(spodni)
                if (!JMENO.containsMatchIn(spodni.text)) {
                    for (r in hotoveRadky.dropLast(1).asReversed()) {
                        val vyska = (spodni.bottom - spodni.top).coerceAtLeast(1)
                        val mezera = spodni.top - r.bottom
                        if (mezera > vyska * 0.9) break       // velká mezera = jiná replika
                        vybrane.addFirst(r)
                        spodni = r
                        if (JMENO.containsMatchIn(r.text)) break // tady replika začíná
                    }
                }
                // 2b) rozhovor jako v GTA: krátké repliky se jmény těsně nad sebou ("Lucia: …" / "Jason: …")
                //     → vzít i ty (nejvýš 3 repliky). U Cyberpunku má starší replika víc řádků
                //     a její poslední řádek jméno nemá, takže se sem nepřidá.
                if (vybrane.size == 1 && JMENO.containsMatchIn(vybrane.first().text)) {
                    var horni = vybrane.first()
                    for (r in hotoveRadky.dropLast(1).asReversed()) {
                        if (vybrane.size >= 3) break
                        val vyska = (horni.bottom - horni.top).coerceAtLeast(1)
                        if (horni.top - r.bottom > vyska * 0.9) break
                        if (!JMENO.containsMatchIn(r.text)) break
                        vybrane.addFirst(r)
                        horni = r
                    }
                }

                // rozdělit na repliky: nová replika začíná řádkem se jménem
                val repliky = mutableListOf<String>()
                val jmenaBoxy = mutableListOf<Rect?>()
                for (r in vybrane) {
                    if (repliky.isEmpty() || JMENO.containsMatchIn(r.text)) {
                        repliky += r.text
                        jmenaBoxy += boxJmena(r)
                    } else repliky[repliky.lastIndex] = repliky.last() + " " + r.text
                }

                // sloupec krátkých řádků (1–2 slova) = menu, ne titulek
                if (vybrane.size >= 3 && vybrane.count { pocetSlov(it.text) <= 2 } >= vybrane.size - 1) {
                    diagnostika = "menu (sloupec krátkých řádků) | " + vybrane.joinToString(" / ") { it.text }
                    hotovo(Titulek.NIC); return@addOnSuccessListener
                }
                val box = Rect(
                    vybrane.minOf { it.left }, vybrane.minOf { it.top },
                    vybrane.maxOf { it.right }, vybrane.maxOf { it.bottom }
                )

                // 3) je to anglicky? (rozpoznání jazyka na PŮVODNÍM textu – i s háčky)
                val bezJmen = repliky.joinToString(" ") { JMENO_CELE.find(it)?.groupValues?.get(1) ?: it }
                jazyk.identifyLanguage(bezJmen)
                    .addOnSuccessListener { kod ->
                        diagnostika = "jazyk=$kod | " + repliky.joinToString(" / ") +
                            (napovedaText?.let { " || nápověda: $it" } ?: "")
                        // krátké repliky ("Ah, gentlemen.", "Arthur...") rozpoznávač jazyka často neurčí →
                        // když v textu nejsou žádné znaky s diakritikou, bereme ho jako angličtinu
                        val bezDiakritiky = bezJmen.none { it.isLetter() && it.code > 127 }
                        if (kod == "en" || (kod != "cs" && kod != "sk" && kod != cilovyJazyk && bezDiakritiky && bezJmen.length < 40 && anglickeSlovo(bezJmen))) hotovo(Titulek(repliky.map { vycisti(it) }, box, vybrane.size, jmenaBoxy,
                            napovedaText?.let { vycisti(it) }, napovedaBox))
                        else hotovo(Titulek.NIC) // čeština, jiný jazyk nebo nejistota → nepřekládat
                    }
                    .addOnFailureListener { hotovo(Titulek.NIC) }
            }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /** Jména postav, která už jsme viděli s dvojtečkou ("Doktor Paradox:") – pamatují se do restartu. */
    private val znamaJmena = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Když čtení vynechá dvojtečku za jménem ("Doktor Paradox Corporations say…") nebo jméno
     * trochu zkomolí ("Doktar Poradox"), doplní ji podle už známých jmen → jméno se nepřekládá
     * a replika se správně oddělí od předchozí.
     */
    private fun doplnJmeno(text: String): String {
        JMENO_CELE.find(text)?.let {
            val jm = text.substringBefore(':').trim()
            if (jm.length >= 2 && jm.split(' ').size <= 3) znamaJmena.add(jm)
            return text
        }
        val kopie = synchronized(znamaJmena) { znamaJmena.toList() }
        for (jm in kopie) {
            for (delka in (jm.length - 2)..(jm.length + 2)) {
                if (delka < 2 || delka >= text.length) continue
                val dalsi = text[delka]
                if (dalsi != ' ' && dalsi != '.' && dalsi != ',') continue
                val zacatek = text.substring(0, delka)
                if (podobnaJmena(zacatek, jm)) {
                    return jm + ": " + text.substring(delka + 1).trimStart(' ', '.', ',')
                }
            }
        }
        return text
    }

    private fun podobnaJmena(a: String, b: String): Boolean {
        val x = a.lowercase(); val y = b.lowercase()
        if (x == y) return true
        if (x.firstOrNull() != y.firstOrNull()) return false
        var pred = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            val rad = IntArray(y.length + 1); rad[0] = i
            for (j in 1..y.length) rad[j] = minOf(pred[j] + 1, rad[j - 1] + 1, pred[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            pred = rad
        }
        return pred[y.length] <= maxOf(1, y.length / 6)
    }

    /** Do jakého jazyka se překládá – text v tomto jazyce se nepřekládá (menu PS5 apod.). */
    @Volatile var cilovyJazyk = "cs"

    /** Co čtečka naposledy přečetla a jaký jazyk poznala (pro záznam titulky.log). */
    @Volatile var diagnostika = ""

    private val jazyk = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )

    private fun pocetSlov(t: String) = t.split(Regex("\\s+")).count { sl -> sl.count { it.isLetter() } >= 2 }

    private fun jePolozkaMenu(t: String): Boolean {
        val pismena = t.filter { it.isLetter() }
        if (pismena.isEmpty()) return true
        val velka = pismena.count { it.isUpperCase() }.toFloat() / pismena.length
        val maInterpunkci = t.any { it in ".,!?:;'" }
        if (velka > 0.8f && pocetSlov(t) <= 3 && !maInterpunkci) return true
        // dlouhý řádek skoro celý VELKÝMI písmeny = lišta ovládání ("SETTINGS PHOTO MODE SELECT BACK")
        return velka > 0.85f && pocetSlov(t) >= 4
    }

    private class Kus(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val stred get() = (top + bottom) / 2
    }
    private class Radek(
        val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
        val kusy: List<Kus> = emptyList()
    )

    /** Odhadne, kde v řádku je jméno postavy ("Lucia:"), aby šlo zjistit jeho barvu. */
    private fun boxJmena(r: Radek): Rect? {
        val jmeno = JMENO_CELE.find(r.text)?.let { r.text.substringBefore(':') } ?: return null
        // jméno bývá samostatný kus (jinou barvou) – pak máme přesný obdélník
        r.kusy.firstOrNull { it.text.trimEnd(':', ' ') == jmeno }?.let { return Rect(it.left, it.top, it.right, it.bottom) }
        // jinak odhad podle délky textu v prvním kusu
        val k = r.kusy.firstOrNull() ?: return null
        val podil = (jmeno.length + 1).toFloat() / k.text.length.coerceAtLeast(1)
        return Rect(k.left, k.top, k.left + ((k.right - k.left) * podil.coerceAtMost(1f)).toInt(), k.bottom)
    }

    /**
     * Opraví typické chyby čtení anglického titulku:
     * - háčky/čárky (např. okno budovy nad "s" → "š"): angličtina je nemá
     * - zbytečný apostrof na konci slova ("told'" → "told"), ale "cops'" nechá
     * - samotné malé "i" → "I"
     */
    fun vycisti(text: String): String {
        var t = text.replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace(Regex("(?<=[A-Za-mo-rt-z])'(?=\\s|$|[.,!?])"), "") // "huntin'" nechat (slang)
        t = t.replace(Regex("(?<![A-Za-z'])i(?![A-Za-z'])"), "I")
        t = t.replace(Regex("\\s+"), " ").trim()
        // slovo začínající malým písmenem s velkými uvnitř = chyba čtení ("crOSs" → "cross")
        t = t.replace(Regex("\\b[a-z]+[A-Z][A-Za-z]*\\b")) { it.value.lowercase() }
        return t
    }

    fun zavri() {
        recognizer.close()
        jazyk.close()
        vlakno.shutdown()
    }

    companion object {
        /** Začátek repliky: "Jméno:" nebo "DOKTOR PARADOX:" na začátku řádku. */
        val JMENO = Regex("^[A-Z][A-Za-z0-9 .'-]{1,24}:\\s")
        /** Nápověda k ovládání: "press/tap/hold/use … to …", tlačítka R1/L2…, symboly tlačítek. */
        /** Slova, kterými nekončí úkol mise, ale klidně první řádek rozdělené repliky. */
        val NEDOKONCENE = setOf(
            "than", "and", "the", "to", "of", "a", "an", "for", "with", "but", "or", "that", "if",
            "in", "on", "at", "you", "we", "i", "he", "she", "they", "is", "are", "was", "be",
            "my", "your", "our", "their", "his", "her", "this", "so", "as", "from", "about", "just"
        )
        /** Stažený tvar ("we're", "don't", "'em") – typický pro dialog. "O'Driscoll" se nepočítá. */
        val STAZENINA = Regex("[a-z]['’][a-z]|(?<![A-Za-z])['’](em|cause|til|round)\\b")
        /** Běžná anglická slova – u krátkých replik, kde rozpoznání jazyka nic neřekne. */
        private val ANGLICKA = setOf(
            // bez slov, která jsou i česká ("a", "i", "to", "no", "on")
            "an", "the", "you", "he", "she", "we", "they", "it", "me", "him", "her", "us", "them",
            "my", "your", "our", "is", "are", "was", "be", "did", "not", "yes", "yeah", "okay", "ok",
            "oh", "ah", "hey", "hello", "come", "go", "get", "here", "there", "now", "what", "who", "why",
            "where", "how", "this", "that", "of", "in", "up", "down", "out", "for", "with", "and", "but",
            "easy", "slow", "stop", "wait", "help", "run", "look", "sorry", "thanks", "thank", "well", "sure",
            "right", "please", "damn", "hell", "shit", "fuck", "god", "boy", "boys", "miss", "mister", "sir",
            "gentlemen", "friend", "always", "never", "again", "too", "all", "just", "so", "very", "way", "it's",
            "don't", "i'm", "can't", "let's", "what's", "that's", "you're", "we're", "ain't"
        )
        fun anglickeSlovo(t: String) = t.lowercase().split(Regex("[^a-z']+")).any { it in ANGLICKA }

        val NAPOVEDA = Regex(
            "(?i)\\b(press|tap|hold|use|select|toggle)(?:[iIlL1|DdOo0]|\\b).{0,40}\\bto\\b" +
                "|\\bpeek out\\b|\\bout of cover\\b|\\breload\\b" +
                // zbytek nápovědy, když se "Hold ⓧ" nepřečte: "to match speed with Dutch", "to continue following"
                "|^\\s*(?-i:to) (match|continue|follow|greet|antagonize|defuse|aim|fire|shoot|interact|mount|dismount|" +
                "pick up|open|enter|exit|loot|search|draw|holster|sprint|crouch|whistle|lasso|hogtie|skin|eat|drink|" +
                "skip|confirm|accept|decline|cancel|ride|lead|hitch|calm|brush|feed|stow|equip)\\b" +
                "|\\bmatch speed\\b|\\bcontinue following\\b" +
                "|\\b(R1|R2|R3|L1|L2|L3|RB|LB|RT|LT)\\b" +
                "|[\u24E7\u24C4\u25B3\u25A1\u2715\u25CB\u24CE\u24CF]"
        )
        /** Totéž, ale vrátí text za jménem (skupina 1). */
        val JMENO_CELE = Regex("^[A-Z][A-Za-z0-9 .'-]{1,24}:\\s*(.+)$")
    }
}
