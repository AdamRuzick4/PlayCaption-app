package cz.titulky.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * PlayCaption – hlavní obrazovka pro televizi (ovládání šipkami + OK).
 * Úvod s logem → výběr jazyka (jen poprvé) → jednoduchá hlavní obrazovka.
 * Stará obrazovka s testovacími tlačítky: VyvojActivity (podržet OK na logu).
 */
class MainActivity : Activity() {

    private val nast by lazy { Nastaveni(this) }
    private val udaje by lazy { RemotePlayUdaje(this) }
    private val ui = Handler(Looper.getMainLooper())
    private var obrazovka = ""
    /** Na telefonu: vše pod sebou a překlad na bílé obrazovce (TelefonActivity). */
    private val telefon by lazy { Rezim.jeTelefon(this) }
    private val sirkaObsahu get() = if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(900)
    private val jazyk get() = nast.jazykNeboCs
    private fun t(klic: String) = Texty.t(jazyk, klic)

    // barvy
    private val CERNA = Color.parseColor("#0B0B0E")
    private val KARTA = Color.parseColor("#1A1A21")
    private val CERVENA = Color.parseColor("#E5091A")
    private val ZELENA = Color.parseColor("#14A23C")
    private val SEDA = Color.parseColor("#3A3A44")
    private val TEXT2 = Color.parseColor("#A8A8B3")

    companion object {
        /** QR Platba na příspěvky (bez částky – každý si zvolí sám). Prázdné = karta se neukáže. */
        const val PODPORA_QR = "SPD*1.0*ACC:CZ0506000000000207980632*CC:CZK*MSG:PlayCaption podpora"
        /** Odkaz na Facebook skupinu PlayCaption – testování. Prázdné = QR v uvítání se neukáže. */
        const val SKUPINA_URL = ""
        const val DOBA_BETA_MS = 12000L
        /** Jazyky, které jdou zatím vybrat (beta pro českou a slovenskou komunitu). */
        val AKTIVNI_JAZYKY = setOf("cs", "sk")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pady.hlidej(this)
        prevezmiAccountId(intent)
        ukazUvod()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prevezmiAccountId(intent)
    }

    /** Záložní cesta: skript psn-account-id.ps1 v počítači pošle Account ID sem. */
    private fun prevezmiAccountId(intent: Intent?) {
        val id = intent?.getStringExtra("psn_account_id") ?: return
        udaje.accountId = id
    }

    override fun onResume() {
        super.onResume()
        if (obrazovka == "hlavni") ukazHlavni()
    }

    override fun onPause() {
        ui.removeCallbacks(obnova)
        super.onPause()
    }

