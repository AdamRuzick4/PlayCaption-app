package cz.titulky.tv

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Překlad angličtina → čeština.
 *
 * 1) Google Překladač přes internet (bez klíče, neoficiální přístup – může ho kdykoli omezit).
 * 2) Když nejde (bez internetu, zablokováno, pomalé), přeloží to Google ML Kit přímo v televizi.
 *
 * Výsledek se vždy vrací na hlavním vlákně.
 */
class Prekladac(context: android.content.Context? = null, cilovy: String = "cs") {

    /** Cílový jazyk překladu (kód: cs, sk, de, pl, fr, es, it). */
    @Volatile var cil: String = cilovy
        private set

    private fun novyTranslator(kod: String): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(kod) ?: TranslateLanguage.CZECH)
            .build()
    )

    private var translator: Translator = novyTranslator(cilovy)

    /** Přepne cílový jazyk (např. po změně v aplikaci). */
    fun nastavJazyk(kod: String) {
        if (kod == cil) return
        val stary = translator
        cil = kod
        translator = novyTranslator(kod)
        cache.evictAll()
        runCatching { stary.close() }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
    }

    private val ui = Handler(Looper.getMainLooper())
    private val sit = Executors.newSingleThreadExecutor()


    /** Paměť na už přeložené věty – ve hrách se věty často opakují. */
    private val cache = LruCache<String, String>(500)

    var pripraven = false
        private set

    /** Odkud byl poslední překlad: "google", "tv" nebo "paměť" (pro záznam). */
    @Volatile var zdroj = ""
        private set

    /** Když Google selže, chvíli ho nezkoušet (ať každá věta nečeká na chybu). */
    @Volatile private var googlePauzaDo = 0L
    @Volatile private var edgePauzaDo = 0L
    @Volatile private var myMemoryPauzaDo = 0L
    @Volatile private var edgeToken: String? = null
    @Volatile private var edgeTokenDo = 0L
    /** Kolikrát Google selhal (pro záznam). */
    @Volatile var chybGooglu = 0
        private set

    /** Stáhne jazykový balíček pro záložní překlad v televizi (jen poprvé). */
    fun priprav(hotovo: () -> Unit, chyba: (String) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { pripraven = true; hotovo() }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /** Přeloží anglický text do češtiny. */
    fun preloz(text: String, hotovo: (String) -> Unit, chyba: (String) -> Unit) {
        val vstup = upravSlang(text.trim())
        if (vstup.isEmpty()) { hotovo(""); return }
        cache.get(vstup)?.let { zdroj = "paměť"; hotovo(it); return }

        sit.execute {
            val ted = System.currentTimeMillis()
            var vysledek: String? = null
            var odkud = ""
            // 1) Google – 2 pokusy (udržované spojení mohl Google mezitím zavřít)
            if (ted >= googlePauzaDo) {
                vysledek = runCatching { google(vstup) }.getOrNull() ?: runCatching { google(vstup) }.getOrNull()
                if (vysledek.isNullOrBlank()) { googlePauzaDo = ted + 30_000L; chybGooglu++ } else odkud = "google"
            }
            // 2) Microsoft (překladač z prohlížeče Edge) – bez registrace
            if (vysledek.isNullOrBlank() && ted >= edgePauzaDo) {
                vysledek = runCatching { edge(vstup) }.getOrNull() ?: runCatching { edgeToken = null; edge(vstup) }.getOrNull()
                if (vysledek.isNullOrBlank()) edgePauzaDo = ted + 60_000L else odkud = "microsoft"
            }
            // 3) MyMemory – veřejná služba zdarma, bez registrace (denní limit)
            if (vysledek.isNullOrBlank() && ted >= myMemoryPauzaDo) {
                vysledek = runCatching { myMemory(vstup) }.getOrNull()
                if (vysledek.isNullOrBlank()) myMemoryPauzaDo = ted + 5 * 60_000L else odkud = "mymemory"
            }
            val hotovyText = vysledek
            ui.post {
                if (!hotovyText.isNullOrBlank()) {
                    zdroj = odkud
                    cache.put(vstup, hotovyText)
                    hotovo(hotovyText)
                } else {
                    // nic online nejde → překlad v televizi
                    prelozZalozne(vstup, hotovo, chyba)
                }
            }
        }
    }

    /** Jen rychlý překlad přímo v televizi (bez internetu) – na okamžitý náhled. */
    fun prelozRychle(text: String, hotovo: (String) -> Unit, chyba: (String) -> Unit) {
        val vstup = upravSlang(text.trim())
        if (vstup.isEmpty()) { hotovo(""); return }
        // když už Google tuhle větu znal, vrátit rovnou dobrý překlad
        cache.get(vstup)?.let { hotovo(it); return }
        translator.translate(vstup)
            .addOnSuccessListener { hotovo(it) }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /** Záloha, když nic online nejde: překladač přímo v televizi. */
    private fun prelozZalozne(vstup: String, hotovo: (String) -> Unit, chyba: (String) -> Unit) =
        prelozVTelevizi(vstup, hotovo, chyba)

    private fun prelozVTelevizi(vstup: String, hotovo: (String) -> Unit, chyba: (String) -> Unit) {
        translator.translate(vstup)
            .addOnSuccessListener { cesky -> zdroj = "tv"; hotovo(cesky) }
            .addOnFailureListener { chyba(it.message ?: "Neznámá chyba") }
    }

    /** Neoficiální adresa Google Překladače (stejnou používají rozšíření do prohlížečů). */
    private fun google(text: String): String? {
        val adresa = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=$cil&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val spojeni = URL(adresa).openConnection() as HttpURLConnection
        try {
            spojeni.connectTimeout = 1500
            spojeni.readTimeout = 2000
            spojeni.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (spojeni.responseCode != 200) { spojeni.disconnect(); return null }
            val json = spojeni.inputStream.bufferedReader().use { it.readText() }
            // odpověď: [[["přeložená věta","původní",…],["další věta",…]],…]
            val kusy = JSONArray(json).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until kusy.length()) {
                val kus = kusy.optJSONArray(i) ?: continue
                sb.append(kus.optString(0, ""))
            }
            return sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            spojeni.disconnect()
            throw e
        }
        // úspěch: spojení NEzavírat – další překlad ho použije znovu (ušetří ~0,3 s na navázání)
    }

    /**
     * Microsoft Translator přes token prohlížeče Edge (neoficiální, bez registrace).
     * Token platí ~10 min, bereme ho znovu po 8 minutách.
     */
    private fun edge(text: String): String? {
        val ted = System.currentTimeMillis()
        var token = edgeToken
        if (token == null || ted > edgeTokenDo) {
            val s = URL("https://edge.microsoft.com/translate/auth").openConnection() as HttpURLConnection
            s.connectTimeout = 1500; s.readTimeout = 2000
            s.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (s.responseCode != 200) { s.disconnect(); return null }
            token = s.inputStream.bufferedReader().use { it.readText() }.trim()
            if (token.isEmpty()) return null
            edgeToken = token
            edgeTokenDo = ted + 8 * 60_000L
        }
        val spojeni = URL("https://api-edge.cognitive.microsofttranslator.com/translate?from=en&to=$cil&api-version=3.0")
            .openConnection() as HttpURLConnection
        spojeni.connectTimeout = 1500; spojeni.readTimeout = 2500
        spojeni.requestMethod = "POST"
        spojeni.doOutput = true
        spojeni.setRequestProperty("Authorization", "Bearer $token")
        spojeni.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val telo = JSONArray().put(org.json.JSONObject().put("Text", text)).toString()
        spojeni.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
        if (spojeni.responseCode == 401) { edgeToken = null; spojeni.disconnect(); return null }
        if (spojeni.responseCode != 200) { spojeni.disconnect(); return null }
        val json = spojeni.inputStream.bufferedReader().use { it.readText() }
        // odpověď: [{"translations":[{"text":"…","to":"cs"}]}]
        return JSONArray(json).getJSONObject(0).getJSONArray("translations")
            .getJSONObject(0).optString("text").trim().ifEmpty { null }
    }

    /** MyMemory – veřejná překladová služba zdarma bez registrace (s denním limitem). */
    private fun myMemory(text: String): String? {
        val adresa = "https://api.mymemory.translated.net/get?langpair=en%7C$cil&q=" + URLEncoder.encode(text, "UTF-8")
        val spojeni = URL(adresa).openConnection() as HttpURLConnection
        spojeni.connectTimeout = 1500; spojeni.readTimeout = 3000
        if (spojeni.responseCode != 200) { spojeni.disconnect(); return null }
        val json = org.json.JSONObject(spojeni.inputStream.bufferedReader().use { it.readText() })
        if (json.optInt("responseStatus", 0) != 200) return null
        val preklad = json.getJSONObject("responseData").optString("translatedText").trim()
        // po vyčerpání limitu vrací místo překladu varování
        if (preklad.isEmpty() || preklad.uppercase().startsWith("MYMEMORY WARNING")) return null
        return preklad
    }

    /** Udržuje spojení s Googlem "teplé", aby první věta po pauze nečekala na připojení. */
    fun udrzujSpojeni() {
        if (System.currentTimeMillis() < googlePauzaDo) return
        sit.execute { runCatching { google("ok") } }
    }

    fun zavri() {
        translator.close()
        sit.shutdown()
    }

    companion object {
        /** Hovorová angličtina ze her → spisovná, ať překladač rozumí. */
        private val SLANG = listOf(
            "gonna" to "going to",
            "wanna" to "want to",
            "gotta" to "got to",
            "ain't" to "is not",
            "'em" to "them",
            "y'all" to "you all",
            "kinda" to "kind of",
            "sorta" to "sort of",
            "outta" to "out of",
            "lemme" to "let me",
            "gimme" to "give me",
            "dunno" to "don't know",
            "c'mon" to "come on",
            "y'know" to "you know",
            "'cause" to "because",
            "cuz" to "because",
            "'til" to "until",
            "'round" to "around",
            "ol'" to "old",
            "fixing to" to "going to",
            "fixin' to" to "going to",
            "fora" to "for a"
        ).map { (z, na) ->
            Regex("(?i)(?<![\\p{L}'])" + Regex.escape(z) + "(?![\\p{L}])") to na
        }

        /**
         * Časté chyby čtení z obrazu (ozdobné písmo ve hrách) → správné slovo.
         * Velikost písmen se bere přesně (např. "Im" opravit, ale ne "im" uvnitř jiného textu).
         */
        private val OCR = listOf(
            // ztracený apostrof
            "Im" to "I'm", "Ive" to "I've", "Ill" to "I'll", "Itl" to "It'll", "Itll" to "It'll",
            "dont" to "don't", "Dont" to "Don't", "cant" to "can't", "Cant" to "Can't",
            "didnt" to "didn't", "Didnt" to "Didn't", "doesnt" to "doesn't", "Doesnt" to "Doesn't",
            "isnt" to "isn't", "Isnt" to "Isn't", "wasnt" to "wasn't", "Wasnt" to "Wasn't",
            "werent" to "weren't", "arent" to "aren't", "havent" to "haven't", "hasnt" to "hasn't",
            "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
            "thats" to "that's", "Thats" to "That's", "whats" to "what's", "Whats" to "What's",
            "theres" to "there's", "Theres" to "There's", "youre" to "you're", "Youre" to "You're",
            "theyre" to "they're", "Theyre" to "They're", "youve" to "you've", "Youve" to "You've",
            "Lets" to "Let's",
            // písmeno navíc / záměna m ↔ rn ↔ nm
            "somneone" to "someone", "Somneone" to "Someone", "sorneone" to "someone", "Sorneone" to "Someone",
            "somnething" to "something", "sornething" to "something", "Sornething" to "Something",
            "tirne" to "time", "corne" to "come", "Corne" to "Come", "rnore" to "more", "rnoney" to "money",
            "frorn" to "from", "Frorn" to "From", "sorne" to "some", "Sorne" to "Some",
            "nmy" to "my", "rny" to "my", "Rny" to "My"
        ).map { (z, na) -> Regex("(?<![\\p{L}'])" + Regex.escape(z) + "(?![\\p{L}'])") to na }

        fun upravSlang(text: String): String {
            var t = text
            // nula uprostřed/na konci slova je písmeno o ("to0" → "too", "s0" → "so", "y0u" → "you")
            t = t.replace(Regex("(?<=\\p{L})0|(?<![\\p{L}\\p{N}])0(?=\\p{L})"), "o")
            for ((r, na) in OCR) t = r.replace(t, na)
            for ((r, na) in SLANG) t = r.replace(t, na)
            // "huntin'", "nothin'" → "hunting", "nothing"
            t = t.replace(Regex("(?<=\\p{L}{2})in'(?![\\p{L}])"), "ing")
            return t
        }
    }
}
