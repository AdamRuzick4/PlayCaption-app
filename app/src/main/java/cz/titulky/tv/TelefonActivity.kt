package cz.titulky.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import android.widget.SeekBar
import android.widget.Switch
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PlayCaption na telefonu: černá obrazovka, na ní velkým písmem překlad toho, co se ve hře říká.
 * Telefon stojí u televize, displej nezhasíná. Zavřením obrazovky se spojení s PS5 ukončí.
 */
class TelefonActivity : Activity() {

    private data class UryvekTts(
        val input: String,
        val output: String,
        val text: String,
        val sourceKey: String,
        val sourceId: Long,
        val eventVersion: Long,
        val eventAtMs: Long,
        val queuedAtMs: Long,
        val story: Boolean,
        val utteranceId: String
    )
    private data class TtsReadMemory(val sourceKey: String, val output: String, val timeMs: Long, val story: Boolean, val sourceId: Long)

    private val ui = Handler(Looper.getMainLooper())
    private val nast by lazy { Nastaveni(this) }
    private fun t(klic: String) = Texty.t(nast.jazykNeboCs, klic)
    private val televize by lazy { TvProfily(this) }
    private val tvPrefs get() = TvProfily.nastaveni(this)
    private val prefs by lazy { getSharedPreferences("gamelingo", MODE_PRIVATE) }

    private lateinit var titulek: TextView
    private lateinit var obalTitulku: FrameLayout
    private lateinit var posunUprav: android.widget.ScrollView
    private lateinit var napoveda: TextView
    private lateinit var stav: TextView
    private lateinit var tecka: View
    private lateinit var tvTlacitko: LinearLayout
    private lateinit var segTv: TextView
    private lateinit var segTel: TextView
    private lateinit var tip: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitGeneration = 0
    private val frontaPredcitani = ArrayDeque<UryvekTts>()
    private val nedavnoPredcitane = ArrayDeque<TtsReadMemory>()
    private val vyreseneVerzeTts = LinkedHashSet<Long>()
    private var pravePredcita = false
    private var aktualniUryvek: UryvekTts? = null
    private var ttsAktualniStartMs = 0L
    private var ttsAktualniRychlost = 1f
    private var hlasZapnut: Boolean
        get() = prefs.getBoolean("hlasTitulkU", false)
        set(v) { prefs.edit().putBoolean("hlasTitulkU", v).apply() }
    private var rychlostZakladni: Float
        get() = prefs.getFloat("rychlostCteni", 1.0f).coerceIn(0.5f, 2.0f)
        set(v) { prefs.edit().putFloat("rychlostCteni", v.coerceIn(0.5f, 2.0f)).apply() }
    private var posledniVerzeTts = -1L
    private val ttsUdalostHandler: () -> Unit = {
        ui.post {
            if (!isFinishing && !isDestroyed) zpracujTtsAktualni()
        }
    }
    private var hlasTlacitko: TextView? = null
    private lateinit var horniPanel: View
    /** Tlačítko ⇄ Xbox / ⇄ PS5 (jen když jsou zapnuté obě konzole). */
    private var prepnoutKonzoli: TextView? = null
    private var obnovRezimTlacitek: (() -> Unit)? = null
    /** Na šířku (jen titulky v telefonu): malá zelená tečka „Titulky aktivní“ v rohu. */
    private lateinit var sirkaZnacka: LinearLayout
    private lateinit var sirkaTecka: View
    /** Telefon je otočený na šířku a titulky se ukazují jen v telefonu → černá obrazovka jen s titulky. */
    private var naSirku = false

    /** Velikosti písma – ťuknutím na text se přepínají. */
    private val VELIKOSTI = floatArrayOf(28f, 36f, 46f)
    private var velikost: Int
        get() = prefs.getInt("telefonPismo", 1).coerceIn(0, VELIKOSTI.size - 1)
        set(v) = prefs.edit().putInt("telefonPismo", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Titulky do televize (obrazovka s posuvníky) → jen na výšku.
        // Titulky jen v telefonu → jde otočit na šířku (černá obrazovka jen s titulky).
        // Otočení obrazovku nestaví znovu (configChanges v manifestu) → spojení se nepřeruší.
        nastavOtaceni(Propojeni.Vysilac.cilIp != null)
        Pady.hlidej(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) TvProfily.lokalne()
        rucneJenTelefon = televize.pouzeTelefon
        Volby.nacti(this)
        OblastTitulku.nacti(this)
        // televize musí znát polohu rámečku voleb (český seznam ukazuje vedle něj)
        Propojeni.Vysilac.trvaleOblast = Volby.zapnuto && Volby.ukazatNaTv
        Propojeni.Vysilac.upravujiOblast(Volby.oblast, ukazat = false)
        postav()
        TelefonTitulky.onTtsEvent = ttsUdalostHandler
        inicializujTts()
        // spuštěno rovnou na šířku (jen telefon) → hned černá obrazovka s titulky
        prepniSirku(resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            Propojeni.Vysilac.cilIp == null)
        // spustit spojení s PS5 (bez kreslení přes jiné aplikace)
        RemotePlayService.pozastaveno = false
        TelefonTitulky.odpojeno = false
        // obrazovka jen postavená znovu (savedInstanceState) → spojení s televizí nechat, jak je
        if (savedInstanceState == null) {
            TelefonTitulky.nastav("", 0)
            TelefonTitulky.napoveda = ""
            // televize, která čeká na titulky z telefonu, se najde a připojí sama
            Propojeni.Vysilac.nastavCil(null, "")
        }
        obnovTvTlacitko()
        ui.postDelayed(hledaniTv, 500)
        // převzít PS5 – když je připojená televize, sama se odpojí
        runCatching { startForegroundService(Intent(this, RemotePlayService::class.java).putExtra(RemotePlayService.EXTRA_PREVZIT, true)) }
    }