    override fun onDestroy() {
        zastavServer()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (obrazovka) {
            "psn", "psnqr" -> ukazPripojeni()
            "pripojeni", "jazyk" -> if (nast.jazyk != null) ukazHlavni() else super.onBackPressed()
            "prijem" -> zpetNaVyber()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ úvod

    private fun ukazUvod() {
        obrazovka = "uvod"
        val bar = View(this).apply { setBackgroundColor(CERVENA) }
        val drazka = FrameLayout(this).apply {
            background = zaobleni(Color.parseColor("#26262E"), 999f)
            addView(bar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(if (telefon) dp(380) else dp(520), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(drazka, LinearLayout.LayoutParams(dp(340), dp(10)).apply { topMargin = dp(36) })
        }
        setContentView(koren)
        // animace načítání ~1,6 s
        val start = System.currentTimeMillis()
        val krok = object : Runnable {
            override fun run() {
                if (obrazovka != "uvod") return
                val podil = ((System.currentTimeMillis() - start) / 1600f).coerceAtMost(1f)
                bar.layoutParams = bar.layoutParams.apply { width = (drazka.width * podil).toInt() }
                bar.requestLayout()
                if (podil < 1f) ui.postDelayed(this, 30)
                else if (maUkazatBetu()) ukazBetu() else pokracuj()
            }
        }
        ui.post(krok)
    }

    private fun pokracuj() { if (nast.jazyk == null) ukazJazyk() else ukazHlavni() }

    // ------------------------------------------------------------------ beta: uvítání

    private val verze by lazy { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "" }
    private val prefsBeta by lazy { getSharedPreferences("beta", MODE_PRIVATE) }

    /** Uvítání ukázat při prvních 3 spuštěních každé verze. */
    private fun maUkazatBetu(): Boolean {
        val klic = "uvitani_$verze"
        val kolikrat = prefsBeta.getInt(klic, 0)
        if (kolikrat >= 3) return false
        prefsBeta.edit().putInt(klic, kolikrat + 1).apply()
        return true
    }

    /** Malá značka "BETA 0.9" do pravého horního rohu. */
    private fun betaZnacka() = TextView(this).apply {
        text = if (verze.startsWith("NG")) "NEXT GEN ${verze.removePrefix("NG").trim()}" else "BETA $verze"
        textSize = 15f * SKALA
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.15f
        setTextColor(CERVENA)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = 999f
            setStroke((2 * resources.displayMetrics.density).toInt(), CERVENA)
        }
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    private fun ukazBetu() {
        obrazovka = "beta"
        val zelena = Color.parseColor("#3CFF6E")
        val box = svisle().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = zaobleni(Color.parseColor("#111116"), 26f, zelena)
            setPadding(dp(54), dp(30), dp(54), dp(30))
        }
        box.addView(text(t("betaNadpis"), 34f, zelena, tucne = true).apply { gravity = Gravity.CENTER })
        val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        radek.addView(text(t("betaText"), 21f, Color.parseColor("#E8E8EE")).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // QR na Facebook skupinu (jen na televizi – telefon by skenoval sám sebe)
        if (!telefon && SKUPINA_URL.isNotEmpty()) {
            val q = svisle().apply { gravity = Gravity.CENTER_HORIZONTAL }
            q.addView(ImageView(this).apply {
                setImageBitmap(PrihlaseniTelefonem.qr(SKUPINA_URL, 400))
                background = zaobleni(Color.WHITE, 14f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(190), dp(190)))
            q.addView(text(t("betaSkupina"), 15f, TEXT2).apply { gravity = Gravity.CENTER }, odsazeni(top = 8))
            radek.addView(q, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(36) })
        }
        box.addView(radek, odsazeni(top = 14))

        val bar = View(this).apply { setBackgroundColor(zelena) }
        val drazka = FrameLayout(this).apply {
            background = zaobleni(Color.parseColor("#26262E"), 999f)
            addView(bar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val stred = svisle().apply {
            gravity = Gravity.CENTER
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo); adjustViewBounds = true
            }, LinearLayout.LayoutParams(if (telefon) dp(300) else dp(340), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(box, LinearLayout.LayoutParams(if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(1250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
            addView(drazka, LinearLayout.LayoutParams(dp(340), dp(8)).apply { topMargin = dp(24) })
            addView(text(t("betaDal"), 16f, Color.parseColor("#77777F")).apply { gravity = Gravity.CENTER }, odsazeni(top = 18))
            isFocusable = true
            setOnClickListener { if (obrazovka == "beta") pokracuj() }
        }
        val koren = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(stred, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(betaZnacka(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        setContentView(koren)
        stred.requestFocus()
        // ~12 s (delší text), pak samo dál (OK = hned)
        val start = System.currentTimeMillis()
        val krok = object : Runnable {
            override fun run() {
                if (obrazovka != "beta") return
                val podil = ((System.currentTimeMillis() - start) / DOBA_BETA_MS.toFloat()).coerceAtMost(1f)
                bar.layoutParams = bar.layoutParams.apply { width = (drazka.width * podil).toInt() }
                bar.requestLayout()
                if (podil < 1f) ui.postDelayed(this, 40) else pokracuj()
            }
        }
        ui.post(krok)
    }

    // ------------------------------------------------------------------ výběr jazyka

    private fun ukazJazyk() {
        obrazovka = "jazyk"
        var vybrany = nast.jazyk?.takeIf { it in AKTIVNI_JAZYKY } ?: "cs"
        val sloupec = svisle().apply { gravity = Gravity.CENTER_HORIZONTAL }
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(Texty.t(vybrany, "vyberJazyk"), 30f, Color.WHITE, tucne = true).apply { gravity = Gravity.CENTER })
        val popis = text(Texty.t(vybrany, "vyberJazykPopis"), 18f, TEXT2).apply { gravity = Gravity.CENTER }
        sloupec.addView(popis, odsazeni(top = 6, bottom = 20))

        val mrizka = svisle()
        val polozky = mutableListOf<TextView>()
        lateinit var pokracovat: TextView
        fun obnov() {
            polozky.forEachIndexed { i, v ->
                val j = Texty.JAZYKY[i]
                val aktivni = j.kod in AKTIVNI_JAZYKY
                v.text = "${j.vlajka}   ${j.nazev}" + when {
                    !aktivni -> "   · " + Texty.t(vybrany, "brzy")
                    j.kod == vybrany -> "      ✔"
                    else -> ""
                }
                v.background = vyber(KARTA, if (j.kod == vybrany) CERVENA else null)
                // zatím jen čeština a slovenština – ostatní šedé, nejdou vybrat
                v.alpha = if (aktivni) 1f else 0.35f
                v.isFocusable = aktivni
                v.isClickable = aktivni
            }
            pokracovat.text = Texty.t(vybrany, "pokracovat")
        }
        // dva sloupce jazyků
        Texty.JAZYKY.chunked(if (telefon) Texty.JAZYKY.size else 4).let { sloupce ->
            val radek = vodorovne()
            sloupce.forEach { cast ->
                val s = svisle()
                cast.forEach { j ->
                    val v = tlacitko("", 22f) {
                        if (j.kod !in AKTIVNI_JAZYKY) return@tlacitko
                        vybrany = j.kod
                        obnov()
                        sloupec.getChildAt(1).let { (it as TextView).text = Texty.t(vybrany, "vyberJazyk") }
                        popis.text = Texty.t(vybrany, "vyberJazykPopis")
                    }.apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(14)) }
                    polozky += v
                    s.addView(v, LinearLayout.LayoutParams(if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(330), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) })
                }
                radek.addView(s)
            }
            mrizka.addView(radek)
        }
        sloupec.addView(mrizka)
        pokracovat = tlacitkoCervene("") {
            nast.jazyk = vybrany
            ukazHlavni()
        }
        sloupec.addView(pokracovat, LinearLayout.LayoutParams(if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(680), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        obnov()
        nastavObsah(sloupec, sirka = if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        polozky[Texty.JAZYKY.indexOfFirst { it.kod == vybrany }.coerceAtLeast(0)].requestFocus()
    }

    // ------------------------------------------------------------------ hlavní obrazovka

    private lateinit var stavText: TextView
    private lateinit var stavTecka: View
    private lateinit var stavKarta: LinearLayout

    private fun ukazHlavni() {
        if (!telefon && PrijemService.bezi) { ukazPrijem(); return }
        obrazovka = "hlavni"
        val vlevo = svisle()
        val vpravo = svisle()

        // logo (podržením OK se otevře stará vývojářská obrazovka)
        vlevo.addView(nadpisLogo().apply {
            isFocusable = true
            setOnLongClickListener { Zaznam.ukaz(this@MainActivity, sVyvojem = true); true }
        })

        // povolení přes jiné aplikace (jen když chybí)
        if (!telefon && !Settings.canDrawOverlays(this)) {
            val k = karta()
            k.addView(text(t("povoleniPopis"), 17f, Color.parseColor("#FFC24B")))
            k.addView(tlacitkoCervene(t("povoleni")) { pozadejOPovoleni() }, obal(top = 10))
            vlevo.addView(k, odsazeni(bottom = 14))
        }

        // jazyk
        vlevo.addView(sekce(t("jazykPrekladu")))
        val j = Texty.JAZYKY.firstOrNull { it.kod == jazyk } ?: Texty.JAZYKY[0]
        val popisZdroje = t("zdroj")
        val jazykBtn = tlacitko("${j.vlajka}   ${j.nazev}   ›\n$popisZdroje", 22f) { ukazJazyk() }.apply {
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        vlevo.addView(jazykBtn, odsazeni(bottom = 18))

        // konzole
        vlevo.addView(sekce(t("pripojitKonzoli")))
        val konzole = karta()
        val r = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(text("🎮  PlayStation 5" + if (udaje.sparovano) "   ✔ ${t("sparovano")}" else "", 21f, Color.WHITE),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tlacitkoZelene(if (udaje.sparovano) t("znovuSparovat") else t("pripojit")) { ukazPripojeni() })
        konzole.addView(r)
        konzole.addView(text(t("pripojitPopis"), 14f, TEXT2), odsazeni(top = 6))
        vlevo.addView(konzole)

        // podpora – QR Platba (jen televize: telefon by musel skenovat sám sebe)
        if (!telefon && PODPORA_QR.isNotEmpty()) {
            vlevo.addView(sekce(t("podpora")), odsazeni(top = 18))
            val k = vodorovne().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = zaobleni(KARTA, 16f, Color.parseColor("#5A4F22"))
                setPadding(dp(26), dp(20), dp(20), dp(20))
            }
            val txt = svisle()
            txt.addView(text(t("podporaNadpis"), 22f, Color.parseColor("#FFD54A"), tucne = true))
            txt.addView(text(t("podporaText"), 16f, Color.parseColor("#D8D8E0")).apply { setLineSpacing(0f, 1.3f) }, odsazeni(top = 6))
            k.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            k.addView(ImageView(this).apply {
                setImageBitmap(PrihlaseniTelefonem.qr(PODPORA_QR, 400))
                background = zaobleni(Color.WHITE, 14f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(200), dp(200)).apply { leftMargin = dp(20) })
            vlevo.addView(k)
        }

        // stav
        stavKarta = vodorovne().apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isFocusable = true
            setOnClickListener { prepniPreklad() }
        }
        stavTecka = View(this).apply { background = zaobleni(Color.GRAY, 999f) }
        stavText = text("", 24f, Color.WHITE, tucne = true)
        stavKarta.addView(stavTecka, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(16) })
        stavKarta.addView(stavText)
        vpravo.addView(stavKarta, odsazeni(top = if (telefon) 24 else 70, bottom = 22))

        // zobrazení
        if (!telefon) {
            vpravo.addView(sekce(t("zobrazeni")))
            val zobr = karta()
            zobr.addView(prepinac(t("prekryvat"), { nast.prekryvat }, { nast.prekryvat = it }, t("zapnuto"), t("vypnuto")))
            vpravo.addView(zobr, odsazeni(bottom = 18))
        }

        // co překládat
        vpravo.addView(sekce(t("coPrekladat")))
        val co = karta()
        co.addView(prepinac(t("dialogy"), { nast.dialogy }, { nast.dialogy = it }, t("ano"), t("ne")))
        co.addView(View(this).apply { setBackgroundColor(SEDA) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(8), 0, dp(8)) })
        co.addView(prepinac(t("napovedy"), { nast.napovedy }, { nast.napovedy = it }, t("ano"), t("ne")))
        vpravo.addView(co, odsazeni(bottom = 22))

        var dlazdiceTv: View? = null
        var dlazdiceTel: View? = null
        if (telefon) {
            vpravo.addView(tlacitkoCervene(t("hotovo")) {
                if (udaje.sparovano) startActivity(Intent(this, TelefonActivity::class.java)) else ukazPripojeni()
            })
        } else {
            // "Jak chceš hrát?" – dvě velké volby místo nenápadného tlačítka
            vpravo.addView(sekce(t("hratJak")))
            // okraj kolem dlaždic, ať se zvětšená vybraná dlaždice neořízne
            val volby = vodorovne().apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(10), dp(12), dp(10)) }
            dlazdiceTv = dlazdice("📺", t("hratTv"), t("hratTvPopis"), CERVENA) { hratTelevizi() }
            dlazdiceTel = dlazdice("📱➜📺", t("hratTel"), t("hratTelPopis"), Color.parseColor("#2A2C3E")) { hratSTelefonem() }
            volby.addView(dlazdiceTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(18) })
            volby.addView(dlazdiceTel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            vpravo.addView(volby)
            vpravo.addView(text(t("hratVyber"), 16f, TEXT2).apply { gravity = Gravity.CENTER }, odsazeni(top = 10))
        }

        // tip: na čem nejvíc záleží rychlost titulků
        vpravo.addView(text(t("tipPripojeni"), 16f, TEXT2).apply {
            setLineSpacing(0f, 1.3f)
            background = zaobleni(KARTA, 16f)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }, odsazeni(top = 18))

        val obsah = if (telefon) svisle().apply {
            addView(vlevo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(vpravo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else vodorovne().apply {
            addView(vlevo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(40) })
            addView(vpravo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        nastavObsah(obsah, sirka = ViewGroup.LayoutParams.MATCH_PARENT)

        // automaticky spustit, když je vše připravené a uživatel překlad nevypnul
        if (!telefon && !PrijemService.bezi && udaje.sparovano && Settings.canDrawOverlays(this) && AutoStart.jeZapnuto(this) && !RemotePlayService.aktivni)
            RemotePlayService.spustVse(this)
        obnovStav()
        ui.removeCallbacks(obnova)
        ui.postDelayed(obnova, 1000)
        if (!telefon) (if (prefsBeta.getString("rezimHry", "tv") == "tel") dlazdiceTel else dlazdiceTv)?.requestFocus()
    }

    // ------------------------------------------------------------------ titulky z telefonu (televize)

    /** Zpět z čekání na telefon → hlavní obrazovka s výběrem "Jak chceš hrát?". */
    private fun zpetNaVyber() {
        PrijemService.zastav(this)
        prefsBeta.edit().putString("rezimHry", "tv").apply()
        obrazovka = "hlavni"
        ukazHlavni()
    }

    /** Velká volba na hlavní obrazovce (ikona, nadpis, popis). */
    private fun dlazdice(ikona: String, nadpis: String, popis: String, barva: Int, akce: () -> Unit) = svisle().apply {
        // obě volby stejně tmavé; ta, na které je ovladač, se rozsvítí (červeně + bílý rámeček) a trochu zvětší
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), zaobleni(CERVENA, 26f, Color.WHITE))
            addState(intArrayOf(), zaobleni(Color.parseColor("#2A2C3E"), 26f, Color.parseColor("#3A3D55")))
        }
        setPadding(dp(24), dp(20), dp(24), dp(20))
        isFocusable = true
        isClickable = true
        setOnClickListener { akce() }
        setOnFocusChangeListener { v, maFokus ->
            val m = if (maFokus) 1.06f else 1f
            v.animate().scaleX(m).scaleY(m).setDuration(150).start()
            v.elevation = if (maFokus) dp(10).toFloat() else 0f
        }
        addView(text(ikona, 34f, Color.WHITE))
        addView(text(nadpis, 24f, Color.WHITE, tucne = true), odsazeni(top = 4))
        addView(text(popis, 15f, Color.parseColor("#DDDDDD")).apply { setLineSpacing(0f, 1.25f) }, odsazeni(top = 4))
    }

    /** "Jen televize" = dřívější Hotovo. */
    private fun hratTelevizi() {
        prefsBeta.edit().putString("rezimHry", "tv").apply()
        if (udaje.sparovano && Settings.canDrawOverlays(this)) {
            PrijemService.zastav(this)
            RemotePlayService.pozastaveno = false
            AutoStart.nastavZapnuto(this, true)
            RemotePlayService.spustVse(this, prevzit = true)
            Oznameni.ukaz(this, t("hratInfo"))
        } else if (!udaje.sparovano) { ukazPripojeni(); return }
        moveTaskToBack(true)
    }

    /** "S telefonem" = televize jen ukazuje titulky poslané z telefonu. */
    private fun hratSTelefonem() {
        if (!Settings.canDrawOverlays(this)) return
        prefsBeta.edit().putString("rezimHry", "tel").apply()
        // televize se od PS5 úplně odpojí (PS5 si vezme telefon)
        RemotePlayService.pozastaveno = true
        AutoStart.nastavZapnuto(this, false)
        runCatching { startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP)) }
        PrijemService.spust(this)
        ukazPrijem()
    }

    // ------------------------------------------------------------------ titulky z telefonu (televize)

    private fun ukazPrijem() {
        obrazovka = "prijem"
        val sloupec = svisle().apply { gravity = Gravity.CENTER_HORIZONTAL }
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("prijemNadpis"), 32f, Color.WHITE, tucne = true).apply { gravity = Gravity.CENTER }, odsazeni(top = 14))

        // tři kroky s obrázky
        val kroky = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        listOf("📱" to "ngKrok1", "👆" to "ngKrok2", "🎮" to "ngKrok3").forEachIndexed { i, (ikona, klic) ->
            if (i > 0) kroky.addView(text("➜", 40f, Color.parseColor("#555555")), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(18), 0, dp(18), 0) })
            val k = svisle().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = zaobleni(Color.parseColor("#1C1D28"), 26f)
                setPadding(dp(26), dp(22), dp(26), dp(22))
            }
            k.addView(text("${t("ngKrokN")} ${i + 1}", 15f, CERVENA, tucne = true).apply { gravity = Gravity.CENTER })
            k.addView(text(ikona, 52f, Color.WHITE).apply { gravity = Gravity.CENTER }, odsazeni(top = 4))
            k.addView(text(t(klic), 20f, Color.WHITE, tucne = true).apply { gravity = Gravity.CENTER }, odsazeni(top = 4))
            kroky.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        sloupec.addView(kroky, odsazeni(top = 28))

        val stav = text("", 24f, Color.WHITE, tucne = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(14), dp(40), dp(14))
        }
        sloupec.addView(stav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28); gravity = Gravity.CENTER_HORIZONTAL
        })

        val zpet = tlacitko(t("prijemZpetKratce"), 20f) { zpetNaVyber() }.apply {
            gravity = Gravity.CENTER
            background = vyber(Color.parseColor("#3A3C50"), radius = 999f)
            setPadding(dp(34), dp(14), dp(34), dp(14))
        }
        sloupec.addView(zpet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL
        })
        sloupec.addView(text(t("prijemSam"), 16f, TEXT2).apply { gravity = Gravity.CENTER }, odsazeni(top = 16))

        nastavObsah(sloupec, sirka = ViewGroup.LayoutParams.MATCH_PARENT)
        zpet.requestFocus()

        var bylPripojen = PrijemService.telefonPripojen()
        val obnovPrijem = object : Runnable {
            override fun run() {
                if (obrazovka != "prijem") return
                val ok = PrijemService.telefonPripojen()
                stav.text = t(if (ok) "prijemPripojen" else "prijemCekam")
                stav.background = zaobleni(if (ok) ZELENA else Color.parseColor("#B07A10"), 999f)
                // telefon se právě připojil → sama přepnout na hru
                if (ok && !bylPripojen) moveTaskToBack(true)
                bylPripojen = ok
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(obnovPrijem)
    }

    private val obnova: Runnable = object : Runnable {
        override fun run() {
            if (obrazovka != "hlavni") return
            obnovStav()
            ui.postDelayed(this, 1000)
        }
    }

    private fun obnovStav() {
        if (!::stavText.isInitialized) return
        val (barva, klic) = when {
            !udaje.sparovano -> SEDA to "stavNesparovano"
            !RemotePlayService.aktivni || RemotePlayService.pozastaveno -> SEDA to "stavVypnuto"
            RemotePlayService.bezi -> ZELENA to "stavPripojeno"
            else -> Color.parseColor("#B07A10") to "stavPripojuji"
        }
        // při připojování ukázat i přesný důvod, proč to zatím nejde (např. "Remote Play ukončeno: …")
        val duvod = RemotePlayService.stav
        stavText.text = if (klic == "stavPripojuji" && duvod.isNotBlank() && !duvod.contains("běží", true))
            t(klic) + "\n" + duvod.take(90) else t(klic)
        stavTecka.background = zaobleni(if (barva == ZELENA) Color.parseColor("#3CFF6E") else Color.parseColor("#DDDDDD"), 999f)
        stavKarta.background = vyber(barva, radius = 999f)
    }

    private fun prepniPreklad() {
        if (!udaje.sparovano) { ukazPripojeni(); return }
        if (telefon) { startActivity(Intent(this, TelefonActivity::class.java)); return }
        // vypnutí = jen pozastavit (spojení s PS5 zůstává, jinak by PS5 1–2 min hlásila "již se používá")
        if (RemotePlayService.aktivni && !RemotePlayService.pozastaveno) {
            RemotePlayService.pozastaveno = true
            AutoStart.nastavZapnuto(this, false)
        } else {
            RemotePlayService.pozastaveno = false
            AutoStart.nastavZapnuto(this, true)
            if (!RemotePlayService.aktivni) RemotePlayService.spustVse(this, prevzit = true)
        }
        ui.postDelayed({ obnovStav() }, 600)
    }

    // ------------------------------------------------------------------ připojení ke konzoli

    private var nalezenaIp: String? = null

    private fun ukazPripojeni() {
        zastavServer()
        obrazovka = "pripojeni"
        val sloupec = svisle()
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("pripojitKonzoli"), 28f, Color.WHITE, tucne = true), odsazeni(bottom = 16))

        // 0) než začneš – je potřeba druhý účet na PS5
        val k0 = karta()
        k0.addView(text(t("predemNadpis"), 20f, Color.parseColor("#FFC24B"), tucne = true))
        k0.addView(text(t("predem"), 17f, Color.WHITE), odsazeni(top = 6))
        sloupec.addView(k0, odsazeni(bottom = 12))

        // 1) účet PlayStation
        val k1 = karta()
        k1.addView(text(t("krok1"), 18f, Color.WHITE))
        val ucetStav = text(if (udaje.accountIdBajty() != null) t("ucetOk") else "", 17f, Color.parseColor("#7DFF9A"))
        k1.addView(ucetStav, odsazeni(top = 6))
        k1.addView(tlacitkoCervene(if (udaje.accountIdBajty() != null) t("zmenitUcet") else t("prihlasit")) { ukazPsnTelefonem() }, obal(top = 8))
        sloupec.addView(k1, odsazeni(bottom = 12))

        // 2) hledání PS5
        val k2 = karta()
        k2.addView(text(t("krok2"), 18f, Color.WHITE))
        val hledaniStav = text(t("hledam"), 17f, TEXT2)
        k2.addView(hledaniStav, odsazeni(top = 6))
        fun hledej() {
            hledaniStav.text = t("hledam")
            Thread {
                val seznam = runCatching { HledacPS.hledej() }.getOrDefault(emptyList())
                runOnUiThread {
                    val ps = seznam.firstOrNull { it.typ == "PS5" } ?: seznam.firstOrNull()
                    nalezenaIp = ps?.ip
                    hledaniStav.text = if (ps != null) "✔ ${t("nalezena")}: ${ps.jmeno} (${ps.ip})" else "✘ ${t("nenalezena")}"
                    hledaniStav.setTextColor(if (ps != null) Color.parseColor("#7DFF9A") else Color.parseColor("#FF8A8A"))
                }
            }.start()
        }
        k2.addView(tlacitko(t("hledatZnovu"), 18f) { hledej() }.apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 8))
        sloupec.addView(k2, odsazeni(bottom = 12))

        // 3) PIN
        val k3 = karta()
        k3.addView(text(t("krok3"), 18f, Color.WHITE))
        val pin = EditText(this).apply {
            hint = "12345678"
            textSize = 26f * SKALA
            setTextColor(Color.WHITE)
            setHintTextColor(SEDA)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            background = vyber(Color.parseColor("#26262E"), CERVENA)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        k3.addView(pin, LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val parovaniStav = text("", 17f, TEXT2)
        k3.addView(tlacitkoZelene(t("sparovat")) {
            val ip = nalezenaIp ?: udaje.ipKonzole
            val cislo = pin.text.toString().filter { it.isDigit() }
            when {
                udaje.accountIdBajty() == null -> parovaniStav.text = t("nejdrivUcet")
                ip == null -> parovaniStav.text = t("nenalezena")
                cislo.length != 8 -> parovaniStav.text = t("pinChyba")
                else -> {
                    parovaniStav.text = t("paruji")
                    Parovani.sparuj(this, ip, cislo.toInt(), log = {}, hotovo = { ok, zprava ->
                        runOnUiThread {
                            parovaniStav.text = if (ok) "✔ ${t("sparovano")}" else "✘ $zprava"
                            if (ok) {
                                AutoStart.nastavZapnuto(this, true)
                                if (!telefon && Settings.canDrawOverlays(this)) RemotePlayService.spustVse(this, prevzit = true)
                                ui.postDelayed({ ukazHlavni() }, 1200)
                            }
                        }
                    })
                }
            }
        }, obal(top = 10))
        k3.addView(parovaniStav, odsazeni(top = 6))
        sloupec.addView(k3, odsazeni(bottom = 12))

        sloupec.addView(tlacitko(t("zpet"), 18f) { ukazHlavni() }.apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal())
        nastavObsah(sloupec, sirka = sirkaObsahu)
        hledej()
    }

    // ------------------------------------------------------------------ přihlášení PSN (prohlížeč)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ukazPsn() {
        obrazovka = "psn"
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.setAcceptCookie(true)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Sony někdy odmítá vestavěný prohlížeč ("Can't connect to the server") → tvářit se jako běžný Chrome
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            isFocusable = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    zachytKod(request.url.toString())

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    zachytKod(url)
                }
            }
        }
        cookies.setAcceptThirdPartyCookies(web, true)
        setContentView(web)
        web.requestFocus()
        web.loadUrl(PsnPrihlaseni.ADRESA_PRIHLASENI)
    }

    private var zpracovavam = false

    /** Po přihlášení Sony přesměruje na adresu s kódem → vyměnit za Account ID. */
    private fun zachytKod(adresa: String): Boolean {
        val kod = PsnPrihlaseni.kodZAdresy(adresa) ?: return false
        vymenKod(kod)
        return true
    }

    /** Kód z přihlášení (z prohlížeče v TV nebo z telefonu) → Account ID. */
    private fun vymenKod(kod: String) {
        if (zpracovavam) return
        zpracovavam = true
        Thread {
            val id = runCatching { PsnPrihlaseni.accountId(kod) }.getOrNull()
            runOnUiThread {
                zpracovavam = false
                if (id != null) udaje.accountId = id
                ukazPripojeni()
                if (id == null) android.widget.Toast.makeText(this, t("ucetChyba"), android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ přihlášení přes telefon (QR)

    private var server: PrihlaseniTelefonem? = null

    private fun zastavServer() {
        server?.zastav()
        server = null
    }

    private fun ukazPsnTelefonem() {
        obrazovka = "psnqr"
        zastavServer()
        val cesky = nast.jazykNeboCs in listOf("cs", "sk")
        server = PrihlaseniTelefonem(cesky) { kod -> runOnUiThread { if (obrazovka == "psnqr") vymenKod(kod) } }
            .also { it.spust() }
        val adresa = if (telefon) "http://127.0.0.1:${PrihlaseniTelefonem.PORT}"
            else "http://${PrihlaseniTelefonem.mojeIp() ?: "?"}:${PrihlaseniTelefonem.PORT}"

        val sloupec = svisle()
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("qrNadpis"), 28f, Color.WHITE, tucne = true), odsazeni(bottom = 12))
        val k = karta()
        if (telefon) {
            k.addView(text(t("qrPopisTelefon"), 18f, Color.WHITE))
            k.addView(tlacitkoCervene(t("qrOtevrit")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(adresa))) }
            }, obal(top = 10))
        } else {
            val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
            radek.addView(ImageView(this).apply {
                runCatching { setImageBitmap(PrihlaseniTelefonem.qr(adresa, 512)) }
            }, LinearLayout.LayoutParams(dp(330), dp(330)).apply { rightMargin = dp(30) })
            val popis = svisle()
            popis.addView(text(t("qrPopis"), 19f, Color.WHITE))
            popis.addView(text(adresa, 22f, Color.parseColor("#7DFF9A"), tucne = true), odsazeni(top = 12))
            radek.addView(popis, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            k.addView(radek)
        }
        k.addView(text(t("qrCekam"), 17f, TEXT2), odsazeni(top = 14))
        sloupec.addView(k, odsazeni(bottom = 12))
        sloupec.addView(tlacitko(t("qrVTelevizi"), 17f) { zastavServer(); ukazPsn() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 4))
        sloupec.addView(tlacitko(t("zpet"), 17f) { ukazPripojeni() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 10))
        nastavObsah(sloupec, sirka = sirkaObsahu)
    }

    // ------------------------------------------------------------------ povolení

    private fun pozadejOPovoleni() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    // ------------------------------------------------------------------ pomocníci pro vzhled

    /** Zmenšení celého prostředí (televize má velké písmo už samo o sobě). */
    private val SKALA = 0.72f
    private fun dp(x: Int) = (x * SKALA * resources.displayMetrics.density).toInt()

    /** Odsazení pro tlačítka – jen tak široká, jak je potřeba (ne přes celou šířku). */
    private fun obal(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun zaobleni(barva: Int, radius: Float = 16f, ramecek: Int? = null) = GradientDrawable().apply {
        setColor(barva)
        cornerRadius = if (radius >= 999f) 999f * resources.displayMetrics.density else radius * resources.displayMetrics.density
        if (ramecek != null) setStroke(dp(3), ramecek)
    }

    /** Pozadí, které při výběru ovladačem dostane výrazný rámeček. */
    private fun vyber(barva: Int, ramecekTrvale: Int? = null, radius: Float = 16f) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), zaobleni(barva, radius, Color.WHITE))
        addState(intArrayOf(), zaobleni(barva, radius, ramecekTrvale))
    }

    private fun svisle() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun vodorovne() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun odsazeni(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun text(s: String, velikost: Float, barva: Int, tucne: Boolean = false) = TextView(this).apply {
        text = s
        textSize = velikost * SKALA
        setTextColor(barva)
        if (tucne) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sekce(s: String) = text(s, 22f, Color.WHITE, tucne = true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun karta() = svisle().apply {
        background = zaobleni(KARTA)
        setPadding(dp(22), dp(16), dp(22), dp(16))
    }

    private fun nadpisLogo() = ImageView(this).apply {
        setImageResource(R.drawable.logo)
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    private fun tlacitko(s: String, velikost: Float, akce: () -> Unit) = TextView(this).apply {
        text = s
        textSize = velikost * SKALA
        setTextColor(Color.WHITE)
        isFocusable = true
        isClickable = true
        background = vyber(KARTA)
        setOnClickListener { akce() }
    }

    private fun tlacitkoCervene(s: String, akce: () -> Unit) = tlacitko(s, 24f, akce).apply {
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = vyber(CERVENA, radius = 999f)
        setPadding(dp(28), dp(14), dp(28), dp(14))
    }

    private fun tlacitkoZelene(s: String, akce: () -> Unit) = tlacitko(s, 20f, akce).apply {
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = vyber(ZELENA, radius = 999f)
        setPadding(dp(26), dp(10), dp(26), dp(10))
    }

    /** Řádek "popis …… [Ano/Ne]" – OK přepíná. */
    private fun prepinac(popis: String, cti: () -> Boolean, zapis: (Boolean) -> Unit, zap: String, vyp: String): View {
        val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        radek.addView(text(popis, 20f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = tlacitko("", 19f) {}.apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(120)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun obnov() {
            val ano = cti()
            pill.text = if (ano) zap else vyp
            pill.background = vyber(if (ano) ZELENA else SEDA, radius = 999f)
        }
        pill.setOnClickListener { zapis(!cti()); obnov() }
        obnov()
        radek.addView(pill)
        return radek
    }

    private fun nastavObsah(obsah: View, sirka: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        val ram = FrameLayout(this).apply {
            setBackgroundColor(CERNA)
            clipToPadding = false // značka BETA sedí v okraji
            if (telefon) setPadding(dp(20), dp(28), dp(20), dp(28)) else setPadding(dp(56), dp(36), dp(56), dp(36))
            addView(obsah, FrameLayout.LayoutParams(sirka, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addView(betaZnacka(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = -dp(if (telefon) 16 else 20); rightMargin = -dp(if (telefon) 8 else 30)
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(CERNA)
            isFillViewport = true
            addView(ram)
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // tlačítko Menu na ovladači → vývojářská obrazovka
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, VyvojActivity::class.java)); return true
        }
        return super.dispatchKeyEvent(event)
    }
}
