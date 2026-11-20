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
        /** Stažení aplikace do telefonu / tabletu (QR kód v okně „Přidat Android zařízení“). */
        // prázdné = QR kód se neukáže (doplnit odkaz ke stažení, např. Google Disk)
        const val STAHNOUT_URL = ""
        const val PODPORA_QR = "SPD*1.0*ACC:CZ0506000000000207980632*CC:CZK*MSG:PlayCaption podpora"
        /** Odkaz na Facebook skupinu PlayCaption – testování. Prázdné = QR v uvítání se neukáže. */
        const val SKUPINA_URL = ""
        const val DOBA_BETA_MS = 9000L
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
        // telefon/tablet: když běží spojení s konzolí, nenechat displej zhasnout ani tady
        // (zhasnutý displej = za minutu konec spojení, Wi-Fi usne)
        if (Rezim.jeTelefon(this) && RemotePlayService.aktivni)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (obrazovka == "hlavni") ukazHlavni()
        // vrátil se z nastavení a povolení pořád nemá → televize ho asi schovává, ukázat návod
        if (zkouselPovoleni && !Rezim.jeTelefon(this) && !Settings.canDrawOverlays(this)) ukazNavodPovoleni()
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
            "xboxkod" -> ukazPripojeniXbox()
            "xbox" -> { xboxRelace?.zastav(); xboxRelace = null; ukazHlavni() }
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
                // poprvé: nejdřív výběr jazyka, uvítání až potom (ve zvoleném jazyce)
                else if (nast.jazyk == null) ukazJazyk()
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
        okBtn = null
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

        val stred = svisle().apply {
            gravity = Gravity.CENTER
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo); adjustViewBounds = true
            }, LinearLayout.LayoutParams(if (telefon) dp(300) else dp(340), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(box, LinearLayout.LayoutParams(if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(1250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
            // nemizí samo – člověk potvrdí, že si to přečetl
            addView(tlacitkoZelene(t("betaOk")) { if (obrazovka == "beta") pokracuj() }.also { okBtn = it },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) })
        }
        val koren = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(stred, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(betaZnacka(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        setContentView(koren)
        okBtn?.requestFocus()
    }

    /** Tlačítko OK na uvítání (výběr ovladačem rovnou na něm). */
    private var okBtn: View? = null

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
            // první spuštění: po výběru jazyka uvítání v tom jazyce
            if (obrazovka == "jazyk" && maUkazatBetu()) ukazBetu() else ukazHlavni()
        }
        sloupec.addView(pokracovat, LinearLayout.LayoutParams(if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else dp(680), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        obnov()
        nastavObsah(sloupec, sirka = if (telefon) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        polozky[Texty.JAZYKY.indexOfFirst { it.kod == vybrany }.coerceAtLeast(0)].requestFocus()
    }

    // ------------------------------------------------------------------ hlavní obrazovka

    /** Pruh „Stav“: nadpis, pod ním podrobnost, vlevo kolečko / točící se načítání. */
    private lateinit var stavText: TextView
    private var stavPodtext: TextView? = null
    private var stavIkona: TextView? = null
    private var stavNacitani: View? = null
    private var stavVypln: View? = null
    /** Televize: malá nápověda vpravo v pruhu Stav („Zmáčkni OK pro vypnutí“). */
    private var stavOkNapoveda: TextView? = null
    private lateinit var stavKarta: FrameLayout
    private var stavUrovenPosledni: UrovenSpojeni? = null
    private var stavAnimace: android.animation.ValueAnimator? = null
    private var stavPodil = 0f
    /** Poslední použité barvy pruhu (pozadí, rámeček, nadpis) – odsud se „zhasíná“. */
    private var stavBarvy: Triple<Int, Int, Int>? = null
    private var stavZhasina: android.animation.ValueAnimator? = null

    // řádky konzolí (rozsvítí se ta, ke které se připojuje)
    private var psRadek: LinearLayout? = null
    private var psStav: TextView? = null
    private var xboxRadekBox: LinearLayout? = null
    private var xboxStav: TextView? = null

    private val ZELENA_SVETLA = Color.parseColor("#3CFF6E")
    private val ZELENA_TMAVA = Color.parseColor("#0F3D1E")
    private val ZELENA_RAMECEK = Color.parseColor("#22C55E")
    private val ZLUTA = Color.parseColor("#FFC24B")
    private val CERVENA_SVETLA = Color.parseColor("#FF6B70")

    /** Velikost písma: televize podle návrhu 0.22, telefon jako dřív. */
    private fun pismo(tv: Float, tel: Float) = if (telefon) tel else tv

    private fun ukazHlavni() {
        zastavServer()
        if (!telefon && PrijemService.bezi) { ukazPrijem(); return }
        obrazovka = "hlavni"
        stavUrovenPosledni = null
        stavAnimace?.cancel(); stavAnimace = null
        stavZhasina?.cancel(); stavZhasina = null
        stavBarvy = null
        stavPodil = 0f

        // ---------- logo (OK = diagnostika, podržení OK = diagnostika s vývojem)
        val logoBox = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = vyber(Color.TRANSPARENT, radius = 18f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo)
                adjustViewBounds = true
            }, FrameLayout.LayoutParams(dp(if (telefon) 230 else 140), ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { Zaznam.ukaz(this@MainActivity) }
            setOnLongClickListener { Zaznam.ukaz(this@MainActivity, sVyvojem = true); true }
        }
        val logoRadek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        logoRadek.addView(logoBox)
        if (!telefon) {
            // po najetí na logo se vedle ukáže, co OK udělá
            val popisLoga = svisle().apply { visibility = View.INVISIBLE }
            popisLoga.addView(text(t("diagNadpis"), 21f, Color.WHITE, tucne = true).apply { letterSpacing = 0.1f })
            popisLoga.addView(text(t("diagLogoOk"), 15f, ZLUTA, tucne = true), odsazeni(top = 2))
            logoRadek.addView(popisLoga, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(18) })
            logoBox.setOnFocusChangeListener { _, maFokus -> popisLoga.visibility = if (maFokus) View.VISIBLE else View.INVISIBLE }
        }

        // ---------- povolení zobrazení přes jiné aplikace (televize) – hned pod logem
        var povolitBtn: View? = null
        val povoleni: View? = if (telefon) null else if (!Settings.canDrawOverlays(this)) {
            vodorovne().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = zaobleni(Color.parseColor("#2A0A0D"), 18f, Color.parseColor("#9A1A22"))
                setPadding(dp(14), dp(12), dp(18), dp(12))
                val btn = tlacitko(t("povolitBtn"), 19f) { pozadejOPovoleni() }.apply {
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), zaobleni(CERVENA, 16f, Color.WHITE))
                        addState(intArrayOf(), zaobleni(CERVENA, 16f, Color.parseColor("#FF9AA0")))
                    }
                    setPadding(dp(24), dp(14), dp(24), dp(14))
                }
                povolitBtn = btn
                addView(btn)
                // tlačítko pulzuje, ať si ho člověk všimne (dokud povolení chybí)
                android.animation.AnimatorSet().apply {
                    playTogether(
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_X, 1f, 1.06f).apply {
                            repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE },
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_Y, 1f, 1.06f).apply {
                            repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE })
                    duration = 700
                    start()
                }.also { puls ->
                    // obrazovka se zavřela / překreslila → pulzování zastavit
                    btn.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {}
                        override fun onViewDetachedFromWindow(v: View) { puls.cancel() }
                    })
                }
                val txt = svisle()
                txt.addView(text(t("povolitChybi"), 16.5f, Color.parseColor("#FF8A8E"), tucne = true))
                txt.addView(text(t("povolitJak"), 12.5f, Color.parseColor("#D8D8E0")), odsazeni(top = 3))
                addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(20) })
            }
        } else {
            // povoleno → jen malá zelená značka vpravo vedle loga (ať se celá obrazovka vejde)
            val znacka = vodorovne().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = zaobleni(ZELENA_TMAVA, 999f, ZELENA_RAMECEK)
                setPadding(dp(8), dp(6), dp(18), dp(6))
                addView(kolecko("✓", ZELENA_SVETLA, ZELENA_TMAVA))
                addView(text(t("povolenoNadpis"), 14f, ZELENA_SVETLA, tucne = true),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
                addView(text("  ·  " + t("povolenoText"), 12.5f, Color.parseColor("#D8D8E0")))
            }
            logoRadek.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            // dole v řádku loga – nahoře vpravo je značka BETA
            logoRadek.addView(znacka, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
            null
        }

        // ---------- jazyk
        val j = Texty.JAZYKY.firstOrNull { it.kod == jazyk } ?: Texty.JAZYKY[0]
        val jazykKarta = vodorovne().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = zaobleni(KARTA, 18f)
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        val jazykTexty = if (telefon) svisle() else vodorovne().apply { gravity = Gravity.BOTTOM }
        jazykTexty.addView(text("${j.vlajka}  ${j.nazev}", pismo(16f, 21f), Color.WHITE).jedenRadek(),
            if (telefon) plnaSirka() else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        jazykTexty.addView(text(t("zdrojKratce"), pismo(11.5f, 14f), TEXT2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (telefon) topMargin = dp(2) else leftMargin = dp(8)
            })
        jazykKarta.addView(jazykTexty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        jazykKarta.addView(tlacitkoSede(t("tvZmenitKratce")) { ukazJazyk() })

        // ---------- stav (pruh, který se při připojování plní zeleně)
        stavKarta = FrameLayout(this).apply {
            clipToOutline = true // zelená výplň se drží zaoblených rohů
            if (!telefon) {
                // televize: OK na pruhu zapne / pozastaví překlad (jako dřív)
                isFocusable = true
                setOnClickListener { prepniPreklad() }
            }
        }
        stavVypln = View(this).apply { setBackgroundColor(Color.parseColor("#4D22C55E")) }
        stavKarta.addView(stavVypln, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val stavObsah = vodorovne().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val ikony = FrameLayout(this)
        stavIkona = kolecko("✓", ZELENA_SVETLA, ZELENA_TMAVA).also { ikony.addView(it) }
        stavNacitani = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ZELENA_SVETLA)
            visibility = View.GONE
        }.also { ikony.addView(it, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)) }
        stavObsah.addView(ikony, LinearLayout.LayoutParams(dp(28), dp(28)))
        val stavTexty = svisle()
        stavText = text("", pismo(19f, 20f), Color.WHITE, tucne = true)
        stavPodtext = text("", pismo(12.5f, 15f), Color.parseColor("#D8D8E0")).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        stavTexty.addView(stavText.jedenRadek(), plnaSirka())
        stavTexty.addView(stavPodtext, odsazeni(top = 2))
        stavObsah.addView(stavTexty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
        // televize: OK na pruhu překlad vypne / zapne – ať to lidi vědí
        stavOkNapoveda = if (telefon) null else text("", 12.5f, ZLUTA, tucne = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            visibility = View.GONE
        }.also { stavObsah.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) }) }
        stavKarta.addView(stavObsah, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        val stavRadek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        stavRadek.addView(stavKarta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        // obě konzole spárované a zapnuté → malé červené tlačítko na přepnutí (po najetí bílý rámeček)
        prepnoutBtn = tlacitko("", pismo(14f, 18f)) { prepniKonzoli() }.apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = vyber(CERVENA, radius = 999f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        stavRadek.addView(prepnoutBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })

        // ---------- konzole
        val konzole = karta()
        val xbox = XboxUcet.konzole(this)
        psRadek = radekKonzole("PlayStation", udaje.sparovano, isPs = true) {
            if (telefon) ukazPripojeni() else upozorneniPredPrihlasenim()
        }.also { konzole.addView(it) }
        pripojitBtn = psRadek?.getChildAt(1)
        xboxRadekBox = radekKonzole("Xbox", xbox != null, isPs = false) {
            if (telefon) ukazPripojeniXbox() else upozorneniPredPrihlasenim(xbox = true)
        }.also { konzole.addView(it, odsazeni(top = 8)) }
        // telefon: najít televizi a převzít od ní spárování s PS5 (bez přihlašování účtu a PINu z PS5)
        if (telefon && !TvProfily(this).platforma.jenZobrazovac) {
            konzole.addView(tlacitkoZelene(t("prevzitZTv")) { prevzitSparovaniZTv() }.jedenRadek(), odsazeni(top = 12))
            // nespárovaný telefon a v síti je televize s PlayCaption → přihlašování schovat, ať nikdo neklikne špatně
            if (!udaje.sparovano) Propojeni.hledej(this, 2500) { nalez ->
                ui.post {
                    // jen spárovaná televize umí telefonu předat spárování; nespárovaná jen ukazuje titulky
                    if (nalez.any { it.sparovano == true } && obrazovka == "hlavni" && !isFinishing) {
                        pripojitBtn?.visibility = View.GONE
                        konzole.addView(text(t("pridatNalezenaTv"), 15f, ZELENA_SVETLA), odsazeni(top = 8))
                    }
                }
            }
        }
        // televize (spárovaná): přidat telefon – kód v okně, telefon ho jen opíše
        if (!telefon && udaje.sparovano) {
            val rz = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(10), 0, 0) }
            val pridane = prefsBeta.getString("pridaneZarizeni", "") ?: ""
            val popisTel = svisle().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            popisTel.addView(text("📱  " + t("telRadek"), 16f, Color.WHITE))
            popisTel.addView(if (pridane.isNotEmpty()) text("  ✔ $pridane", 16f, ZELENA_SVETLA) else text("  · " + t("telZadny"), 12.5f, TEXT2))
            rz.addView(popisTel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rz.addView(tlacitkoZeleneMale(t("pridatTelefon")) { pridatZarizeni() })
            konzole.addView(rz)
        }

        // ---------- co překládat
        val co = karta()
        co.addView(prepinac(t("dialogy"), { nast.dialogy }, { nast.dialogy = it }, t("ano"), t("ne"), t("dialogyPopis")))
        if (!telefon) {
            co.addView(cara())
            co.addView(prepinac(t("prekryvat"), { nast.prekryvat }, { nast.prekryvat = it }, t("ano"), t("ne"), t("prekryvatPopis")))
        }
        co.addView(cara())
        // menu a nápovědy se nepřekládají samy – v mobilní aplikaci si je každý přeloží žlutým rámečkem (Text)
        // úkoly mise / nápovědy do TV jen když je zapne přepínač v telefonu (výchozí vypnuto)
        if (!getSharedPreferences("gamelingo", MODE_PRIVATE).getBoolean("ukolyTv", false)) nast.napovedy = false
        run {
            val r = svisle()
            r.addView(text(t("napovedy"), pismo(16f, 20f), Color.WHITE).jedenRadek(), plnaSirka())
            r.addView(text(if (telefon) t("napovedyTelefon") else t("napovedyJenMobil"), pismo(12f, 15f), TEXT2), odsazeni(top = 2))
            co.addView(r)
        }

        // ---------- tip a poděkování (Chiaki / chiaki-ng, AGPLv3 – musí zůstat)
        val tip = text(t("tipPripojeni"), pismo(12.5f, 15f), TEXT2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        val zasluhy = text(t("zasluhy"), pismo(11f, 13f), TEXT2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            alpha = 0.85f
        }

        var dlazdiceTv: View? = null
        var dlazdiceTel: View? = null
        val obsah = svisle()
        if (telefon) {
            obsah.addView(logoRadek, odsazeni(bottom = 14))
            obsah.addView(stavRadek, odsazeni(bottom = 20))
            obsah.addView(sekce(t("hlTel1")))
            obsah.addView(konzole, odsazeni(bottom = 20))
            obsah.addView(sekce(t("hlTel2")))
            obsah.addView(jazykKarta, odsazeni(bottom = 20))
            obsah.addView(sekce(t("hlTel3")))
            obsah.addView(kartaVystupuTv(), odsazeni(bottom = 20))
            obsah.addView(sekce(t("hlTel4")))
            obsah.addView(co, odsazeni(bottom = 22))
            obsah.addView(text(t("hratZmackni"), 19f, ZLUTA, tucne = true).apply { gravity = Gravity.CENTER }, odsazeni(bottom = 8))
            obsah.addView(tlacitkoCervene(t("hratBtn")) {
                if (nejakaKonzole) startActivity(Intent(this, TelefonActivity::class.java)) else ukazPripojeni()
            }.apply { textSize = 28f * SKALA; setPadding(dp(28), dp(18), dp(28), dp(18)) })
            obsah.addView(tip, odsazeni(top = 16))
            obsah.addView(zasluhy, odsazeni(top = 10))
            obnovTvKartu()
        } else {
            // televize: mřížka – levý a pravý sloupec mají v každém řádku stejnou výšku, vše v jedné rovině
            obsah.addView(logoRadek, odsazeni(bottom = 6))
            povoleni?.let { obsah.addView(it, odsazeni(bottom = 12)) }
            obsah.addView(radekMrizky(bunka(t("hlJazyk"), jazykKarta), bunka(t("hlStav"), stavRadek)))
            obsah.addView(radekMrizky(bunka(t("hlKonzole"), konzole), bunka(t("coPrekladat"), co)), odsazeni(top = 12))

            // podpora – QR Platba
            val podpora: View = if (PODPORA_QR.isNotEmpty()) {
                val k = vodorovne().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = zaobleni(KARTA, 18f, Color.parseColor("#5A4F22"))
                    setPadding(dp(16), dp(12), dp(12), dp(12))
                }
                val txt = svisle()
                txt.addView(text(t("podporaNadpis"), 17f, Color.parseColor("#FFD54A"), tucne = true))
                txt.addView(text(t("podporaText"), 12f, Color.parseColor("#D8D8E0")).apply { setLineSpacing(0f, 1.2f) }, odsazeni(top = 4))
                // QR je česká QR Platba – slovenské banky ji nenačtou → IBAN k ručnímu zadání
                txt.addView(text(t("podporaIban"), 11f, Color.parseColor("#FFD54A")), odsazeni(top = 4))
                k.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                k.addView(ImageView(this).apply {
                    setImageBitmap(PrihlaseniTelefonem.qr(PODPORA_QR, 400))
                    background = zaobleni(Color.WHITE, 14f)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }, LinearLayout.LayoutParams(dp(104), dp(104)).apply { leftMargin = dp(14) })
                bunka(t("podpora"), k)
            } else View(this)

            // "Jak chceš hrát?" – dvě velké volby; doporučená (S telefonem) první
            val slaba = jeSlabaTv()
            dlazdiceTel = dlazdice("📱➜📺", t("hratTel"), if (slaba) t("hratTelDoporuceno") else t("hratTelPopis"), Color.parseColor("#2A2C3E")) { hratSTelefonem() }
            dlazdiceTv = dlazdice("📺", t("hratTv"), if (slaba) t("hratTvSlaba") else t("hratTvPopis"), CERVENA) {
                if (slaba) varovaniSlabaTv() else hratTelevizi()
            }
            // okraj kolem dlaždic, ať se zvětšená vybraná dlaždice neořízne
            val volby = vodorovne().apply { clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(6), dp(6), dp(6)) }
            volby.addView(dlazdiceTel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(16) })
            volby.addView(dlazdiceTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val hlavaHrat = vodorovne().apply { gravity = Gravity.BOTTOM }
            hlavaHrat.addView(sekceMala(t("hratJak")))
            hlavaHrat.addView(text(t("hratVyberKratce"), 14.5f, ZLUTA, tucne = true).apply { setPadding(dp(16), 0, 0, dp(9)) })
            val hrat = svisle().apply { clipChildren = false; clipToPadding = false }
            hrat.addView(hlavaHrat)
            hrat.addView(volby, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { leftMargin = -dp(6); rightMargin = -dp(6) })
            obsah.addView(radekMrizky(podpora, hrat), odsazeni(top = 12))
            obsah.clipChildren = false

            obsah.addView(tip, odsazeni(top = 26))
            obsah.addView(zasluhy, odsazeni(top = 8))
        }
        nastavObsah(obsah, sirka = ViewGroup.LayoutParams.MATCH_PARENT)

        // automaticky spustit, když je vše připravené a uživatel překlad nevypnul
        if (!telefon && !PrijemService.bezi && nejakaKonzole && Settings.canDrawOverlays(this) && AutoStart.jeZapnuto(this) && !RemotePlayService.aktivni) {
            val up = getSharedPreferences("uspora", MODE_PRIVATE)
            if (up.getBoolean("padlo", false) && prefsBeta.getString("rezimHry", "tv") == "tv") {
                // minule v režimu „Jen televize“ spadla → nepřipojovat se samo dokola, zeptat se
                up.edit().putBoolean("padlo", false).putBoolean("slaba", true).putBoolean("varovat", true).putBoolean("rychla", false).apply()
                varovaniSlabaTv(t("padloNadpis"), t("padloText"))
            } else {
                up.edit().putBoolean("padlo", false).apply()
                RemotePlayService.spustVse(this)
            }
        }
        obnovStav()
        ui.removeCallbacks(obnova)
        ui.postDelayed(obnova, 1000)
        if (!telefon) {
            // chybí povolení → ovladač rovnou na „Povolit“, jinak na naposledy zvolený způsob hraní
            if (povolitBtn != null) povolitBtn?.requestFocus()
            else (if (jeSlabaTv() || prefsBeta.getString("rezimHry", "tv") == "tel") dlazdiceTel else dlazdiceTv)?.requestFocus()
        }
    }

    /** Telefon: karta „Kde chceš titulky?“ (televize / jen tady) – obsah jako dřív. */
    private fun kartaVystupuTv(): View {
        val vystup = vodorovne().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = vyber(KARTA, radius = 18f)
            minimumHeight = dp(88)
            setPadding(dp(22), dp(16), dp(22), dp(16))
            isFocusable = true
            setOnClickListener { vyberVystupTv() }
        }
        val popis = svisle()
        tvNazev = text("", 21f, Color.WHITE, tucne = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { popis.addView(it) }
        tvPlatforma = text("", 14f, TEXT2).also { popis.addView(it, odsazeni(top = 4)) }
        tvStav = text("", 15f, TEXT2).also { popis.addView(it, odsazeni(top = 6)) }
        popis.addView(LgDevModeView(this) {
            val p = TvProfily(this)
            if (p.pouzeTelefon && Propojeni.Vysilac.cilIp == null) null
            else (if (Propojeni.Vysilac.cilIp != null) TvProfily.aktivniId else p.posledniId)?.let { p.najdi(it) }
        })
        vystup.addView(popis, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(16) })
        vystup.addView(tlacitkoSede(t("tvZmenitKratce")) { vyberVystupTv() })
        return vystup
    }

    /** Řádek konzole: název, stav (rozsvítí se zeleně, když je připravená) a tlačítko. */
    private fun radekKonzole(nazev: String, sparovano: Boolean, isPs: Boolean, akce: () -> Unit): LinearLayout {
        val radek = vodorovne().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        val nazevStav = if (telefon) svisle() else vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        val jmeno = text("🎮  $nazev", pismo(16f, 21f), Color.WHITE).jedenRadek()
        val stav = text("", pismo(16f, 16f), TEXT2, tucne = true).jedenRadek()
        nazevStav.addView(jmeno, if (telefon) plnaSirka() else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nazevStav.addView(stav, (if (telefon) plnaSirka() else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)).apply {
            if (telefon) topMargin = dp(2) else leftMargin = dp(8)
        })
        radek.addView(nazevStav, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        radek.addView(if (sparovano) tlacitkoSede(t("konZnovu"), akce) else tlacitkoZeleneMale(t("pripojit"), akce))
        if (isPs) psStav = stav else xboxStav = stav
        return radek
    }

    /** Nastaví vzhled řádku konzole: "ready" (zelený), "wait" (průhledně zelený rámeček), "chyba", "neutral", "off". */
    private fun vzhledKonzole(radek: LinearLayout?, stav: TextView?, druh: String, popis: String) {
        radek ?: return; stav ?: return
        val jmeno = ((radek.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView)
        stav.text = if (telefon) popis.replaceFirstChar { it.uppercase() } else "· $popis"
        when (druh) {
            "ready" -> { radek.background = zaobleni(ZELENA_TMAVA, 14f, ZELENA_RAMECEK); stav.setTextColor(ZELENA_SVETLA); jmeno?.setTextColor(Color.WHITE) }
            "wait" -> { radek.background = zaobleni(Color.parseColor("#22232C"), 14f, Color.parseColor("#7322C55E")); stav.setTextColor(Color.parseColor("#9FE8B4")); jmeno?.setTextColor(Color.WHITE) }
            "chyba" -> { radek.background = zaobleni(Color.parseColor("#2A1214"), 14f, Color.parseColor("#FF4A50")); stav.setTextColor(CERVENA_SVETLA); jmeno?.setTextColor(Color.WHITE) }
            "neutral" -> { radek.background = zaobleni(Color.parseColor("#22232C"), 14f); stav.setTextColor(TEXT2); jmeno?.setTextColor(Color.WHITE) }
            else -> { radek.background = zaobleni(Color.parseColor("#22232C"), 14f); stav.setTextColor(Color.parseColor("#8A8A96")); jmeno?.setTextColor(Color.parseColor("#8A8A96")) }
        }
        stav.typeface = if (druh == "off" || druh == "neutral") Typeface.DEFAULT else Typeface.DEFAULT_BOLD
    }

    /** Buňka mřížky: nadpis a pod ním obsah, který se natáhne na výšku řádku. */
    private fun bunka(nadpis: String, obsah: View) = svisle().apply {
        clipChildren = false; clipToPadding = false
        addView(sekceMala(nadpis))
        addView(obsah, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Řádek mřížky na televizi: vlevo a vpravo stejně vysoké buňky (vpravo o trochu širší). */
    private fun radekMrizky(vlevo: View, vpravo: View) = vodorovne().apply {
        clipChildren = false; clipToPadding = false
        addView(vlevo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(40) })
        addView(vpravo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
    }

    private fun cara() = View(this).apply { setBackgroundColor(Color.parseColor("#33334A")) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(9), 0, dp(9)) }
    }

    /** Kulaté kolečko se znakem (✓ / ! / •). */
    private fun kolecko(znak: String, barva: Int, barvaZnaku: Int) = TextView(this).apply {
        text = znak
        textSize = 14f * SKALA
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(barvaZnaku)
        background = zaobleni(barva, 999f)
        layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
    }

    /**
     * Text vždy na jednom řádku – na úzkém displeji se písmo samo zmenší (místo zalomení „An/o“).
     * Funguje jen tam, kde má text danou šířku (MATCH_PARENT nebo váha v řádku).
     */
    private fun <T : TextView> T.jedenRadek(): T = apply {
        maxLines = 1
        val sp = (textSize / resources.displayMetrics.scaledDensity).toInt()
        setAutoSizeTextTypeUniformWithConfiguration(minOf(8, sp - 1).coerceAtLeast(6), sp.coerceAtLeast(9), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    private fun plnaSirka() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Šedé zaoblené tlačítko (Změnit, Znovu připojit). */
    private fun tlacitkoSede(s: String, akce: () -> Unit) = tlacitko(s, pismo(14f, 18f), akce).apply {
        maxLines = 1
        gravity = Gravity.CENTER
        background = vyber(Color.parseColor("#2A2B36"), radius = 999f)
        setPadding(dp(18), dp(9), dp(18), dp(9))
    }

    private fun tlacitkoZeleneMale(s: String, akce: () -> Unit) = tlacitkoZelene(s, akce).apply {
        maxLines = 1
        if (!telefon) { textSize = 14f * SKALA; setPadding(dp(18), dp(9), dp(18), dp(9)) }
    }

    /** Nadpis sekce na hlavní obrazovce (na televizi o něco menší, ať se vše vejde). */
    private fun sekceMala(s: String) = sekce(s).apply { if (!telefon) textSize = 18f * SKALA }

    private fun obnovStav() {
        obnovTvKartu()
        if (!::stavText.isInitialized) return
        val sluzba = RemotePlayService.aktivni
        val spojeni = if (!nejakaKonzole) PrehledSpojeni("stavNesparovano", UrovenSpojeni.VYPNUTO)
            else RemotePlayService.prehledSpojeni()
        val chybaPrekryvu = if (!telefon && sluzba) OverlayService.chyba else null
        // na čem se hraje: připojeno → podle spojení, jinak podle volby
        val hrajuXbox = if (RemotePlayService.bezi || sluzba) RemotePlayService.naXboxu else RemotePlayService.preferXbox(this)
        val xboxSparovan = XboxUcet.konzole(this) != null
        // konzole, kterou pruh ukazuje (když je spárovaná jen jedna, tak ta)
        val naXboxu = when {
            !udaje.sparovano && xboxSparovan -> true
            udaje.sparovano && !xboxSparovan -> false
            else -> hrajuXbox
        }
        // telefon hraje v obrazovce Hrát – tady spárovaná konzole = připravená ke hraní
        val uroven = when {
            chybaPrekryvu != null -> UrovenSpojeni.CHYBA
            telefon && nejakaKonzole && !sluzba -> UrovenSpojeni.OK
            else -> spojeni.uroven
        }
        val podrobnosti = Texty.spojeni(nast.jazykNeboCs, spojeni) + chybaPrekryvu?.let {
            "\n" + Texty.spojeni(nast.jazykNeboCs, it)
        }.orEmpty()
        val (nadpis, podtext) = when (uroven) {
            UrovenSpojeni.OK -> t(if (naXboxu) "banOkXbox" else "banOkPs") to t("banOkText")
            UrovenSpojeni.CEKANI -> t(if (naXboxu) "banCekaXbox" else "banCekaPs") to podrobnosti
            UrovenSpojeni.CHYBA -> t(if (naXboxu) "banChybaXbox" else "banChybaPs") to podrobnosti
            else -> t(spojeni.klic) to ""
        }
        stavText.text = nadpis
        // nápověda: běží → „OK = vypnout“; vypnuto/pozastaveno už říká nadpis („zapni OK“)
        stavOkNapoveda?.let { n ->
            val bezi = nejakaKonzole && sluzba && !RemotePlayService.pozastaveno
            n.text = t("stavOkVypnout")
            n.visibility = if (bezi) View.VISIBLE else View.GONE
        }
        stavPodtext?.text = podtext
        stavPodtext?.visibility = if (podtext.isBlank()) View.GONE else View.VISIBLE

        val (pozadi, ramecek, barvaNadpisu) = when (uroven) {
            UrovenSpojeni.OK -> Triple(ZELENA_TMAVA, ZELENA_RAMECEK, ZELENA_SVETLA)
            UrovenSpojeni.CEKANI -> Triple(Color.parseColor("#14171A"), Color.parseColor("#8022C55E"), Color.WHITE)
            UrovenSpojeni.CHYBA -> Triple(Color.parseColor("#3A1012"), Color.parseColor("#FF4A50"), CERVENA_SVETLA)
            else -> Triple(KARTA, Color.parseColor("#3A3A44"), Color.WHITE)
        }
        fun nastavBarvy(p: Int, r: Int, n: Int) {
            stavText.setTextColor(n)
            stavKarta.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), zaobleni(p, 18f, Color.WHITE))
                addState(intArrayOf(), zaobleni(p, 18f, r))
            }
        }
        // vypnutí z běžícího stavu → pruh pomalu zhasne (jako když se stmívá světlo)
        val predchozi = stavBarvy
        val zhasnout = !telefon && uroven == UrovenSpojeni.VYPNUTO && predchozi != null &&
            (stavUrovenPosledni == UrovenSpojeni.OK || stavUrovenPosledni == UrovenSpojeni.CEKANI)
        if (zhasnout && predchozi != null) {
            stavZhasina?.cancel()
            val argb = android.animation.ArgbEvaluator()
            stavVypln?.animate()?.alpha(0f)?.setDuration(1200)?.start()
            stavZhasina = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    nastavBarvy(argb.evaluate(f, predchozi.first, pozadi) as Int,
                        argb.evaluate(f, predchozi.second, ramecek) as Int,
                        argb.evaluate(f, predchozi.third, barvaNadpisu) as Int)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { stavZhasina = null }
                })
                start()
            }
        } else if (stavZhasina == null) {
            nastavBarvy(pozadi, ramecek, barvaNadpisu)
        }
        stavBarvy = Triple(pozadi, ramecek, barvaNadpisu)
        stavNacitani?.visibility = if (uroven == UrovenSpojeni.CEKANI) View.VISIBLE else View.GONE
        stavIkona?.visibility = if (uroven == UrovenSpojeni.CEKANI) View.GONE else View.VISIBLE
        stavIkona?.apply {
            when (uroven) {
                UrovenSpojeni.OK -> { text = "✓"; background = zaobleni(ZELENA_SVETLA, 999f); setTextColor(ZELENA_TMAVA) }
                UrovenSpojeni.CHYBA -> { text = "!"; background = zaobleni(Color.parseColor("#FF4A50"), 999f); setTextColor(Color.parseColor("#3A1012")) }
                else -> { text = ""; background = zaobleni(Color.parseColor("#6A6A76"), 999f) }
            }
        }
        // výplň: při připojování se pomalu plní průhledně zeleně, po připojení se doplní a pruh zezelená
        if (uroven != stavUrovenPosledni) {
            stavUrovenPosledni = uroven
            stavAnimace?.cancel()
            val cil = when (uroven) { UrovenSpojeni.CEKANI -> 0.9f; UrovenSpojeni.OK -> 1f; else -> 0f }
            val doba = when (uroven) { UrovenSpojeni.CEKANI -> 15000L; UrovenSpojeni.OK -> 500L; else -> 0L }
            if (zhasnout) {
                // výplň už pomalu mizí se zhasínáním – vynulovat až potom
                ui.postDelayed({ if (stavUrovenPosledni == UrovenSpojeni.VYPNUTO) nastavVypln(0f) }, 1300)
            } else {
                stavVypln?.alpha = 1f
            }
            if (zhasnout) Unit
            else if (doba == 0L) nastavVypln(cil)
            else stavAnimace = android.animation.ValueAnimator.ofFloat(stavPodil, cil).apply {
                duration = doba
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { nastavVypln(it.animatedValue as Float) }
                if (uroven == UrovenSpojeni.OK) addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        stavVypln?.animate()?.alpha(0f)?.setDuration(500)?.start()
                    }
                })
                start()
            }
        }

        // řádky konzolí: rozsvítí se ta, ke které se připojuje / na které se hraje
        fun radek(jeXbox: Boolean): Pair<String, String> {
            val sparovana = if (jeXbox) xboxSparovan else udaje.sparovano
            if (!sparovana) return "off" to t("konNepripojeno")
            if (jeXbox != naXboxu) return "neutral" to t("konSparovano")
            return when (uroven) {
                UrovenSpojeni.OK -> "ready" to t("konPripraveno")
                UrovenSpojeni.CEKANI -> "wait" to t("konPripravuji")
                UrovenSpojeni.CHYBA -> "chyba" to t("konChyba")
                else -> "neutral" to t("konSparovano")
            }
        }
        radek(false).let { (druh, popis) -> vzhledKonzole(psRadek, psStav, druh, popis) }
        radek(true).let { (druh, popis) -> vzhledKonzole(xboxRadekBox, xboxStav, druh, popis) }

        // tlačítko ukazuje konzoli, na které se právě hraje (kliknutím → ta druhá)
        prepnoutBtn?.text = if (hrajuXbox) "⇄ Xbox" else "⇄ PlayStation"
        // tlačítko ⇄ jen když jsou zapnuté obě konzole (zjišťuje služba každých 20 s)
        // jen když překlad běží (jinak služba konzole nekontroluje a údaj by byl starý)
        val ukazat = sluzba && RemotePlayService.obeZapnute && udaje.sparovano && xboxSparovan
        prepnoutBtn?.let { b ->
            val v = if (ukazat) View.VISIBLE else View.GONE
            if (b.visibility != v) { if (!ukazat && b.isFocused) stavKarta.requestFocus(); b.visibility = v }
        }
    }

    private fun nastavVypln(podil: Float) {
        stavPodil = podil
        val v = stavVypln ?: return
        val sirka = (stavKarta.width * podil).toInt()
        if (stavKarta.width == 0) { if (obrazovka == "hlavni") stavKarta.post { nastavVypln(podil) }; return }
        v.layoutParams = v.layoutParams.apply { width = sirka }
        v.requestLayout()
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
        gravity = Gravity.CENTER_VERTICAL
        addView(text(ikona, 29f, Color.WHITE))
        addView(text(nadpis, 20f, Color.WHITE, tucne = true), odsazeni(top = 4))
        addView(text(popis, 12.5f, Color.parseColor("#DDDDDD")).apply { setLineSpacing(0f, 1.25f) }, odsazeni(top = 4))
    }

    /**
     * Je televize slabá? Pozná se z toho, že minule v režimu „Jen televize“ spadla ("varovat"),
     * nebo z paměti (do ~2 GB). Jedno pomalé čtení ("slaba") jen zapne úsporný režim, nevaruje.
     */
    private fun jeSlabaTv(): Boolean {
        val up = getSharedPreferences("uspora", MODE_PRIVATE)
        // po pádu v režimu „Jen televize“ → upozornit (jedno pomalé čtení ale ne – to umí i silná TV)
        if (up.getBoolean("varovat", false)) return true
        // už se při hraní ukázalo, že stíhá (10× po sobě rychlé čtení) → neupozorňovat, i když má 2 GB
        if (up.getBoolean("rychla", false)) return false
        return runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // televize „2 GB“ hlásí kvůli rezervě systému jen ~1,6–1,9 GB → hranice 2,5 GB pokryje všechny 2GB televize
            am.isLowRamDevice || info.totalMem < 2_500L * 1024 * 1024
        }.getOrDefault(false)
    }

    /** Tenký žlutočerný šikmý proužek (výstražná páska). */
    private fun vystraznyPruh(): View = object : View(this) {
        private val zluta = android.graphics.Paint().apply { color = Color.parseColor("#FFC800") }
        private val cerna = android.graphics.Paint().apply { color = Color.BLACK; isAntiAlias = true }
        private val cesta = android.graphics.Path()
        override fun onDraw(c: android.graphics.Canvas) {
            val h = height.toFloat()
            c.drawRect(0f, 0f, width.toFloat(), h, zluta)
            var x = -h
            while (x < width) {
                cesta.reset()
                cesta.moveTo(x, h); cesta.lineTo(x + h, 0f); cesta.lineTo(x + 2 * h, 0f); cesta.lineTo(x + h, h); cesta.close()
                c.drawPath(cesta, cerna)
                x += 2 * h
            }
        }
    }

    /** Slabá televize a přesto "Jen televize" → upozornit a nabídnout telefon. */
    private fun varovaniSlabaTv(nadpis: String = t("slabaNadpis"), zprava: String = t("slabaText")) {
        runCatching {
            // žlutočerný výstražný proužek (jako páska na stavbě) nad textem a pod ním
            val obsah = svisle().apply { setPadding(dp(24), dp(20), dp(24), dp(4)) }
            obsah.addView(vystraznyPruh(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            obsah.addView(text(nadpis, 24f, Color.parseColor("#FFC800"), tucne = true), odsazeni(top = 16))
            obsah.addView(text(zprava, 19f, Color.WHITE), odsazeni(top = 10, bottom = 16))
            obsah.addView(vystraznyPruh(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            android.app.AlertDialog.Builder(this)
                .setView(obsah)
                // Zpět = jen zavřít (dlaždici „S telefonem“ si člověk vybere sám), Pokračovat = jen televize
                .setPositiveButton(t("slabaTel"), null)
                .setNegativeButton(t("slabaPresto")) { _, _ -> hratTelevizi() }
                .show()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }.onFailure { hratTelevizi() }
    }

    /** "Jen televize" = dřívější Hotovo. */
    private fun hratTelevizi() {
        prefsBeta.edit().putString("rezimHry", "tv").apply()
        if (nejakaKonzole && Settings.canDrawOverlays(this)) {
            PrijemService.zastav(this)
            RemotePlayService.pozastaveno = false
            AutoStart.nastavZapnuto(this, true)
            run { AutoStart.uspech(this); RemotePlayService.spustVse(this, prevzit = true) }
            Oznameni.ukaz(this, t("hratInfo"))
        } else if (!nejakaKonzole) { ukazPripojeni(); return }
        moveTaskToBack(true)
    }

    /** "S telefonem" = televize jen ukazuje titulky poslané z telefonu. */
    private fun hratSTelefonem() {
        if (!Settings.canDrawOverlays(this)) return
        prefsBeta.edit().putString("rezimHry", "tel").apply()
        // televize se od PS5 úplně odpojí (PS5 si vezme telefon)
        RemotePlayService.pozastaveno = true
        AutoStart.nastavZapnuto(this, false)
        // zastavit jen když opravdu běží – jinak by se čtečka zbytečně spouštěla (slabou TV to shodí)
        if (RemotePlayService.aktivni) runCatching { startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP)) }
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
        listOf("📱" to "ngKrok1", "🔗" to "ngKrok2", "🎮" to "ngKrok3").forEachIndexed { i, (ikona, klic) ->
            if (i > 0) kroky.addView(text("➜", 40f, Color.parseColor("#555555")), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(18), 0, dp(18), 0) })
            val k = svisle().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = zaobleni(Color.parseColor("#1C1D28"), 26f).apply {
                    // krok 2 (spárování) je jen poprvé → žlutý rámeček, ať je vidět, že se týká rámečku pod ním
                    if (i == 1) setStroke(dp(2), Color.parseColor("#FFC24B"))
                }
                setPadding(dp(26), dp(18), dp(26), dp(18))
            }
            val nadpisKroku = "${t("ngKrokN")} ${i + 1}" + if (i == 1) " – ${t("ngKrok2N")}" else ""
            k.addView(text(nadpisKroku, 15f, CERVENA, tucne = true).apply { gravity = Gravity.CENTER })
            k.addView(text(ikona, 52f, Color.WHITE).apply { gravity = Gravity.CENTER }, odsazeni(top = 4))
            k.addView(text(t(klic), 20f, Color.WHITE, tucne = true).apply { gravity = Gravity.CENTER }, odsazeni(top = 4))
            kroky.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        sloupec.addView(kroky, odsazeni(top = 20))

        // jak spárovat telefon – obě cesty (přihlášená televize / přihlášení v telefonu)
        val par = svisle().apply {
            background = zaobleni(Color.parseColor("#161722"), 22f)
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        par.addView(text(t("ngParNadpis"), 18f, Color.parseColor("#FFC24B"), tucne = true))
        par.addView(text(t("ngPar1"), 17f, Color.WHITE), odsazeni(top = 4))
        par.addView(text(t("ngPar2"), 17f, Color.WHITE), odsazeni(top = 4))
        sloupec.addView(par, odsazeni(top = 16))

        val stav = text("", 24f, Color.WHITE, tucne = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(14), dp(40), dp(14))
        }
        sloupec.addView(stav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
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

        var bylPripojen = PrijemService.prehled().uroven == UrovenSpojeni.OK
        val obnovPrijem = object : Runnable {
            override fun run() {
                if (obrazovka != "prijem") return
                val prijem = PrijemService.prehled()
                stav.text = Texty.spojeni(nast.jazykNeboCs, prijem)
                stav.background = zaobleni(when (prijem.uroven) {
                    UrovenSpojeni.OK -> ZELENA
                    UrovenSpojeni.CHYBA -> Color.parseColor("#B71C1C")
                    else -> Color.parseColor("#B07A10")
                }, 999f)
                // telefon se právě připojil → sama přepnout na hru
                if (prijem.uroven == UrovenSpojeni.OK && !bylPripojen) moveTaskToBack(true)
                bylPripojen = prijem.uroven == UrovenSpojeni.OK
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

    private var tvNazev: TextView? = null
    private var tvPlatforma: TextView? = null
    private var tvStav: TextView? = null

    private fun obnovTvKartu() {
        if (!telefon) return
        val profily = TvProfily(this)
        val posila = Propojeni.Vysilac.cilIp != null
        val profil = (if (posila) TvProfily.aktivniId else profily.posledniId)?.let { profily.najdi(it) }
        val lokalni = !posila && profily.pouzeTelefon
        tvNazev?.text = when {
            posila -> Propojeni.Vysilac.cilJmeno.ifBlank { profil?.jmeno ?: t("tvZadnaVybrana") }
            lokalni -> t("tvJenZarizeni")
            else -> profil?.jmeno ?: t("tvZadnaVybrana")
        }
        tvPlatforma?.text = if (lokalni || profil == null) t("tvNastaveniPopis") else when (profil.platforma) {
            TvPlatforma.WEBOS -> "LG webOS"
            TvPlatforma.SAMSUNG -> "Samsung (Tizen)"
            TvPlatforma.ANDROID_TV -> "Android TV / Google TV"
            else -> t("tvPlatformaNeznama")
        }
        tvStav?.text = when {
            posila -> Texty.prenos(nast.jazykNeboCs, Propojeni.Vysilac.stavPrenosu)
            lokalni -> t("tvJenZde")
            profil != null -> t("tvPripravena")
            else -> t("tvVyberVystup")
        }
        tvStav?.setTextColor(if (!posila) TEXT2 else if (Propojeni.Vysilac.stavPrenosu.chyba != null)
            Color.parseColor("#FF7272") else Color.parseColor("#FFC24B"))
    }

    private var prepnoutBtn: TextView? = null
    private var xboxRadek: TextView? = null
    private var ps5Radek: TextView? = null
    private var xboxRadekText = ""
    private var ps5RadekText = ""

    /** Tlačítko ⇄: přepnout překlad na druhou konzoli. */
    private fun prepniKonzoli() {
        val hrajuXbox = if (RemotePlayService.bezi || RemotePlayService.aktivni) RemotePlayService.naXboxu else RemotePlayService.preferXbox(this)
        RemotePlayService.prepni(this, naXbox = !hrajuXbox)
        obnovStav()
    }

    /** Spárovaná aspoň jedna konzole (PS5 nebo Xbox). */
    private val nejakaKonzole get() = udaje.sparovano || XboxUcet.konzole(this) != null

    private fun prepniPreklad() {
        if (!nejakaKonzole) { ukazPripojeni(); return }
        if (telefon) { startActivity(Intent(this, TelefonActivity::class.java)); return }
        // vypnutí = jen pozastavit (spojení s PS5 zůstává, jinak by PS5 1–2 min hlásila "již se používá")
        if (RemotePlayService.aktivni && !RemotePlayService.pozastaveno) {
            RemotePlayService.pozastaveno = true
            AutoStart.nastavZapnuto(this, false)
        } else {
            RemotePlayService.pozastaveno = false
            AutoStart.nastavZapnuto(this, true)
            if (!RemotePlayService.aktivni) run { AutoStart.uspech(this); RemotePlayService.spustVse(this, prevzit = true) }
        }
        ui.postDelayed({ obnovStav() }, 600)
    }

    // ------------------------------------------------------------------ připojení ke konzoli

    private var nalezenaIp: String? = null

    /** Tlačítko „Připojit / Znovu spárovat“ (telefon ho schová, když najde televizi s PlayCaption). */
    private var pripojitBtn: View? = null

    /**
     * Telefon: „Vyhledat televizi“ → seznam nalezených televizí → klepnout na svoji → opsat kód z televize.
     * Nenajde nic → zkusit znovu nebo zadat adresu televize ručně (je vidět v okně na televizi).
     */
    private fun prevzitSparovaniZTv() {
        val cekej = android.app.AlertDialog.Builder(this).setMessage(t("prevzitHledam")).show()
        Propojeni.hledej(this, 3000) { seznam ->
            ui.post {
                runCatching { cekej.dismiss() }
                if (isFinishing) return@post
                if (seznam.isEmpty()) {
                    android.app.AlertDialog.Builder(this).setMessage(t("prevzitNenalezena"))
                        .setPositiveButton(t("pridatZnovu")) { _, _ -> prevzitSparovaniZTv() }
                        .setNeutralButton(t("pridatAdresa")) { _, _ -> zadejAdresuTv() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                } else {
                    android.app.AlertDialog.Builder(this).setTitle(t("pridatNalezena"))
                        .setItems(seznam.map { "📺  " + it.jmeno }.toTypedArray()) { _, i -> prevzitOd(seznam[i]) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
    }

    /** Ruční adresa televize (když se telefon a televize v síti „nevidí“). */
    private fun zadejAdresuTv() {
        val pole = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 24f
            gravity = Gravity.CENTER
            hint = "192.168.1.20"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(t("pridatAdresa"))
            .setMessage(t("pridatAdresaText"))
            .setView(pole)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ip = pole.text.toString().trim().replace(",", ".")
                if (ip.isNotEmpty()) prevzitOd(Propojeni.Televize("rucne", ip, ip))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- televize: „+ Přidat Android zařízení“

    /** Dočasný příjem (jen kvůli přidání zařízení), když televize zrovna nečeká na telefon. */
    private var pridaniPrijimac: Propojeni.Prijimac? = null

    /**
     * Okno na televizi: QR kód ke stažení aplikace, adresa televize a 4místný kód (platí 180 s).
     * Telefon klepne na „Vyhledat televizi“, vybere ji a opíše kód → okno samo zmizí.
     */
    private fun pridatZarizeni() {
        if (!PrijemService.bezi && pridaniPrijimac == null)
            pridaniPrijimac = runCatching { Propojeni.Prijimac(this, priTitulku = { _, _, _, _, _ -> }) }.getOrNull()

        val obsah = svisle().apply { setPadding(dp(24), dp(18), dp(24), dp(8)) }
        val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        if (STAHNOUT_URL.isNotEmpty()) radek.addView(android.widget.ImageView(this).apply {
            setImageBitmap(PrihlaseniTelefonem.qr(STAHNOUT_URL, 360))
        }, LinearLayout.LayoutParams(dp(170), dp(170)))
        val kroky = svisle().apply { setPadding(if (STAHNOUT_URL.isNotEmpty()) dp(22) else 0, 0, 0, 0) }
        kroky.addView(text(t(if (STAHNOUT_URL.isNotEmpty()) "pridatKrok1" else "pridatKrok1BezQr"), 18f, Color.WHITE))
        kroky.addView(text(t("pridatKrok2"), 18f, Color.WHITE), odsazeni(top = 10))
        kroky.addView(text(t("pridatKrok3"), 18f, Color.WHITE), odsazeni(top = 10))
        radek.addView(kroky, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        obsah.addView(radek)
        val kodText = text("", 52f, Color.parseColor("#3CFF6E"), tucne = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
        obsah.addView(kodText, odsazeni(top = 14))
        val odpocet = text("", 16f, TEXT2).apply { gravity = Gravity.CENTER }
        obsah.addView(odpocet)
        obsah.addView(text(t("pridatTvAdresa").replace("{ip}", PrihlaseniTelefonem.mojeIp(this) ?: "?"), 14f, TEXT2), odsazeni(top = 12))

        var okno: android.app.AlertDialog? = null
        val tikani = object : Runnable {
            override fun run() {
                val zbyva = ((Propojeni.Pridani.platiDo - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                if (Propojeni.Pridani.kod != null && zbyva > 0) {
                    kodText.text = Propojeni.Pridani.kod!!.toCharArray().joinToString(" ")
                    odpocet.text = t("pridatPlati").replace("{s}", zbyva.toString())
                    ui.postDelayed(this, 1000)
                } else {
                    kodText.text = "– – – –"
                    odpocet.text = t("pridatVyprsel")
                    okno?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.visibility = View.VISIBLE
                }
            }
        }
        fun novyKod() {
            Propojeni.Pridani.novy()
            okno?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE
            ui.removeCallbacks(tikani); tikani.run()
        }
        Propojeni.Pridani.hotovo = { jmeno ->
            ui.post {
                prefsBeta.edit().putString("pridaneZarizeni", jmeno).apply()
                runCatching { okno?.dismiss() }
                zprava(t("pridatHotovo").replace("{jmeno}", jmeno))
                if (obrazovka == "hlavni") ukazHlavni()
            }
        }
        okno = android.app.AlertDialog.Builder(this)
            .setTitle(t("pridatNadpis"))
            .setView(obsah)
            .setPositiveButton(t("pridatNovyKod"), null)
            .setNegativeButton(t("pridatZavrit"), null)
            .setOnDismissListener {
                ui.removeCallbacks(tikani)
                Propojeni.Pridani.zrus()
                pridaniPrijimac?.zavri(); pridaniPrijimac = null
            }
            .show()
        okno?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { novyKod() }
        novyKod()
    }

    private fun vyberVystupTv() {
        TvVyber(this, { tv, platforma ->
            val profily = TvProfily(this)
            profily.zapamatuj(tv.id, tv.jmeno, tv.ip, platforma)
            profily.zapamatujVyber(tv.id)
            obnovTvKartu()
            if (Propojeni.Vysilac.cilIp != null) android.widget.Toast.makeText(this, t("tvUlozena").replace("{tv}", tv.jmeno), android.widget.Toast.LENGTH_LONG).show()
        }, {
            TvProfily(this).pouzeTelefon = true
            obnovTvKartu()
        }, { ukazHlavni() }).ukaz()
    }

    private fun prevzitOd(tv: Propojeni.Televize) {
        if (!TvProfily(this).muzePrevzitSparovani(tv)) {
            android.app.AlertDialog.Builder(this).setTitle(tv.jmeno)
                .setMessage(t("lgPouzeVystup"))
                .setPositiveButton(t("tvZmenit")) { _, _ -> vyberVystupTv() }
                .setNegativeButton(t("zpet"), null).show()
            return
        }
        Thread {
            val odp = Propojeni.sparovaniDotaz(tv, "ZADOST\t${Propojeni.jmenoZarizeni(this)}")
            ui.post {
                if (isFinishing) return@post
                when {
                    odp == null -> zprava(t("prevzitNeodpovida"))
                    odp[0] == "CHYBA" -> android.app.AlertDialog.Builder(this).setMessage(t("prevzitNesparovano"))
                        .setPositiveButton(t("pridatSparovatTel")) { _, _ -> ukazPripojeni() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    else -> zadejKodZTv(tv)
                }
            }
        }.start()
    }

    private fun zadejKodZTv(tv: Propojeni.Televize) {
        val pole = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 28f
            gravity = Gravity.CENTER
            hint = "0000"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(t("prevzitKodNadpis"))
            .setMessage(t("prevzitKodText"))
            .setView(pole)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val kod = pole.text.toString().trim()
                Thread {
                    val odp = Propojeni.sparovaniDotaz(tv, "KOD\t$kod")
                    ui.post {
                        if (isFinishing) return@post
                        if (odp != null && odp[0] == "DATA" && odp.size >= 5) {
                            // PS5 (jen když ji televize má spárovanou)
                            if (odp[1] != "null" && odp[1].isNotEmpty() && odp[3].isNotEmpty()) {
                                val u = RemotePlayUdaje(this)
                                u.accountId = odp[1]
                                u.ipKonzole = odp[2]
                                u.registKey = android.util.Base64.decode(odp[3], android.util.Base64.NO_WRAP)
                                u.rpKey = android.util.Base64.decode(odp[4], android.util.Base64.NO_WRAP)
                            }
                            // Xbox (když ho televize má) – telefon se nemusí přihlašovat zvlášť
                            XboxUcet.prevezmi(this, odp)
                            zprava(t("prevzitHotovo"))
                            ukazHlavni()
                        } else if (odp == null) zprava(t("prevzitNeodpovida"))
                        else zprava(t("prevzitChybaKod"))
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun zprava(s: String) {
        runCatching { android.app.AlertDialog.Builder(this).setMessage(s).setPositiveButton(android.R.string.ok, null).show() }
    }

    /**
     * Televize: než se začne přihlašovat k PS účtu – upozornit, že s Android telefonem / tabletem
     * se v televizi přihlašovat nemusí (lidi na „Připojit“ kliknou automaticky).
     */
    private fun upozorneniPredPrihlasenim(xbox: Boolean = false) {
        val obsah = svisle().apply { setPadding(dp(28), dp(22), dp(28), dp(6)) }
        obsah.addView(text(t("upozorneniNadpis"), 26f, Color.parseColor("#FFC24B"), tucne = true))
        obsah.addView(text(if (xbox) t("upozorneniText").replace("PS ", "Xbox ") else t("upozorneniText"), 21f, Color.WHITE), odsazeni(top = 12))
        android.app.AlertDialog.Builder(this)
            .setView(obsah)
            .setNegativeButton(t("upozorneniZpet")) { _, _ ->
                // bude používat mobilní aplikaci → druhá tabulka: kam kliknout; po OK výběr na dlaždici „S telefonem“
                val o2 = svisle().apply { setPadding(dp(28), dp(22), dp(28), dp(6)) }
                o2.addView(text(t("upozorneni2Text"), 22f, Color.WHITE))
                android.app.AlertDialog.Builder(this)
                    .setView(o2)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setPositiveButton(t("upozorneniOk")) { _, _ -> if (xbox) ukazPripojeniXbox() else ukazPripojeni() }
            .show()
            .getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun ukazPripojeni(ukoncitPrihlaseni: Boolean = true) {
        if (ukoncitPrihlaseni) zastavServer()
        obrazovka = "pripojeni"
        val sloupec = svisle()
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("pripojitKonzoli"), 28f, Color.WHITE, tucne = true), odsazeni(bottom = 16))
        if (telefon && !TvProfily(this).platforma.jenZobrazovac) {
            val kTv = karta()
            kTv.addView(text(t("maTvNadpis"), 18f, Color.parseColor("#FFC24B"), tucne = true))
            kTv.addView(text(t("maTv"), 16f, Color.WHITE), odsazeni(top = 6))
            kTv.addView(tlacitkoZelene(t("prevzitZTv")) { prevzitSparovaniZTv() }, odsazeni(top = 10))
            // bez televize → sjet rovnou k přihlášení pod tímhle rámečkem
            kTv.addView(tlacitko(t("nemamTv"), 15f) {
                var v: View = kTv; var y = kTv.bottom
                while (v.parent is View && v.parent !is ScrollView) { v = v.parent as View; y += v.top }
                (v.parent as? ScrollView)?.smoothScrollTo(0, y)
            }, odsazeni(top = 8))
            sloupec.addView(kTv, odsazeni(bottom = 12))
        }

        // 0) než začneš – je potřeba druhý účet na PS5
        val k0 = karta()
        k0.addView(text(t("predemNadpis"), 20f, Color.parseColor("#FFC24B"), tucne = true))
        k0.addView(text(t(if (telefon) "predemTelefon" else "predem"), 17f, Color.WHITE), odsazeni(top = 6))
        sloupec.addView(k0, odsazeni(bottom = 12))

        // 1) účet PlayStation
        val k1 = karta()
        k1.addView(text(t(if (telefon) "krok1Telefon" else "krok1"), 18f, Color.WHITE))
        val maUcet = udaje.accountIdBajty() != null
        val prihlaseni = psnPokus?.vysledek
        val overuji = prihlaseni?.stav == PsnPokus.Stav.OVERUJE
        val chybaPsn = prihlaseni?.takeIf { it.stav == PsnPokus.Stav.CHYBA }?.popisChyby(jazyk)
        val ucetStav = text(if (overuji) t("psnOveruji") else chybaPsn ?: if (maUcet) t("ucetOk") else t("nejdrivUcet"),
            17f, if (chybaPsn != null) Color.parseColor("#FF8A8A") else if (maUcet && !overuji) Color.parseColor("#7DFF9A") else TEXT2)
        k1.addView(ucetStav, odsazeni(top = 6))
        k1.addView(tlacitkoCervene(if (maUcet) t("zmenitUcet") else t("prihlasit")) { ukazPsnTelefonem() }, obal(top = 8))
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
        k3.addView(text(t(if (telefon) "krok3Telefon" else "krok3"), 18f, Color.WHITE))
        val lzeParovat = psnPokus?.lzeParovat(maUcet) ?: maUcet
        val pin = EditText(this).apply {
            hint = "12345678"
            isEnabled = lzeParovat
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
                udaje.accountIdBajty() == null || psnPokus?.lzeParovat(true) == false -> parovaniStav.text = t("nejdrivUcet")
                ip == null -> parovaniStav.text = t("nenalezena")
                cislo.length != 8 -> parovaniStav.text = t("pinChyba")
                else -> {
                    parovaniStav.text = t("paruji")
                    Parovani.sparuj(this, ip, cislo.toInt(), log = {}, hotovo = { ok, zprava ->
                        runOnUiThread {
                            parovaniStav.text = if (ok) "✔ ${t("sparovano")}" else "✘ $zprava"
                            if (ok) {
                                AutoStart.nastavZapnuto(this, true)
                                if (!telefon && Settings.canDrawOverlays(this)) run { AutoStart.uspech(this); RemotePlayService.spustVse(this, prevzit = true) }
                                ui.postDelayed({ ukazHlavni() }, 1200)
                            }
                        }
                    })
                }
            }
        }.apply { isEnabled = lzeParovat; alpha = if (lzeParovat) 1f else 0.5f }, obal(top = 10))
        k3.addView(parovaniStav, odsazeni(top = 6))
        sloupec.addView(k3, odsazeni(bottom = 12))

        sloupec.addView(tlacitko(t("zpet"), 18f) { ukazHlavni() }.apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal())
        nastavObsah(sloupec, sirka = sirkaObsahu)
        hledej()
    }

    // ------------------------------------------------------------------ přihlášení PSN (prohlížeč)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ukazPsn() {
        val pokus = pripravPsnPokus()
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
                    zachytKod(request.url.toString(), pokus)

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    zachytKod(url, pokus)
                }
            }
        }
        cookies.setAcceptThirdPartyCookies(web, true)
        setContentView(web)
        web.requestFocus()
        web.loadUrl(PsnPrihlaseni.ADRESA_PRIHLASENI)
    }

    /** Po přihlášení Sony přesměruje na adresu s kódem → vyměnit za Account ID. */
    private fun zachytKod(adresa: String, pokus: PsnPokus): Boolean {
        val kod = PsnPrihlaseni.kodZAdresy(adresa) ?: return false
        pokus.prihlas(kod)
        return true
    }

    private fun pripravPsnPokus(): PsnPokus {
        zastavServer()
        lateinit var pokus: PsnPokus
        pokus = PsnPokus(PsnPrihlaseni::accountId, udaje::ulozAccountId) {
            runOnUiThread {
                if (psnPokus !== pokus || isFinishing || isDestroyed) return@runOnUiThread
                if (pokus.stav == PsnPokus.Stav.OVERUJE && obrazovka != "pripojeni") psnStav?.text = t("psnOveruji")
                else if (obrazovka in listOf("psn", "psnqr", "pripojeni")) ukazPripojeni(ukoncitPrihlaseni = false)
            }
        }
        psnPokus = pokus
        return pokus
    }

    // ------------------------------------------------------------------ přihlášení přes telefon (QR)

    private var server: PrihlaseniTelefonem? = null
    private var psnPokus: PsnPokus? = null
    private var psnStav: TextView? = null

    private fun zastavServer() {
        psnPokus?.zrus()
        psnPokus = null
        psnStav = null
        server?.zastav()
        server = null
    }

    private fun ukazPsnTelefonem() {
        val pokus = pripravPsnPokus()
        obrazovka = "psnqr"
        val cesky = nast.jazykNeboCs in listOf("cs", "sk")
        val stranka = PrihlaseniTelefonem(cesky, pokus)
        server = stranka.also { it.spust() }
        val host = if (telefon) "127.0.0.1" else PrihlaseniTelefonem.mojeIp(this) ?: "?"
        val adresa = "http://$host:${PrihlaseniTelefonem.PORT}${stranka.cesta}"

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
        psnStav = text(t("qrCekam"), 17f, TEXT2)
        k.addView(psnStav, odsazeni(top = 14))
        sloupec.addView(k, odsazeni(bottom = 12))
        // přihlášení přímo v prohlížeči televize Sony často odmítne (chyba) → na televizi nenabízet,
        // jen přes telefon (QR kód)
        if (telefon) sloupec.addView(tlacitko(t("qrVProhlizeci"), 17f) { ukazPsn() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 4))
        sloupec.addView(tlacitko(t("zpet"), 17f) { ukazPripojeni() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 10))
        nastavObsah(sloupec, sirka = sirkaObsahu)
    }

    // ------------------------------------------------------------------ Xbox (stejný postup jako PS5)

    private var xboxRelace: XboxSpojeni.Relace? = null
    private var xboxNalezena: XboxSpojeni.Konzole? = null

    private fun ukazPripojeniXbox() {
        zastavServer()
        obrazovka = "xbox"
        val sloupec = svisle()
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("xboxNadpis"), 28f, Color.WHITE, tucne = true), odsazeni(bottom = 16))
        if (telefon && !TvProfily(this).platforma.jenZobrazovac) {
            val kTv = karta()
            kTv.addView(text(t("maTvNadpis"), 18f, Color.parseColor("#FFC24B"), tucne = true))
            kTv.addView(text(t("maTv"), 16f, Color.WHITE), odsazeni(top = 6))
            kTv.addView(tlacitkoZelene(t("prevzitZTv")) { prevzitSparovaniZTv() }, odsazeni(top = 10))
            // bez televize → sjet rovnou k přihlášení pod tímhle rámečkem
            kTv.addView(tlacitko(t("nemamTv"), 15f) {
                var v: View = kTv; var y = kTv.bottom
                while (v.parent is View && v.parent !is ScrollView) { v = v.parent as View; y += v.top }
                (v.parent as? ScrollView)?.smoothScrollTo(0, y)
            }, odsazeni(top = 8))
            sloupec.addView(kTv, odsazeni(bottom = 12))
        }

        // 0) než začneš – nastavení na Xboxu
        val k0 = karta()
        k0.addView(text(t("predemNadpis"), 20f, Color.parseColor("#FFC24B"), tucne = true))
        k0.addView(text(t("xboxPredem"), 17f, Color.WHITE), odsazeni(top = 6))
        sloupec.addView(k0, odsazeni(bottom = 12))

        // 1) Microsoft účet
        val prihlasen = XboxUcet.prihlasen(this)
        val k1 = karta()
        k1.addView(text(t("xboxKrok1"), 18f, Color.WHITE))
        val jmeno = XboxUcet.jmeno(this)
        k1.addView(text(if (prihlasen) t("xboxUcetOk") + (if (jmeno.isNotEmpty()) " ($jmeno)" else "") else "", 17f, Color.parseColor("#7DFF9A")), odsazeni(top = 6))
        k1.addView(tlacitkoCervene(if (prihlasen) t("zmenitUcet") else t("prihlasit")) {
            XboxUcet.odhlas(this); ukazXboxKod()
        }, obal(top = 8))
        sloupec.addView(k1, odsazeni(bottom = 12))

        // 2) hledání Xboxu (na účtu)
        val k2 = karta()
        k2.addView(text(t("xboxKrok2"), 18f, Color.WHITE))
        val hledaniStav = text("", 17f, TEXT2)
        k2.addView(hledaniStav, odsazeni(top = 6))
        fun hledej() {
            xboxNalezena = null
            if (!XboxUcet.prihlasen(this)) { hledaniStav.text = t("nejdrivUcet"); return }
            hledaniStav.text = t("xboxHledam"); hledaniStav.setTextColor(TEXT2)
            Thread {
                val seznam = runCatching { XboxSpojeni.konzole(this) }
                runOnUiThread {
                    if (obrazovka != "xbox") return@runOnUiThread
                    val x = seznam.getOrNull()?.firstOrNull()
                    xboxNalezena = x
                    hledaniStav.text = when {
                        x != null -> "✔ ${t("xboxNalezen")}: ${x.jmeno}"
                        seznam.isFailure -> "✘ " + (seznam.exceptionOrNull()?.message ?: "")
                        else -> "✘ ${t("xboxNenalezen")}"
                    }
                    hledaniStav.setTextColor(if (x != null) Color.parseColor("#7DFF9A") else Color.parseColor("#FF8A8A"))
                }
            }.start()
        }
        k2.addView(tlacitko(t("hledatZnovu"), 18f) { hledej() }.apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 8))
        sloupec.addView(k2, odsazeni(bottom = 12))

        // 3) připojení – zkusí spojení a počká na obraz
        val k3 = karta()
        k3.addView(text(t("xboxKrok3"), 18f, Color.WHITE))
        val pripojStav = text("", 17f, TEXT2)
        k3.addView(tlacitkoZelene(t("xboxPripojitBtn")) {
            val x = xboxNalezena
            when {
                !XboxUcet.prihlasen(this) -> pripojStav.text = t("nejdrivUcet")
                x == null -> pripojStav.text = t("xboxNenalezen")
                xboxRelace != null -> {}
                else -> {
                    pripojStav.setTextColor(TEXT2)
                    var hotovo = false
                    val r = XboxSpojeni.Relace(this, x.id, priStavu = { st ->
                        runOnUiThread {
                            if (obrazovka != "xbox" || hotovo) return@runOnUiThread
                            pripojStav.text = st
                            if (st.startsWith("✔")) {
                                hotovo = true
                                pripojStav.setTextColor(Color.parseColor("#7DFF9A"))
                                XboxUcet.ulozKonzoli(this, x.id, x.jmeno)
                                xboxRelace?.zastav(); xboxRelace = null
                                ui.postDelayed({ if (obrazovka == "xbox") ukazHlavni() }, 1500)
                            } else if (st.startsWith("✘")) {
                                pripojStav.setTextColor(Color.parseColor("#FF8A8A"))
                                xboxRelace = null
                            }
                        }
                    })
                    xboxRelace = r
                    r.spust()
                }
            }
        }, obal(top = 10))
        k3.addView(pripojStav, odsazeni(top = 6))
        sloupec.addView(k3, odsazeni(bottom = 12))

        sloupec.addView(tlacitko(t("zpet"), 18f) { xboxRelace?.zastav(); xboxRelace = null; ukazHlavni() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal())
        nastavObsah(sloupec, sirka = sirkaObsahu)
        hledej()
    }

    /** Přihlášení Microsoft účtem: kód na microsoft.com/link (QR na televizi, odkaz v telefonu). */
    private fun ukazXboxKod() {
        obrazovka = "xboxkod"
        val sloupec = svisle()
        sloupec.addView(nadpisLogo())
        sloupec.addView(text(t("xboxKodNadpis"), 28f, Color.WHITE, tucne = true), odsazeni(bottom = 12))
        val k = karta()
        val stav = text(t("xboxKodNacitam"), 17f, TEXT2)
        k.addView(stav)
        sloupec.addView(k, odsazeni(bottom = 12))
        sloupec.addView(tlacitko(t("zpet"), 17f) { ukazPripojeniXbox() }
            .apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }, obal(top = 10))
        nastavObsah(sloupec, sirka = sirkaObsahu)

        Thread {
            val kod = runCatching { XboxUcet.novyKod() }
            runOnUiThread {
                if (obrazovka != "xboxkod") return@runOnUiThread
                val kd = kod.getOrNull()
                if (kd == null) { stav.text = "✘ " + (kod.exceptionOrNull()?.message ?: ""); return@runOnUiThread }
                k.removeAllViews()
                val velkyKod = text(kd.uzivatelskyKod, 44f, Color.parseColor("#7DFF9A"), tucne = true).apply {
                    letterSpacing = 0.15f
                }
                if (telefon) {
                    k.addView(text(t("xboxKodTelefon"), 18f, Color.WHITE))
                    k.addView(velkyKod, odsazeni(top = 12))
                    k.addView(tlacitkoCervene(t("xboxOtevritOdkaz")) {
                        runCatching {
                            val cb = getSystemService(android.content.ClipboardManager::class.java)
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("kod", kd.uzivatelskyKod))
                        }
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(kd.adresa))) }
                    }, obal(top = 12))
                } else {
                    val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
                    radek.addView(ImageView(this).apply {
                        runCatching { setImageBitmap(PrihlaseniTelefonem.qr(kd.adresa, 512)) }
                    }, LinearLayout.LayoutParams(dp(330), dp(330)).apply { rightMargin = dp(30) })
                    val popis = svisle()
                    popis.addView(text(t("xboxKodTv"), 19f, Color.WHITE))
                    popis.addView(text(kd.adresa.removePrefix("https://").removePrefix("www."), 22f, Color.WHITE, tucne = true), odsazeni(top = 12))
                    popis.addView(velkyKod, odsazeni(top = 10))
                    radek.addView(popis, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    k.addView(radek)
                }
                // přihlášení „klíčem“ (passkey) v prohlížeči telefonu často nejde → poradit jinou cestu
                k.addView(text(t("xboxKodTip"), 16f, Color.parseColor("#FFC24B")).apply { setLineSpacing(0f, 1.2f) }, odsazeni(top = 12))
                k.addView(text(t("qrCekam"), 17f, TEXT2), odsazeni(top = 10))
                Thread {
                    val ok = XboxUcet.cekejNaPrihlaseni(this, kd) { obrazovka != "xboxkod" || isFinishing }
                    runOnUiThread {
                        if (obrazovka != "xboxkod") return@runOnUiThread
                        if (ok) ukazPripojeniXbox()
                        else android.widget.Toast.makeText(this, t("ucetChyba"), android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }.start()
    }

    // ------------------------------------------------------------------ povolení

    /** Zkusil uživatel otevřít povolení? (po návratu bez povolení → ukázat návod) */
    private var zkouselPovoleni = false

    private fun pozadejOPovoleni() {
        zkouselPovoleni = true
        // co nejblíž k povolení: 1) stránka povolení pro PlayCaption, 2) seznam aplikací s tímhle povolením,
        // 3) nastavení aplikace PlayCaption (tam bývá „Zobrazovat přes jiné aplikace“), 4) až pak návod
        val pokusy = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in pokusy) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        // televize stránku s povolením nemá vůbec (schovaná) → návod
        ukazNavodPovoleni()
    }

    /** Návod, když televize povolení "Zobrazovat přes jiné aplikace" schovává. */
    private fun ukazNavodPovoleni() {
        zkouselPovoleni = false
        val ip = runCatching { PrihlaseniTelefonem.mojeIp(this) }.getOrNull() ?: "IP_TELEVIZE"
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle(t("povoleniNavodNadpis"))
                .setMessage(t("povoleniNavod").replace("%IP%", ip))
                .setPositiveButton("OK", null)
                .show()
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

    /** Řádek "popis …… [Ano | Ne]" – OK přepíná, zeleně svítí zvolená možnost. */
    private fun prepinac(popis: String, cti: () -> Boolean, zapis: (Boolean) -> Unit, zap: String, vyp: String, podpis: String? = null): View {
        val radek = vodorovne().apply { gravity = Gravity.CENTER_VERTICAL }
        val texty = svisle()
        texty.addView(text(popis, pismo(16f, 20f), Color.WHITE).jedenRadek(), plnaSirka())
        // malé šedé vysvětlení pod názvem (co přesně se tím překládá)
        if (!podpis.isNullOrBlank()) texty.addView(text(podpis, pismo(12f, 14f), TEXT2), odsazeni(top = 2))
        radek.addView(texty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        val velikost = pismo(13.5f, 18f)
        val anoTxt = text(zap, velikost, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; minWidth = dp(64); setPadding(dp(16), dp(7), dp(16), dp(7)) }
        val neTxt = text(vyp, velikost, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; minWidth = dp(64); setPadding(dp(16), dp(7), dp(16), dp(7)) }
        val pill = vodorovne().apply {
            isFocusable = true
            isClickable = true
            background = vyber(Color.parseColor("#2A2B36"), radius = 999f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(anoTxt, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(neTxt, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun obnov() {
            val ano = cti()
            anoTxt.background = if (ano) zaobleni(ZELENA, 999f) else null
            neTxt.background = if (!ano) zaobleni(SEDA, 999f) else null
            anoTxt.typeface = if (ano) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            neTxt.typeface = if (!ano) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