    private fun inicializujTts() {
        val vybranyEngine = prefs.getString("ttsEnginePackage", "").orEmpty()
        val generace = ++ttsInitGeneration
        ttsReady = false
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(vybranyEngine)
        if (vybranyEngine.isNotBlank() && packageManager.resolveService(intent, 0) == null) {
            prepniNaVychoziTts("Vybraný TTS engine už není dostupný; používám výchozí engine Androidu")
            return
        }
        val listener = TextToSpeech.OnInitListener { status ->
            // Odložit callback, aby už bylo pole tts přiřazené i na enginech s rychlou inicializací.
            ui.post {
                if (generace != ttsInitGeneration) return@post
                if (status != TextToSpeech.SUCCESS) {
                    if (vybranyEngine.isNotBlank()) {
                        prepniNaVychoziTts("Vybraný TTS engine se nespustil; používám výchozí engine Androidu")
                    } else {
                        ttsReady = false
                        Zaznam.zapisTts(this, "ERROR: výchozí TTS engine se nespustil")
                    }
                    return@post
                }
                val engine = tts ?: return@post
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    private fun dokoncit(utteranceId: String?, vysledek: String) {
                        val aktualniId = aktualniUryvek?.utteranceId
                        if (utteranceId == null || utteranceId != aktualniId) {
                            Zaznam.zapisTts(this@TelefonActivity,
                                "STALE_CALLBACK result=$vysledek utterance=${utteranceId ?: "none"} active=${aktualniId ?: "none"}")
                            return
                        }
                        dokonciTtsUryvek(vysledek, utteranceId)
                    }
                    override fun onStart(utteranceId: String?) {
                        ui.post {
                        val polozka = aktualniUryvek
                        if (polozka != null && polozka.utteranceId == utteranceId) {
                            ttsAktualniStartMs = android.os.SystemClock.elapsedRealtime()
                            zapisRozhodnutiTts("PLAYING", "Android TTS potvrdil start", polozka.input,
                            polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
                        }
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        dokoncit(utteranceId, "DONE")
                    }
                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) {
                        dokoncit(utteranceId, "ERROR Android TTS")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        dokoncit(utteranceId, "ERROR Android TTS code=$errorCode")
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        dokoncit(utteranceId, "STOP interrupted=$interrupted")
                    }
                })
                val vysledek = engine.setLanguage(Locale.forLanguageTag("cs-CZ"))
                if (vysledek == TextToSpeech.LANG_MISSING_DATA || vysledek == TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (vybranyEngine.isNotBlank()) {
                        prepniNaVychoziTts("Vybraný engine nemá češtinu; používám výchozí engine Androidu")
                    } else {
                        ttsReady = false
                        android.widget.Toast.makeText(this, "Český hlas TTS není dostupný", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    ttsReady = true
                    val nazev = vybranyEngine.ifBlank {
                        runCatching { engine.defaultEngine }.getOrNull().orEmpty().ifBlank { "výchozí Android" }
                    }
                    val verzeAplikace = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                    Zaznam.zapisTts(this, "READY app=$verzeAplikace; engine=$nazev; locale=cs-CZ")
                    if (hlasZapnut) zpracujTtsAktualni(force = true)
                }
            }
        }
        val novyEngine = runCatching {
            if (vybranyEngine.isBlank()) TextToSpeech(this, listener)
            else TextToSpeech(this, listener, vybranyEngine)
        }.getOrNull()
        if (novyEngine == null) {
            if (vybranyEngine.isNotBlank()) {
                ttsInitGeneration++
                prefs.edit().remove("ttsEnginePackage").apply()
                android.widget.Toast.makeText(this, "TTS engine se nepodařilo otevřít; používám výchozí engine Androidu", android.widget.Toast.LENGTH_LONG).show()
                ui.post { inicializujTts() }
            } else {
                ttsReady = false
                Zaznam.zapisTts(this, "ERROR: nepodařilo se vytvořit TTS engine")
            }
        } else {
            tts = novyEngine
        }
    }

    private fun prepniNaVychoziTts(zprava: String) {
        ttsInitGeneration++
        ttsReady = false
        tts?.shutdown()
        tts = null
        prefs.edit().remove("ttsEnginePackage").apply()
        android.widget.Toast.makeText(this, zprava, android.widget.Toast.LENGTH_LONG).show()
        inicializujTts()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(obnova)
        ui.post(obnova)
    }

    override fun onPause() {
        ui.removeCallbacks(obnova)
        super.onPause()
    }

    // ---------------------------------------------------------------- automatické hledání televize

    /** Uživatel ručně zvolil "jen telefon" → automaticky do televize neposílat. */
    private var rucneJenTelefon = false
    @Volatile private var hledam = false

    /**
     * Dokud se titulky neposílají do televize, každých 8 s se podívat, jestli v síti nečeká
     * televize v režimu "Titulky čte telefon". Jedna → připojit sama. Víc → nabídnout výběr
     * (jednou). Poslední vybraná televize má přednost.
     */
    private var nabidnutVyber = true
    private val hledaniTv: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (!rucneJenTelefon && Propojeni.Vysilac.cilIp == null && !hledam) {
                hledam = true
                Propojeni.hledej(this@TelefonActivity, 2500) { seznam ->
                    ui.post {
                        hledam = false
                        if (isFinishing || rucneJenTelefon || Propojeni.Vysilac.cilIp != null) return@post
                        val tv = televize.automaticky(seznam)
                        when {
                            tv != null -> pripojTv(tv)
                            seznam.size > 1 && nabidnutVyber -> { nabidnutVyber = false; vyberTelevizi() }
                        }
                    }
                }
            }
            ui.postDelayed(this, 8000)
        }
    }

    private fun pripojTv(tv: Propojeni.Televize, platforma: TvPlatforma? = null) {
        ui.removeCallbacks(konecUprav)
        Propojeni.Vysilac.nastavCil(null, "")
        televize.zapamatuj(tv.id, tv.jmeno, tv.ip, platforma)
        televize.vyber(tv.id)
        Volby.nacti(this)
        OblastTitulku.nacti(this)
        nactiRozvrh()
        Propojeni.Vysilac.nastavRozvrh(rozvrh.copyOf())
        Propojeni.Vysilac.trvaleOblast = Volby.zapnuto && Volby.ukazatNaTv
        Propojeni.Vysilac.upravujiOblast(Volby.oblast, ukazat = false)
        Propojeni.Vysilac.nastavCil(tv.ip, tv.jmeno)
        obnovUpravy()
        android.widget.Toast.makeText(this, t("tvPosilam").replace("{tv}", tv.jmeno), android.widget.Toast.LENGTH_LONG).show()
        obnovTvTlacitko()
    }

    /**
     * Telefon odložený (zamčený) → za 1 min spojení s PS5 ukončit.
     * Zamčený telefon totiž nedostane zprávu "PS5 si bere televize" (Wi-Fi spí) a držel by PS5
     * dál – televize by se pak nemohla připojit ("already in use").
     */
    private val odlozeno = Runnable {
        if (!isFinishing) finish()
    }

    /** PlayCaption je v pozadí (foťák, jiná aplikace…). */
    private var vPozadi = false

    /**
     * Odpojit se jen když je telefon ZAMČENÝ (displej zhasl) – ne když je jen otevřená jiná
     * aplikace (třeba foťák při natáčení videa). Zhasne displej → za 1 min konec.
     */
    private val displej = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> if (vPozadi) { ui.removeCallbacks(odlozeno); ui.postDelayed(odlozeno, 60_000) }
                Intent.ACTION_SCREEN_ON -> ui.removeCallbacks(odlozeno)
            }
        }
    }
    private var displejHlidam = false

    override fun onStart() {
        super.onStart()
        vPozadi = false
        ui.removeCallbacks(odlozeno)
        if (!displejHlidam) runCatching {
            registerReceiver(displej, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            })
            displejHlidam = true
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !isFinishing) {
            vPozadi = true
            // displej už nesvítí (telefon zamčený) → za 1 min konec; svítí (jiná aplikace) → čekat na zhasnutí
            val sviti = runCatching { (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive }.getOrDefault(false)
            if (!sviti) ui.postDelayed(odlozeno, 60_000)
        }
        super.onStop()
    }

    /**
     * Zpět (tlačítko / gesto) dřív aplikaci zavřelo a tím i ukončilo spojení s PS5/Xboxem.
     * Teď ji jen schová do pozadí – titulky běží dál. Ukončit jde tlačítkem v aplikaci.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (RemotePlayService.aktivni) moveTaskToBack(true)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        if (displejHlidam) { runCatching { unregisterReceiver(displej) }; displejHlidam = false }
        if (isFinishing) zastav()
        if (TelefonTitulky.onTtsEvent === ttsUdalostHandler) TelefonTitulky.onTtsEvent = null
        ttsInitGeneration++
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ttsReady = false
        super.onDestroy()
    }

    private fun zastav() {
        // televizi říct, ať titulek schová, a přestat jí posílat
        Propojeni.Vysilac.zmena("", 0, "")
        Propojeni.Vysilac.nastavCil(null, "")
        TvProfily.lokalne()
        runCatching {
            startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP))
        }
    }

    private fun obnovTvTlacitko() {
        obnovRezimTlacitek?.invoke()
        if (!::tvTlacitko.isInitialized) return
        // přepínač "Titulky: 📺 Televize | 📱 Telefon" – zeleně to, kde se titulky právě ukazují
        val naTv = Propojeni.Vysilac.cilIp != null
        segTv.background = zaobleni(Color.parseColor(if (naTv) "#14A23C" else "#00000000"))
        segTel.background = zaobleni(Color.parseColor(if (!naTv) "#14A23C" else "#00000000"))
        segTv.alpha = if (naTv) 1f else 0.6f
        segTel.alpha = if (!naTv) 1f else 0.6f
    }

    /** Přepínač: titulky do televize. Poslední televize (nebo jediná v síti) se připojí hned. */
    private fun prepniNaTv() {
        rucneJenTelefon = false
        televize.pouzeTelefon = false
        if (Propojeni.Vysilac.cilIp != null) { spravaTelevize(); return }
        segTv.alpha = 0.85f
        Propojeni.hledej(this, 2500) { seznam ->
            ui.post {
                if (isFinishing || rucneJenTelefon || Propojeni.Vysilac.cilIp != null) return@post
                val tv = televize.automaticky(seznam)
                when {
                    tv != null -> pripojTv(tv)
                    else -> spravaTelevize()
                }
                obnovTvTlacitko()
            }
        }
    }

    /** Přepínač: titulky jen v telefonu (televizi titulek schovat a přestat jí posílat). */
    private fun prepniNaTelefon() {
        rucneJenTelefon = true
        televize.pouzeTelefon = true
        Propojeni.Vysilac.nastavCil(null, "")
        TvProfily.lokalne()
        Volby.nacti(this)
        OblastTitulku.nacti(this)
        nactiRozvrh()
        obnovUpravy()
        obnovTvTlacitko()
    }

    private fun spravaTelevize() {
        rucneJenTelefon = true
        TvVyber(this, { tv, platforma ->
            rucneJenTelefon = false
            pripojTv(tv, platforma)
        }, { prepniNaTelefon() }).ukaz()
    }

    private fun vyberTelevizi() = spravaTelevize()

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun zaobleni(barva: Int) = GradientDrawable().apply {
        setColor(barva)
        cornerRadius = 999f * resources.displayMetrics.density
    }

    // ---------------------------------------------------------------- poloha titulků na televizi

    /** (střed X, spodní hrana Y, šířka, velikost písma) – stejné jako výchozí poloha na televizi. */
    private var rozvrh = floatArrayOf(0.5f, 0.93f, 0.62f, 1f)
    private lateinit var upravy: LinearLayout
    /** Zámeček zeleného boxu (poloha českých titulků na televizi). */
    private var zelenaZamcena: Boolean
        get() = tvPrefs.getBoolean("rozvrhZamek", false)
        set(v) = tvPrefs.edit().putBoolean("rozvrhZamek", v).apply()

    /** Kulaté tlačítko se zámečkem: zamčeno = plné barvou, odemčeno = jen obrys. */
    private fun zamek(barva: Int, zamceno: Boolean, prepni: () -> Unit) = TextView(this).apply {
        text = if (zamceno) "🔒" else "🔓"
        textSize = 20f
        gravity = Gravity.CENTER
        alpha = if (zamceno) 1f else 0.55f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (zamceno) setColor(barva) else { setColor(Color.TRANSPARENT); setStroke(dp(2), barva) }
        }
        setOnClickListener { prepni(); obnovUpravy() }
    }

    private fun nactiRozvrh() {
        rozvrh = floatArrayOf(
            tvPrefs.getFloat("tvX", 0.5f), tvPrefs.getFloat("tvY", 0.93f),
            tvPrefs.getFloat("tvSirka", 0.62f), tvPrefs.getFloat("tvPismo", 1f))
    }

    private fun ulozAPosliRozvrh(ukazat: Boolean = true) {
        tvPrefs.edit().putFloat("tvX", rozvrh[0]).putFloat("tvY", rozvrh[1])
            .putFloat("tvSirka", rozvrh[2]).putFloat("tvPismo", rozvrh[3]).apply()
        Propojeni.Vysilac.nastavRozvrh(rozvrh.copyOf())
        // na televizi ukázat ukázkový titulek, ať je vidět, kam se posunul
        if (ukazat) Propojeni.Vysilac.ukazka(t("tvUkazka"))
    }

    /**
     * Malá "televize" s polohou titulků: tažením prstem se titulky posunou,
     * posuvníky změní šířku a velikost písma. Vše se hned ukáže i na skutečné televizi.
     */
    private fun postavUpravy(): LinearLayout {
        nactiRozvrh()
        val zelena = Color.parseColor("#3CFF6E")
        val obal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(10))
            visibility = View.GONE
        }
        obal.addView(TextView(this).apply {
            text = "⚙ " + t("tvNastaveni") + " · " + Propojeni.Vysilac.cilJmeno
            textSize = 16f
            setTextColor(Color.WHITE)
            background = zaobleni(Color.parseColor("#333338"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { spravaTelevize() }
        })
        obal.addView(LgDevModeView(this) {
            TvProfily.aktivniId?.let { TvProfily(this).najdi(it) }
        })
        obal.addView(TextView(this).apply {
            text = t("tvUpravit")
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, dp(6))
        })
        val obrazovka = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1C26"))
                cornerRadius = 10 * resources.displayMetrics.density
                setStroke(dp(2), Color.parseColor("#555A78"))
            }
        }
        val box = TextView(this).apply {
            text = t("tvTitulky")
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(zelena); cornerRadius = 6 * resources.displayMetrics.density }
        }
        obrazovka.addView(box, FrameLayout.LayoutParams(10, 10))
        // žlutý šrafovaný rámeček voleb odpovědí (jen když jsou volby zapnuté)
        val ramecek = SrafovanyRamecek(this, t("volbyRamecek")).apply {
            visibility = if (Volby.zapnuto) View.VISIBLE else View.GONE
        }
        obrazovka.addView(ramecek, FrameLayout.LayoutParams(10, 10))
        // modrý šrafovaný rámeček ruční oblasti titulků (jen když je čtení titulků ručně)
        val modry = SrafovanyRamecek(this, t("titOblRamecek"), OblastTitulku.MODRA).apply {
            visibility = if (OblastTitulku.rucne) View.VISIBLE else View.GONE
        }
        obrazovka.addView(modry, FrameLayout.LayoutParams(10, 10))
        fun prekresli() {
            val w = obrazovka.width.toFloat()
            val h = obrazovka.height.toFloat()
            if (w <= 0 || h <= 0) return
            val bw = rozvrh[2] * w
            val bh = (h * 0.12f * rozvrh[3]).coerceIn(h * 0.06f, h * 0.35f)
            box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply {
                width = bw.toInt(); height = bh.toInt()
                leftMargin = (rozvrh[0] * w - bw / 2).coerceIn(0f, w - bw).toInt()
                topMargin = (rozvrh[1] * h - bh).coerceIn(0f, h - bh).toInt()
            }
            box.textSize = (9f * rozvrh[3]).coerceIn(7f, 20f)
            val o = Volby.oblast
            ramecek.layoutParams = (ramecek.layoutParams as FrameLayout.LayoutParams).apply {
                width = ((o[2] - o[0]) * w).toInt().coerceAtLeast(10); height = ((o[3] - o[1]) * h).toInt().coerceAtLeast(10)
                leftMargin = (o[0] * w).toInt(); topMargin = (o[1] * h).toInt()
            }
            val m = OblastTitulku.oblast
            modry.layoutParams = (modry.layoutParams as FrameLayout.LayoutParams).apply {
                width = ((m[2] - m[0]) * w).toInt().coerceAtLeast(10); height = ((m[3] - m[1]) * h).toInt().coerceAtLeast(10)
                leftMargin = (m[0] * w).toInt(); topMargin = (m[1] * h).toInt()
            }
        }
        // výška = 16:9 podle šířky
        obrazovka.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            val w = r - l
            val chci = w * 9 / 16
            if (v.layoutParams.height != chci) { v.layoutParams = v.layoutParams.apply { height = chci }; v.requestLayout() }
            if (w != or_ - ol) v.post { prekresli() } else prekresli()
        }
        var posledniPoslani = 0L
        // táhne se rámeček voleb (prst začal v něm), nebo titulky?
        var tahamVolby = false
        var tahamModry = false
        var zamcenyDotyk = false
        var chytX = 0f; var chytY = 0f
        obrazovka.setOnTouchListener { v, e ->
            // prst v mini televizi posouvá rámečky – stránka (ScrollView) se při tom nesmí hýbat
            if (e.action == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            val w = v.width.toFloat(); val h = v.height.toFloat()
            if (e.action == android.view.MotionEvent.ACTION_DOWN) ui.removeCallbacks(konecUprav)
            // prst puštěn → rámečky na televizi za chvilku schovat (při tažení je vidět, kam to jde)
            if (e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL)
                ui.postDelayed(konecUprav, 1000)
            if (w > 0 && h > 0 && e.action == android.view.MotionEvent.ACTION_DOWN) {
                val o = Volby.oblast
                val m = OblastTitulku.oblast
                val fx = e.x / w; val fy = e.y / h
                // přednost má modrý rámeček (titulky), pak žlutý (volby), jinak poloha českých titulků
                val vModrem = OblastTitulku.rucne && fx in m[0]..m[2] && fy in m[1]..m[3]
                val vZlutem = !vModrem && Volby.zapnuto && fx in o[0]..o[2] && fy in o[1]..o[3]
                // zamčený rámeček se nehne (a prst v něm nepohne ani ničím jiným)
                zamcenyDotyk = (vModrem && OblastTitulku.zamceno) || (vZlutem && Volby.zamceno) ||
                    (!vModrem && !vZlutem && zelenaZamcena)
                tahamModry = vModrem && !OblastTitulku.zamceno
                tahamVolby = vZlutem && !Volby.zamceno
                if (tahamModry) { chytX = fx - m[0]; chytY = fy - m[1] } else { chytX = fx - o[0]; chytY = fy - o[1] }
            }
            if (zamcenyDotyk) {
                // nic – rámeček je zamčený
            } else if (w > 0 && h > 0 && tahamModry) {
                val m = OblastTitulku.oblast.copyOf()
                val mw = m[2] - m[0]; val mh = m[3] - m[1]
                m[0] = (e.x / w - chytX).coerceIn(0f, 1f - mw); m[1] = (e.y / h - chytY).coerceIn(0f, 1f - mh)
                m[2] = m[0] + mw; m[3] = m[1] + mh
                OblastTitulku.oblast = m
                prekresli()
                val ted = System.currentTimeMillis()
                if (e.action == android.view.MotionEvent.ACTION_UP || ted - posledniPoslani > 150) {
                    posledniPoslani = ted
                    ulozAPosliOblastTitulku()
                }
            } else if (w > 0 && h > 0 && tahamVolby) {
                val o = Volby.oblast.copyOf()
                val ow = o[2] - o[0]; val oh = o[3] - o[1]
                o[0] = (e.x / w - chytX).coerceIn(0f, 1f - ow); o[1] = (e.y / h - chytY).coerceIn(0f, 1f - oh)
                o[2] = o[0] + ow; o[3] = o[1] + oh
                Volby.oblast = o
                prekresli()
                val ted = System.currentTimeMillis()
                if (e.action == android.view.MotionEvent.ACTION_UP || ted - posledniPoslani > 150) {
                    posledniPoslani = ted
                    ulozAPosliVolby()
                }
            } else if (w > 0 && h > 0) {
                rozvrh[0] = (e.x / w).coerceIn(0f, 1f)
                // prst je uprostřed boxu → spodní hrana o půl boxu níž
                val pulBoxu = (0.06f * rozvrh[3]).coerceIn(0.03f, 0.17f)
                rozvrh[1] = (e.y / h + pulBoxu).coerceIn(0.1f, 1f)
                prekresli()
                val ted = System.currentTimeMillis()
                if (e.action == android.view.MotionEvent.ACTION_UP || ted - posledniPoslani > 150) {
                    posledniPoslani = ted
                    ulozAPosliRozvrh()
                }
            }
            true
        }
        obal.addView(obrazovka, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        fun posuvnik(popis: String, od: Int, doo: Int, hodnota: () -> Int, nastav: (Int) -> Unit, posli: () -> Unit = { ulozAPosliRozvrh() }) {
            val radek = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val stitek = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE) }
            fun popisek() { stitek.text = "$popis ${hodnota()} %" }
            popisek()
            radek.addView(stitek, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
            radek.addView(android.widget.SeekBar(this).apply {
                max = doo - od
                progress = hodnota() - od
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, odUzivatele: Boolean) {
                        if (!odUzivatele) return
                        nastav(p + od); popisek(); prekresli()
                        // posílat do televize už během posouvání (max ~6× za sekundu), ať je změna hned vidět
                        val ted = System.currentTimeMillis()
                        if (ted - posledniPoslani > 150) { posledniPoslani = ted; posli() }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) { ui.removeCallbacks(konecUprav) }
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) { posli(); ui.postDelayed(konecUprav, 1000) }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            obal.addView(radek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // šedá nápověda pod tlačítkem (mění se podle stavu)
        fun sedaNapoveda(text: String) = obal.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(4), dp(6), 0, 0)
        })

        // ---- poloha českých titulků (zelený box) + zámeček
        val radekZeleny = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        radekZeleny.addView(TextView(this).apply {
            text = t("zelenaPoloha")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(zelena) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        radekZeleny.addView(zamek(zelena, zelenaZamcena) { zelenaZamcena = !zelenaZamcena },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(10) })
        obal.addView(radekZeleny, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        sedaNapoveda(if (zelenaZamcena) t("zelenaNapZamceno") else t("zelenaNap"))
        if (!zelenaZamcena) {
            posuvnik(t("tvSirka"), 20, 100, { (rozvrh[2] * 100).toInt() }, { rozvrh[2] = it / 100f })
            posuvnik(t("tvPismo"), 50, 250, { (rozvrh[3] * 100).toInt() }, { rozvrh[3] = it / 100f })
        }

        // ---- čtení titulků: automaticky / ručně (modrý rámeček) + zámeček vedle
        val radekModry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        radekModry.addView(TextView(this).apply {
            text = if (OblastTitulku.rucne) t("titOblZap") else t("titOblVyp")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (OblastTitulku.rucne) Color.BLACK else OblastTitulku.MODRA)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (OblastTitulku.rucne) setColor(OblastTitulku.MODRA) else { setColor(Color.TRANSPARENT); setStroke(dp(2), OblastTitulku.MODRA) }
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                OblastTitulku.rucne = !OblastTitulku.rucne
                OblastTitulku.uloz(this@TelefonActivity)
                if (OblastTitulku.rucne) { ulozAPosliOblastTitulku(); ui.removeCallbacks(konecUprav); ui.postDelayed(konecUprav, 3000) }
                else Propojeni.Vysilac.oblastTitulku(OblastTitulku.oblast, ukazat = false)
                obnovUpravy()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (OblastTitulku.rucne) {
            // zámeček: zamčeno = svítí modře, rámeček nejde posunout
            radekModry.addView(zamek(OblastTitulku.MODRA, OblastTitulku.zamceno) {
                OblastTitulku.zamceno = !OblastTitulku.zamceno
                OblastTitulku.uloz(this@TelefonActivity)
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(10) })
        }
        obal.addView(radekModry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        sedaNapoveda(when {
            !OblastTitulku.rucne -> t("titOblNapAuto")
            OblastTitulku.zamceno -> t("titOblNapZamceno")
            else -> t("titOblNavod")
        })
        if (OblastTitulku.rucne && !OblastTitulku.zamceno) {
            fun zmenModry(sirka: Float?, vyska: Float?) {
                val o = OblastTitulku.oblast.copyOf()
                if (sirka != null) {
                    val cx = (o[0] + o[2]) / 2
                    o[0] = (cx - sirka / 2).coerceIn(0f, 1f - sirka); o[2] = o[0] + sirka
                }
                if (vyska != null) {
                    val cy = (o[1] + o[3]) / 2
                    o[1] = (cy - vyska / 2).coerceIn(0f, 1f - vyska); o[3] = o[1] + vyska
                }
                OblastTitulku.oblast = o
            }
            posuvnik(t("titOblSirka"), 20, 100, { ((OblastTitulku.oblast[2] - OblastTitulku.oblast[0]) * 100).toInt() },
                { zmenModry(it / 100f, null) }, { ulozAPosliOblastTitulku() })
            posuvnik(t("titOblVyska"), 5, 60, { ((OblastTitulku.oblast[3] - OblastTitulku.oblast[1]) * 100).toInt() },
                { zmenModry(null, it / 100f) }, { ulozAPosliOblastTitulku() })
        }

        // ---- volby odpovědí: vypínač + zámeček + velikost žlutého rámečku
        val radekZluty = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        radekZluty.addView(TextView(this).apply {
            text = if (Volby.zapnuto) t("volbyZap") else t("volbyVyp")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (Volby.zapnuto) Color.BLACK else Volby.ZLUTA)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (Volby.zapnuto) setColor(Volby.ZLUTA) else { setColor(Color.TRANSPARENT); setStroke(dp(2), Volby.ZLUTA) }
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                Volby.zapnuto = !Volby.zapnuto
                Volby.uloz(this@TelefonActivity)
                Propojeni.Vysilac.trvaleOblast = Volby.zapnuto && Volby.ukazatNaTv
                if (Volby.zapnuto) { ulozAPosliVolby(); ui.removeCallbacks(konecUprav); ui.postDelayed(konecUprav, 3000) }
                else {
                    Propojeni.Vysilac.upravujiOblast(Volby.oblast, ukazat = false)
                    Propojeni.Vysilac.nastavVolby(emptyList(), -1)
                }
                obnovUpravy()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Volby.zapnuto) radekZluty.addView(zamek(Volby.ZLUTA, Volby.zamceno) {
            Volby.zamceno = !Volby.zamceno
            Volby.uloz(this@TelefonActivity)
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(10) })
        obal.addView(radekZluty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        sedaNapoveda(when {
            !Volby.zapnuto -> t("volbyNapVyp")
            Volby.zamceno -> t("volbyNapZamceno")
            else -> t("volbyNavod")
        })
        // režim žlutého rámečku: Volby (seznam odpovědí) / Text (dopis, SMS, popis úkolu jako celek)
        if (Volby.zapnuto) {
            val radekRezim = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.parseColor("#333338")) }
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            fun rezim(popis: String, text: Boolean) = TextView(this).apply {
                this.text = popis
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val zvoleno = Volby.rezimText == text
                setTextColor(if (zvoleno) Color.BLACK else Color.WHITE)
                alpha = if (zvoleno) 1f else 0.6f
                background = GradientDrawable().apply { cornerRadius = 999f; setColor(if (zvoleno) Volby.ZLUTA else Color.TRANSPARENT) }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    if (Volby.rezimText != text) {
                        Volby.rezimText = text
                        Volby.uloz(this@TelefonActivity)
                        Propojeni.Vysilac.nastavVolby(emptyList(), -1)
                        obnovUpravy()
                    }
                }
            }
            radekRezim.addView(rezim(t("rezimVolby"), false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            radekRezim.addView(rezim(t("rezimText"), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            obal.addView(radekRezim, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            sedaNapoveda(if (Volby.rezimText) t("rezimTextNap") else t("rezimVolbyNap"))
        }
        if (Volby.zapnuto && !Volby.zamceno) {
            // ukazovat rámeček na televizi pořád (jen když chci vidět, kde je), jinak jen při posouvání
            obal.addView(TextView(this).apply {
                text = if (Volby.ukazatNaTv) t("volbyUkazatZap") else t("volbyUkazatVyp")
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (Volby.ukazatNaTv) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    if (Volby.ukazatNaTv) setColor(Volby.ZLUTA) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#888888")) }
                }
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    Volby.ukazatNaTv = !Volby.ukazatNaTv
                    Volby.uloz(this@TelefonActivity)
                    Propojeni.Vysilac.nastavTrvaleOblast(Volby.ukazatNaTv)
                    obnovUpravy()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            // šířka / výška rámečku – mění se kolem středu
            fun zmenRamecek(sirka: Float?, vyska: Float?) {
                val o = Volby.oblast.copyOf()
                if (sirka != null) {
                    val cx = (o[0] + o[2]) / 2
                    o[0] = (cx - sirka / 2).coerceIn(0f, 1f - sirka); o[2] = o[0] + sirka
                }
                if (vyska != null) {
                    val cy = (o[1] + o[3]) / 2
                    o[1] = (cy - vyska / 2).coerceIn(0f, 1f - vyska); o[3] = o[1] + vyska
                }
                Volby.oblast = o
            }
            posuvnik(t("volbySirka"), 10, 100, { ((Volby.oblast[2] - Volby.oblast[0]) * 100).toInt() },
                { zmenRamecek(it / 100f, null) }, { ulozAPosliVolby() })
            posuvnik(t("volbyVyska"), 10, 100, { ((Volby.oblast[3] - Volby.oblast[1]) * 100).toInt() },
                { zmenRamecek(null, it / 100f) }, { ulozAPosliVolby() })
        }

        obal.addView(TextView(this).apply {
            text = t("tvVychozi")
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                rozvrh = floatArrayOf(0.5f, 0.93f, 0.62f, 1f)
                ulozAPosliRozvrh(); obnovUpravy()
            }
        })
        return obal
    }

    /** Prst puštěn → zelený i žlutý rámeček na televizi schovat (pokud žlutý nemá svítit pořád). */
    private val konecUprav = Runnable {
        Propojeni.Vysilac.konecUkazky()
        Propojeni.Vysilac.konecOblasti()
        Propojeni.Vysilac.oblastTitulku(OblastTitulku.oblast, ukazat = false)
    }

    /** Uložit modrý rámeček (oblast titulků) a ukázat ho na televizi (do puštění prstu). */
    private fun ulozAPosliOblastTitulku() {
        OblastTitulku.uloz(this)
        Propojeni.Vysilac.oblastTitulku(OblastTitulku.oblast, ukazat = true)
    }

    /** Uložit rámeček voleb a ukázat ho na televizi (žlutý šrafovaný, do puštění prstu). */
    private fun ulozAPosliVolby() {
        Volby.uloz(this)
        Propojeni.Vysilac.upravujiOblast(Volby.oblast)
    }

    private fun obnovUpravy() {
        // znovu postavit (posuvníky se nastaví na nové hodnoty)
        val rodic = upravy.parent as? LinearLayout ?: return
        val i = rodic.indexOfChild(upravy)
        val viditelne = upravy.visibility
        rodic.removeView(upravy)
        upravy = postavUpravy().apply { visibility = viditelne }
        rodic.addView(upravy, i, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun postav() {
        upravy = postavUpravy()
        // horní lišta: logo, stav, Zastavit
        tecka = View(this).apply { background = zaobleni(Color.GRAY) }
        stav = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#BBBBBB"))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val zastavit = TextView(this).apply {
            text = t("zastavit")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = zaobleni(Color.parseColor("#E5091A"))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { finish() }
        }
        // kde ukazovat titulky: dvě volby vedle sebe (jedním klepnutím)
        fun segment(popis: String, akce: () -> Unit) = TextView(this).apply {
            text = popis
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { akce() }
        }
        segTv = segment(t("segTv")) { prepniNaTv() }
        segTel = segment(t("segTel")) { prepniNaTelefon() }
        tvTlacitko = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = zaobleni(Color.parseColor("#333338"))
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(segTv)
            addView(segTel)
            // podržet → seznam televizí (když jich je v síti víc)
            setOnLongClickListener { spravaTelevize(); true }
            segTv.setOnLongClickListener { spravaTelevize(); true }
        }
        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
            addView(ImageView(this@TelefonActivity).apply {
                setImageResource(R.drawable.logo)
                adjustViewBounds = true
                // podržet logo → záznam (pro hledání chyb)
                setOnLongClickListener { Zaznam.ukaz(this@TelefonActivity); true }
            }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
            // mezera – tlačítka doprava
            addView(View(this@TelefonActivity), LinearLayout.LayoutParams(0, 1, 1f))
            hlasTlacitko = TextView(this@TelefonActivity).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), dp(8), dp(7), dp(8))
                background = zaobleni(if (hlasZapnut) Color.parseColor("#16833A") else Color.parseColor("#333338"))
                setTextColor(Color.WHITE)
                text = if (hlasZapnut) "🔊 Hlas" else "🔇 Hlas"
                contentDescription = "Nastavení hlasu a rychlosti předčítání"
                setOnClickListener { ukazNastaveniPredcitani() }
            }
            addView(tvTlacitko, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(hlasTlacitko, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(zastavit)
        }

        // velký text překladu uprostřed
        titulek = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            // co nejtučnější písmo, ať je titulek výrazný
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 900, false) else Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            // zvolená velikost je horní hranice; když se dlouhá věta nevejde (hlavně na šířku), písmo se samo zmenší
            nastavVelikost(this)
            setOnClickListener {
                velikost = (velikost + 1) % VELIKOSTI.size
                nastavVelikost(this)
            }
        }
        // nápověda k ovládání (když je zapnutá)
        napoveda = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.parseColor("#FFD54A"))
            setBackgroundColor(Color.parseColor("#1E1A0C"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        // stav spojení na samostatném řádku pod lištou (jinak se text mačkal po písmenech pod sebe)
        val stavRadek = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(10))
            addView(tecka, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
            addView(stav, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // obě konzole zapnuté → přepnout (stejně jako na televizi)
            addView(TextView(this@TelefonActivity).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = zaobleni(Color.parseColor("#E5091A"))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                visibility = View.GONE
                setOnClickListener { RemotePlayService.prepni(this@TelefonActivity, naXbox = !RemotePlayService.naXboxu) }
                prepnoutKonzoli = this
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }
        val horni = LinearLayout(this).apply {
            horniPanel = this
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111114"))
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val rezimy = LinearLayout(this@TelefonActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            val tlacitka = LinearLayout(this@TelefonActivity)
            val standard = android.widget.Button(this@TelefonActivity).apply { text = "Standardní"; isAllCaps = false }
            val pribehovy = android.widget.Button(this@TelefonActivity).apply { text = "Příběhový"; isAllCaps = false }
            val casObal = LinearLayout(this@TelefonActivity).apply { orientation = LinearLayout.VERTICAL }
            val casPopis = TextView(this@TelefonActivity).apply { textSize = 14f; setTextColor(Color.WHITE) }
            val omezeniPribehu = TextView(this@TelefonActivity).apply {
                text = "Tento Android TV přijímač podporuje Standardní režim. Příběhový režim zůstává dostupný v telefonu a na podporovaných TV."
                textSize = 13f; setTextColor(Color.parseColor("#B6B6C2"))
                setPadding(0, dp(8), 0, 0)
            }
            fun obnovRezim() {
                val platforma = TvProfily.aktivniId?.let { televize.platformaPrijimace(it) } ?: TvPlatforma.NEZNAMA
                val muzePribeh = Propojeni.Vysilac.cilIp == null || PodporaPribehu.dotazovat(platforma)
                val vybranPribeh = nast.pribehovy && muzePribeh
                for ((button, vybrano) in listOf(standard to !vybranPribeh, pribehovy to vybranPribeh)) {
                    button.isSelected = vybrano
                    button.contentDescription = "${button.text} režim" + if (vybrano) ", vybráno" else ""
                    button.typeface = Typeface.DEFAULT_BOLD
                    button.minHeight = dp(48)
                    button.setTextColor(if (vybrano) Color.BLACK else Color.WHITE)
                    button.background = zaobleni(Color.parseColor(if (vybrano) "#3CFF6E" else "#303038")).apply {
                        setStroke(dp(2), Color.parseColor(if (vybrano) "#3CFF6E" else "#737380"))
                    }
                    button.elevation = dp(2).toFloat()
                }
                pribehovy.isEnabled = muzePribeh
                pribehovy.alpha = if (muzePribeh) 1f else 0.5f
                omezeniPribehu.visibility = if (muzePribeh) View.GONE else View.VISIBLE
                casObal.visibility = if (vybranPribeh) View.VISIBLE else View.GONE
            }
            standard.setOnClickListener { nast.pribehovy = false; obnovRezim() }
            pribehovy.setOnClickListener { nast.pribehovy = true; obnovRezim() }
            tlacitka.addView(standard, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
            tlacitka.addView(pribehovy, LinearLayout.LayoutParams(0, dp(48), 1f))
            fun obnovCas() { casPopis.text = "Délka zobrazení: ${nast.casPribehuProcenta} %" }
            obnovCas()
            casObal.addView(casPopis)
            casObal.addView(android.widget.SeekBar(this@TelefonActivity).apply {
                max = (CasovaniPribehu.MAX_PROCENT - CasovaniPribehu.MIN_PROCENT) / 5
                progress = (nast.casPribehuProcenta - CasovaniPribehu.MIN_PROCENT) / 5
                contentDescription = "Délka zobrazení příběhových titulků v procentech"
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, odUzivatele: Boolean) {
                        if (odUzivatele) { nast.casPribehuProcenta = CasovaniPribehu.MIN_PROCENT + p * 5; obnovCas() }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            casObal.addView(TextView(this@TelefonActivity).apply {
                text = "100 % = výchozí čas · méně % = kratší zobrazení. Platí od další stránky."
                textSize = 12f; setTextColor(Color.parseColor("#B6B6C2"))
            })
            rezimy.addView(tlacitka)
            rezimy.addView(omezeniPribehu)
            rezimy.addView(casObal)
            obnovRezimTlacitek = ::obnovRezim
            obnovRezim()
            addView(rezimy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stavRadek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        tip = TextView(this).apply {
            text = t("telefonTip")
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, dp(6), 0, dp(10))
        }

        val koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(horni, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            obalTitulku = FrameLayout(this@TelefonActivity).apply {
                addView(titulek, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // na šířku: malá zelená tečka v rohu, ať to neruší
                sirkaTecka = View(this@TelefonActivity).apply { background = zaobleni(Color.parseColor("#3CFF6E")) }
                sirkaZnacka = LinearLayout(this@TelefonActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    alpha = 0.6f
                    visibility = View.GONE
                    addView(sirkaTecka, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
                    addView(TextView(this@TelefonActivity).apply {
                        text = t("titulkyAktivni")
                        textSize = 11f
                        setTextColor(Color.parseColor("#3CFF6E"))
                    })
                }
                addView(sirkaZnacka, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END).apply { topMargin = dp(10); rightMargin = dp(16) })
            }
            addView(obalTitulku, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // nastavení (mini televize, rámečky, posuvníky) jde posouvat, ať se vejde i na menší telefon
            posunUprav = android.widget.ScrollView(this@TelefonActivity).apply {
                visibility = View.GONE
                addView(LinearLayout(this@TelefonActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(upravy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            addView(posunUprav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(napoveda, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(koren)
    }

    // ---------------------------------------------------------------- na šířku (jen titulky v telefonu)

    /** Do televize → jen na výšku. Jen telefon → otáčí se podle telefonu. */
    private fun nastavOtaceni(doTv: Boolean) {
        val chci = if (doTv) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                   else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        runCatching { if (requestedOrientation != chci) requestedOrientation = chci }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        prepniSirku(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            Propojeni.Vysilac.cilIp == null)
    }

    /** Na šířku: černá obrazovka jen s titulky přes celou šířku + malá zelená tečka. */
    private fun prepniSirku(ano: Boolean) {
        if (!::horniPanel.isInitialized || naSirku == ano) return
        naSirku = ano
        horniPanel.visibility = if (ano) View.GONE else View.VISIBLE
        tip.visibility = if (ano || Propojeni.Vysilac.cilIp != null) View.GONE else View.VISIBLE
        if (ano) napoveda.visibility = View.GONE
        sirkaZnacka.visibility = if (ano) View.VISIBLE else View.GONE
        titulek.setPadding(dp(if (ano) 32 else 20), dp(20), dp(if (ano) 32 else 20), dp(20))
        // schovat horní a spodní lištu systému (hodiny, tlačítka) – přejetím od kraje se ukážou
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val c = window.insetsController
                if (ano) {
                    c?.hide(android.view.WindowInsets.Type.systemBars())
                    c?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else c?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (ano)
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                else View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    /** Jméno postavy ("Vivian:") v barvě jako ve hře, zbytek bíle. */
    private fun nastavHlasZapnut(zapnuto: Boolean) {
        hlasZapnut = zapnuto
        hlasTlacitko?.apply {
            text = if (hlasZapnut) "🔊 Hlas" else "🔇 Hlas"
            background = zaobleni(if (hlasZapnut) Color.parseColor("#16833A") else Color.parseColor("#333338"))
        }
        if (!hlasZapnut) {
            while (frontaPredcitani.isNotEmpty()) {
                val polozka = frontaPredcitani.removeFirst()
                zapisRozhodnutiTts("DROP", "uživatel vypnul předčítání", polozka.input,
                    polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
            }
            aktualniUryvek?.let { polozka ->
                zapisRozhodnutiTts("STOP", "uživatel vypnul předčítání během přehrávání", polozka.input,
                    polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
            }
            pravePredcita = false
            aktualniUryvek = null
            ttsAktualniStartMs = 0L
            tts?.stop()
        } else zpracujTtsAktualni(force = true)
    }

    private fun ukazNastaveniPredcitani() {
        val prepinac = Switch(this).apply {
            text = "Předčítání hlasem"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = hlasZapnut
            setOnCheckedChangeListener { _, zapnuto -> nastavHlasZapnut(zapnuto) }
        }
        val popis = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val posuvnik = SeekBar(this).apply {
            max = 150
            progress = ((rychlostZakladni - 0.5f) * 100f).toInt().coerceIn(0, max)
        }
        fun obnov(hodnota: Float) {
            rychlostZakladni = hodnota.coerceIn(0.5f, 2.0f)
            popis.text = "Rychlost předčítání: ${String.format(Locale.getDefault(), "%.1f", rychlostZakladni)}×"
        }
        posuvnik.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                obnov(0.5f + progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val obsah = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
            addView(prepinac)
            addView(TextView(this@TelefonActivity).apply {
                text = "Ruční rychlost lze nastavit od 0,5× do 2,0×. Dohánění násobí zvolenou rychlost až 2× (nejvýše 3,0×). Tempo se mění při začátku dalšího úryvku; po dohnání se vrací na slider. Standardní režim může vypustit příliš staré titulky."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(10), 0, dp(4))
            })
            addView(popis)
            addView(posuvnik)
            addView(TextView(this@TelefonActivity).apply {
                text = "Zobrazit a exportovat TTS diagnostiku"
                textSize = 14f
                setTextColor(Color.parseColor("#8FC7FF"))
                setPadding(0, dp(12), 0, dp(4))
                isFocusable = true
                setOnClickListener { Zaznam.ukaz(this@TelefonActivity) }
            })
        }
        obnov(rychlostZakladni)
        val scroll = android.widget.ScrollView(this).apply { addView(obsah) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nastavení hlasu")
            .setView(scroll)
            .setPositiveButton("Uložit", null)
            .show()
    }

    /** Do TTS pustí až konečný překlad a spojí jej s anglickým OCR vstupem. */
    private fun zpracujTtsAktualni(force: Boolean = false) {
        val verze = TelefonTitulky.verzeTtsUdalosti
        if (!force && verze == posledniVerzeTts) return
        posledniVerzeTts = verze
        val text = TelefonTitulky.textProTts.replace(Regex("\\s+"), " ").trim()
        val vstup = TelefonTitulky.vstupOcr
        val idOcr = TelefonTitulky.idOcr
        val deduplikovat = TelefonTitulky.deduplikovatTts
        val casUdalosti = TelefonTitulky.casTtsUdalostiMs.takeIf { it > 0L }
            ?: android.os.SystemClock.elapsedRealtime()
        if (verze in vyreseneVerzeTts) {
            zapisRozhodnutiTts("DROP", "duplicitní zpracování stejné události v#$verze", vstup, text, idOcr, casUdalosti)
            return
        }
        if (text.isBlank()) {
            vyresTtsVerzi(verze)
            zapisRozhodnutiTts("SKIP", "prázdný výstup", vstup, text, idOcr, casUdalosti)
            return
        }
        if (!TelefonTitulky.pripravenoProTts) {
            vyresTtsVerzi(verze)
            zapisRozhodnutiTts("SKIP", "překlad je jen náhled, není dokončený", vstup, text, idOcr, casUdalosti)
            return
        }
        if (!hlasZapnut) {
            zapisRozhodnutiTts("SKIP", "hlas je vypnutý", vstup, text, idOcr, casUdalosti)
            return
        }
        if (!ttsReady) {
            zapisRozhodnutiTts("WAIT", "TTS engine ještě není připraven", vstup, text, idOcr, casUdalosti)
            return
        }
        val zpozdeni = if (deduplikovat) 40L else 0L
        if (zpozdeni > 0) zapisRozhodnutiTts("WAIT", "stabilizace ${zpozdeni}ms", vstup, text, idOcr, casUdalosti)
        val runnable = Runnable {
            if (!hlasZapnut) {
                zapisRozhodnutiTts("SKIP", "hlas se vypnul před zařazením", vstup, text, idOcr, casUdalosti)
                return@Runnable
            }
            if (!ttsReady) {
                zapisRozhodnutiTts("WAIT", "TTS engine není připraven při zařazení", vstup, text, idOcr, casUdalosti)
                return@Runnable
            }
            val aktualniVstup = TelefonTitulky.vstupOcr
            if (deduplikovat && TelefonTitulky.verzeTtsUdalosti != verze &&
                TtsRevisionPolicy.relation(vstup, aktualniVstup, false) in
                    setOf(TtsRevisionPolicy.Relation.SAME, TtsRevisionPolicy.Relation.EXTENDED)) {
                vyresTtsVerzi(verze)
                zapisRozhodnutiTts("DROP", "před zařazením přišla novější OCR verze stejného titulku", vstup, text, idOcr, casUdalosti)
                return@Runnable
            }
            vlozDoFronty(text, vstup, idOcr, verze, casUdalosti, deduplikovat)
        }
        if (zpozdeni == 0L) ui.post(runnable) else ui.postDelayed(runnable, zpozdeni)
    }

    private fun vyresTtsVerzi(verze: Long) {
        vyreseneVerzeTts.add(verze)
        while (vyreseneVerzeTts.size > 96) vyreseneVerzeTts.remove(vyreseneVerzeTts.first())
    }

    private fun bezJmena(text: String): String = text.lines().joinToString(" ") {
        it.replace(Regex("^[A-Z][A-Za-z0-9 .'-]{1,24}:\\s*"), "").trim()
    }.replace(Regex("\\s+"), " ").trim()

    private fun UryvekTts.proKontrolu() = TtsRevisionPolicy.Peer(sourceKey, bezJmena(output), text, story)

    private fun jizCteneProKontrolu(): List<TtsRevisionPolicy.Peer> {
        val ted = android.os.SystemClock.elapsedRealtime()
        val vysledek = mutableListOf<TtsRevisionPolicy.Peer>()
        aktualniUryvek?.let { vysledek += it.proKontrolu() }
        nedavnoPredcitane.reversed().filter { ted - it.timeMs in 0L..12_000L }.forEach {
            vysledek += TtsRevisionPolicy.Peer(it.sourceKey, bezJmena(it.output), story = it.story)
        }
        return vysledek
    }

    /** Speech history takes precedence over queued OCR fragments. */
    private fun zaradRevizi(polozka: UryvekTts): Boolean {
        val seznam = frontaPredcitani.toMutableList()
        val plan = TtsRevisionPolicy.plan(polozka.proKontrolu(), seznam.map { it.proKontrolu() }, jizCteneProKontrolu())
        when (plan.action) {
            TtsRevisionPolicy.Action.NEW -> return false
            TtsRevisionPolicy.Action.DROP -> Unit
            TtsRevisionPolicy.Action.CONTINUE -> frontaPredcitani.addLast(polozka.copy(text = requireNotNull(plan.text)))
            TtsRevisionPolicy.Action.REPLACE -> {
                val stara = seznam[plan.pendingIndex]
                seznam[plan.pendingIndex] = polozka.copy(text = requireNotNull(plan.text),
                    eventAtMs = stara.eventAtMs, queuedAtMs = stara.queuedAtMs)
                frontaPredcitani.clear()
                frontaPredcitani.addAll(seznam)
            }
        }
        zapisRozhodnutiTts(plan.action.name, plan.reason, polozka.input, polozka.output,
            polozka.sourceId, polozka.eventAtMs, plan.text.orEmpty())
        return true
    }

    private fun zapisRozhodnutiTts(akce: String, duvod: String, vstup: String, vystup: String,
        idOcr: Long, casUdalosti: Long, cteni: String = "") {
        val ted = android.os.SystemClock.elapsedRealtime()
        val zpozdeni = (ted - casUdalosti).coerceAtLeast(0L)
        fun logText(s: String): String {
            val oneLine = s.replace("\r", " ").replace("\n", " ↵ ").replace("\"", "'")
            return if (oneLine.length <= 4_000) oneLine else oneLine.take(4_000) + "…[zkráceno]"
        }
        Zaznam.zapisTts(this,
            "OCR#$idOcr $akce reason=\"${logText(duvod)}\" INPUT=\"${logText(vstup)}\" OUTPUT=\"${logText(vystup)}\" READ=\"${logText(cteni)}\" lag=${zpozdeni}ms ${stavFrontyTts(ted)}")
    }

    private fun odhadDobyCteniMs(text: String, rychlost: Float): Long {
        val slova = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        val pauzy = text.count { it in ".,;:!?…" }
        return ((slova * 360L + pauzy * 90L) / rychlost.coerceIn(0.5f, 3.0f)).toLong()
    }

    private fun odhadFrontyMs(ted: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val beziciZbyva = aktualniUryvek?.let {
            (odhadDobyCteniMs(it.text, ttsAktualniRychlost) - (ted - ttsAktualniStartMs).coerceAtLeast(0L)).coerceAtLeast(0L)
        } ?: 0L
        val cekajici = frontaPredcitani.sumOf { odhadDobyCteniMs(it.text, rychlostZakladni) }
        return beziciZbyva + cekajici
    }

    private fun stavFrontyTts(ted: Long = android.os.SystemClock.elapsedRealtime()): String {
        val slova = frontaPredcitani.sumOf { it.text.trim().split(Regex("\\s+")).count(String::isNotBlank) }
        val nejstarsi = frontaPredcitani.firstOrNull()?.let { (ted - it.eventAtMs).coerceAtLeast(0L) }
            ?: aktualniUryvek?.let { (ted - it.eventAtMs).coerceAtLeast(0L) } ?: 0L
        return "queue=${frontaPredcitani.size} words=$slova active=${aktualniUryvek?.sourceId ?: "none"} " +
            "oldestLag=${nejstarsi}ms backlog~${odhadFrontyMs(ted) / 1000.0}s"
    }

    private fun stavTtsProUzivatele(): String {
        val ted = android.os.SystemClock.elapsedRealtime()
        val polozka = aktualniUryvek ?: frontaPredcitani.firstOrNull()
        val lag = polozka?.let { ((ted - it.eventAtMs).coerceAtLeast(0L) / 1000.0) } ?: 0.0
        val backlog = odhadFrontyMs(ted) / 1000.0
        val tempo = if (pravePredcita) ttsAktualniRychlost else rychlostZakladni
        return "TTS: fronta ${frontaPredcitani.size} · zpoždění ~${String.format(Locale.US, "%.1f", lag)}s · " +
            "dočtení ~${String.format(Locale.US, "%.1f", backlog)}s · ${String.format(Locale.US, "%.1f", tempo)}×"
    }

    private fun vlozDoFronty(preklad: String, vstupOcr: String, idOcr: Long, verze: Long,
        casUdalosti: Long, deduplikovat: Boolean) {
        if (verze in vyreseneVerzeTts) {
            zapisRozhodnutiTts("DROP", "událost v#$verze už byla vyřešena", vstupOcr, preklad, idOcr, casUdalosti)
            return
        }
        val plny = preklad.replace(Regex("\\s+"), " ").trim()
        val zdrojKlic = if (deduplikovat) OcrDedup.klic(vstupOcr) else vstupOcr
        val bezJmenMluvcich = bezJmena(preklad)
        if (bezJmenMluvcich.isBlank()) {
            vyresTtsVerzi(verze)
            zapisRozhodnutiTts("SKIP", "výstup obsahuje pouze jméno mluvčího", vstupOcr, plny, idOcr, casUdalosti)
            return
        }
        val polozka = UryvekTts(
            input = vstupOcr,
            output = plny,
            text = bezJmenMluvcich,
            sourceKey = zdrojKlic,
            sourceId = idOcr,
            eventVersion = verze,
            eventAtMs = casUdalosti,
            queuedAtMs = android.os.SystemClock.elapsedRealtime(),
            story = !deduplikovat,
            utteranceId = "playcaption-ocr$idOcr-${System.nanoTime()}"
        )
        vyresTtsVerzi(verze)
        if (!zaradRevizi(polozka)) {
            frontaPredcitani.addLast(polozka)
            zapisRozhodnutiTts("ENQUEUE", "nový titulek", vstupOcr, plny, idOcr, casUdalosti, polozka.text)
        }
        zpracujDohaneniFronty()
        spustDalsiUryvek()
    }

    private fun zpracujDohaneniFronty() {
        val ted = android.os.SystemClock.elapsedRealtime()
        fun vypust(polozka: UryvekTts, duvod: String) {
            frontaPredcitani.remove(polozka)
            zapisRozhodnutiTts("DROP", duvod, polozka.input, polozka.output,
                polozka.sourceId, polozka.eventAtMs, polozka.text)
        }
        frontaPredcitani.filter { !it.story && ted - it.eventAtMs > 10_000L }.forEach { polozka ->
            vypust(polozka, "čekající titulek je starší než 10 s; přednost dostává aktuálnější dialog")
        }
        fun nejstarsiStandardni() = frontaPredcitani.firstOrNull { !it.story }
        while (frontaPredcitani.count { !it.story } > 4) {
            nejstarsiStandardni()?.let { vypust(it, "fronta Standardního režimu překročila 4 čekající titulky") }
        }
        while (frontaPredcitani.count { !it.story } > 2 && odhadFrontyMs(ted) > 18_000L) {
            nejstarsiStandardni()?.let {
                vypust(it, "odhad fronty překročil 18 s; zkracuji zpoždění a ponechávám novější titulky")
            }
        }
    }

    /** Vlastní fronta zabrání tomu, aby nový titulek přerušil právě čtenou větu. */
    private fun spustDalsiUryvek() {
        if (!ttsReady || pravePredcita || !hlasZapnut) return
        zpracujDohaneniFronty()
        // The preceding utterance may have finished while this item was waiting.
        // Recheck coverage at the actual playback boundary, not just on enqueue.
        while (frontaPredcitani.isNotEmpty() &&
            TtsRevisionPolicy.covered(frontaPredcitani.first().proKontrolu(), jizCteneProKontrolu())) {
            val dup = frontaPredcitani.removeFirst()
            zapisRozhodnutiTts("DROP", "kontrola před startem: obsah už byl přečten", dup.input,
                dup.output, dup.sourceId, dup.eventAtMs, dup.text)
        }
        if (frontaPredcitani.isEmpty()) return
        val polozka = frontaPredcitani.removeFirst()
        aktualniUryvek = polozka
        ttsAktualniStartMs = android.os.SystemClock.elapsedRealtime()
        ttsAktualniRychlost = rychlostZakladni
        pravePredcita = true
        val odhadZpozdeni = (ttsAktualniStartMs - polozka.eventAtMs).coerceAtLeast(0L)
        val backlog = odhadFrontyMs(ttsAktualniStartMs)
        val rychlost = TtsRevisionPolicy.rate(rychlostZakladni, odhadZpozdeni, backlog)
        ttsAktualniRychlost = rychlost
        zapisRozhodnutiTts("CATCHUP", "základ ${rychlostZakladni}x; věk ${odhadZpozdeni}ms; fronta ~${backlog}ms → tempo ${rychlost}x",
            polozka.input, polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
        val vysledek = runCatching {
            tts?.setSpeechRate(rychlost)
            zapisRozhodnutiTts("STARTING", "Android engine rate=${rychlost}x", polozka.input,
                polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
            tts?.speak(polozka.text, TextToSpeech.QUEUE_ADD, null, polozka.utteranceId)
        }.getOrNull()
        if (vysledek != TextToSpeech.SUCCESS) {
            zapisRozhodnutiTts("ERROR", "Android speak status=$vysledek", polozka.input,
                polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
            dokonciTtsUryvek("ERROR speak status=$vysledek")
        }
    }

    private fun dokonciTtsUryvek(vysledek: String = "DONE", expectedId: String? = aktualniUryvek?.utteranceId) {
        ui.post {
            // Engines may deliver multiple terminal callbacks. Recheck on the UI thread so
            // a late callback cannot finish the next utterance and start overlapping speech.
            if (expectedId == null || aktualniUryvek?.utteranceId != expectedId) return@post
            val polozka = aktualniUryvek
            if (polozka != null) {
                zapisRozhodnutiTts(vysledek, "přehrávání skončilo", polozka.input,
                    polozka.output, polozka.sourceId, polozka.eventAtMs, polozka.text)
                if (vysledek.startsWith("DONE")) {
                    nedavnoPredcitane.addLast(TtsReadMemory(polozka.sourceKey, polozka.output,
                        android.os.SystemClock.elapsedRealtime(), polozka.story, polozka.sourceId))
                    while (nedavnoPredcitane.size > 24) nedavnoPredcitane.removeFirst()
                }
            }
            pravePredcita = false
            aktualniUryvek = null
            ttsAktualniStartMs = 0L
            ttsAktualniRychlost = rychlostZakladni
            spustDalsiUryvek()
        }
    }

    private fun obarviJmena(text: String, barvy: IntArray?): CharSequence {
        if (barvy == null || barvy.none { it != 0 }) return text
        val sb = android.text.SpannableStringBuilder(text)
        var zacatek = 0
        text.split("\n").forEachIndexed { i, radek ->
            val barva = barvy.getOrNull(i) ?: 0
            val dvojtecka = radek.indexOf(':')
            if (barva != 0 && dvojtecka in 1..25)
                sb.setSpan(android.text.style.ForegroundColorSpan(barva), zacatek, zacatek + dvojtecka + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            zacatek += radek.length + 1
        }
        return sb
    }

    private fun nastavVelikost(t: TextView) {
        t.setAutoSizeTextTypeUniformWithConfiguration(
            14, VELIKOSTI[velikost].toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    private var posledni: String? = null
    private var posledniDoTv = false

    private val obnova: Runnable = object : Runnable {
        override fun run() {
            // PS5 si mezitím vzala televize → zavřít
            if (TelefonTitulky.odpojeno) {
                TelefonTitulky.odpojeno = false
                android.widget.Toast.makeText(this@TelefonActivity, t("prevzato"), android.widget.Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // stav spojení
            val (klic, barva) = when {
                RemotePlayService.bezi -> "stavPripojeno" to Color.parseColor("#14A23C")
                else -> "stavPripojuji" to Color.parseColor("#E08A00")
            }
            // při potížích ukázat i přesný důvod (např. "Remote Play ukončeno: …")
            val duvod = RemotePlayService.stav
            val k = if (RemotePlayService.naXboxu) klic + "Xbox" else klic
            stav.text = if (!RemotePlayService.bezi && (duvod.contains("ukončeno", true) || duvod.contains("✘")))
                t(k) + "\n" + duvod else t(k)
            if (nast.pribehovy) stav.append("\n" + RemotePlayService.pribehStav)
            if (hlasZapnut && (pravePredcita || frontaPredcitani.isNotEmpty()))
                stav.append("\n" + stavTtsProUzivatele())
            tecka.background = zaobleni(barva)
            prepnoutKonzoli?.let { b ->
                val ukazat = RemotePlayService.obeZapnute && RemotePlayUdaje(this@TelefonActivity).sparovano &&
                    XboxUcet.konzole(this@TelefonActivity) != null
                b.visibility = if (ukazat) View.VISIBLE else View.GONE
                b.text = if (RemotePlayService.naXboxu) "⇄ Xbox" else "⇄ PS5"
            }

            // titulky jdou do televize → telefon jen ukazuje stav a ztlumí displej (šetří baterii)
            val doTv = Propojeni.Vysilac.cilIp != null
            if (doTv != posledniDoTv) {
                posledniDoTv = doTv
                // do televize → zpátky na výšku (obrazovka s posuvníky); jen telefon → jde otočit
                if (doTv) prepniSirku(false)
                nastavOtaceni(doTv)
                upravy.visibility = if (doTv) View.VISIBLE else View.GONE
                tip.visibility = if (doTv) View.GONE else View.VISIBLE
                if (doTv) {
                    // titulky jdou do televize → nahoře jen malý zelený řádek, víc místa pro nastavení
                    titulek.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                    titulek.textSize = 13f
                    titulek.setPadding(dp(20), dp(4), dp(20), dp(4))
                    obalTitulku.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    posunUprav.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                } else {
                    titulek.setPadding(dp(20), dp(20), dp(20), dp(20))
                    nastavVelikost(titulek)
                    obalTitulku.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    posunUprav.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                posunUprav.visibility = if (doTv) View.VISIBLE else View.GONE
                if (doTv) Propojeni.Vysilac.nastavRozvrh(rozvrh.copyOf())
                window.attributes = window.attributes.apply {
                    screenBrightness = if (doTv) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                posledni = null
            }
            // Hlas běží i při posílání titulků do TV, kdy telefon zobrazuje jen stav a nastavení.
            zpracujTtsAktualni()
            if (doTv) {
                val s = t("tvBeziKratce")
                if (titulek.text.toString() != s) {
                    titulek.text = s
                    titulek.setTextColor(Color.parseColor("#3CFF6E"))
                }
                napoveda.visibility = View.GONE
                ui.postDelayed(this, 300)
                return
            }

            // překlad
            val text = TelefonTitulky.aktualni()
            if (text != posledni) {
                posledni = text
                if (text.isEmpty()) {
                    titulek.text = t("telefonCekam")
                    titulek.setTextColor(Color.parseColor("#555555"))
                } else {
                    titulek.text = obarviJmena(text, TelefonTitulky.barvy)
                    titulek.setTextColor(Color.WHITE)
                }
            }
            val n = if (nast.napovedy) TelefonTitulky.napoveda else ""
            napoveda.text = n
            napoveda.visibility = if (n.isEmpty() || naSirku) View.GONE else View.VISIBLE
            // na šířku: tečka zelená = spojeno s PS5, oranžová = připojuji
            if (naSirku) sirkaTecka.background = zaobleni(Color.parseColor(if (RemotePlayService.bezi) "#3CFF6E" else "#E08A00"))
            ui.postDelayed(this, 100)
        }
    }
}
