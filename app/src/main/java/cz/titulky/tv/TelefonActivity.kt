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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PlayCaption na telefonu: černá obrazovka, na ní velkým písmem překlad toho, co se ve hře říká.
 * Telefon stojí u televize, displej nezhasíná. Zavřením obrazovky se spojení s PS5 ukončí.
 */
class TelefonActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val nast by lazy { Nastaveni(this) }
    private fun t(klic: String) = Texty.t(nast.jazykNeboCs, klic)
    private val prefs by lazy { getSharedPreferences("gamelingo", MODE_PRIVATE) }

    private lateinit var titulek: TextView
    private lateinit var napoveda: TextView
    private lateinit var stav: TextView
    private lateinit var tecka: View
    private lateinit var tvTlacitko: TextView
    private lateinit var tip: TextView

    /** Velikosti písma – ťuknutím na text se přepínají. */
    private val VELIKOSTI = floatArrayOf(28f, 36f, 46f)
    private var velikost: Int
        get() = prefs.getInt("telefonPismo", 1).coerceIn(0, VELIKOSTI.size - 1)
        set(v) = prefs.edit().putInt("telefonPismo", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pady.hlidej(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Volby.nacti(this)
        // televize musí znát polohu rámečku voleb (český seznam ukazuje vedle něj)
        Propojeni.Vysilac.trvaleOblast = Volby.zapnuto && Volby.ukazatNaTv
        Propojeni.Vysilac.upravujiOblast(Volby.oblast, ukazat = false)
        postav()
        // spustit spojení s PS5 (bez kreslení přes jiné aplikace)
        RemotePlayService.pozastaveno = false
        TelefonTitulky.nastav("", 0)
        TelefonTitulky.napoveda = ""
        TelefonTitulky.odpojeno = false
        // televize, která čeká na titulky z telefonu, se najde a připojí sama
        Propojeni.Vysilac.nastavCil(null, "")
        obnovTvTlacitko()
        ui.postDelayed(hledaniTv, 500)
        // převzít PS5 – když je připojená televize, sama se odpojí
        runCatching { startForegroundService(Intent(this, RemotePlayService::class.java).putExtra(RemotePlayService.EXTRA_PREVZIT, true)) }
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
                        val posledni = prefs.getString("tvId", null)
                        val tv = seznam.firstOrNull { it.id == posledni } ?: seznam.singleOrNull()
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

    private fun pripojTv(tv: Propojeni.Televize) {
        Propojeni.Vysilac.nastavCil(tv.ip, tv.jmeno)
        prefs.edit().putString("tvId", tv.id).apply()
        android.widget.Toast.makeText(this, t("tvPosilam").replace("{tv}", tv.jmeno), android.widget.Toast.LENGTH_LONG).show()
        obnovTvTlacitko()
    }

    /**
     * Telefon odložený (zamčený / jiná aplikace) → za 1 min spojení s PS5 ukončit.
     * Zamčený telefon totiž nedostane zprávu "PS5 si bere televize" (Wi-Fi spí) a držel by PS5
     * dál – televize by se pak nemohla připojit ("already in use").
     */
    private val odlozeno = Runnable {
        if (!isFinishing) finish()
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(odlozeno)
    }

    override fun onStop() {
        if (!isChangingConfigurations && !isFinishing) ui.postDelayed(odlozeno, 60_000)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) zastav()
        super.onDestroy()
    }

    private fun zastav() {
        // televizi říct, ať titulek schová, a přestat jí posílat
        Propojeni.Vysilac.zmena("", 0, "")
        Propojeni.Vysilac.nastavCil(null, "")
        runCatching {
            startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP))
        }
    }

    private fun obnovTvTlacitko() {
        if (!::tvTlacitko.isInitialized) return
        val cil = Propojeni.Vysilac.cilIp
        tvTlacitko.text = if (cil != null) "📺 TV ✔" else "📺 TV"
        tvTlacitko.background = zaobleni(Color.parseColor(if (cil != null) "#14A23C" else "#333338"))
    }

    /** Najít televize s PlayCaption v síti a vybrat, kam posílat titulky. */
    private fun vyberTelevizi() {
        val hledam = android.app.AlertDialog.Builder(this)
            .setTitle(t("tvVyber"))
            .setMessage(t("tvHledam"))
            .show()
        Propojeni.hledej(this) { seznam ->
            ui.post {
                if (isFinishing) return@post
                runCatching { hledam.dismiss() }
                val polozky = seznam.map { "📺  " + it.jmeno } + ("📱  " + t("tvJenTelefon"))
                android.app.AlertDialog.Builder(this)
                    .setTitle(t(if (seznam.isEmpty()) "tvNenalezena" else "tvVyber"))
                    .setItems(polozky.toTypedArray()) { _, i ->
                        val tv = seznam.getOrNull(i)
                        if (tv != null) {
                            rucneJenTelefon = false
                            pripojTv(tv)
                        } else {
                            rucneJenTelefon = true
                            Propojeni.Vysilac.zmena("", 0, "")
                            Propojeni.Vysilac.nastavCil(null, "")
                        }
                        obnovTvTlacitko()
                    }
                    .show()
            }
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun zaobleni(barva: Int) = GradientDrawable().apply {
        setColor(barva)
        cornerRadius = 999f * resources.displayMetrics.density
    }

    // ---------------------------------------------------------------- poloha titulků na televizi

    /** (střed X, spodní hrana Y, šířka, velikost písma) – stejné jako výchozí poloha na televizi. */
    private var rozvrh = floatArrayOf(0.5f, 0.93f, 0.62f, 1f)
    private lateinit var upravy: LinearLayout

    private fun nactiRozvrh() {
        rozvrh = floatArrayOf(
            prefs.getFloat("tvX", 0.5f), prefs.getFloat("tvY", 0.93f),
            prefs.getFloat("tvSirka", 0.62f), prefs.getFloat("tvPismo", 1f))
    }

    private fun ulozAPosliRozvrh(ukazat: Boolean = true) {
        prefs.edit().putFloat("tvX", rozvrh[0]).putFloat("tvY", rozvrh[1])
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
        var chytX = 0f; var chytY = 0f
        obrazovka.setOnTouchListener { v, e ->
            val w = v.width.toFloat(); val h = v.height.toFloat()
            if (e.action == android.view.MotionEvent.ACTION_DOWN) ui.removeCallbacks(konecUprav)
            // prst puštěn → rámečky na televizi za chvilku schovat (při tažení je vidět, kam to jde)
            if (e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL)
                ui.postDelayed(konecUprav, 1000)
            if (w > 0 && h > 0 && e.action == android.view.MotionEvent.ACTION_DOWN) {
                val o = Volby.oblast
                val fx = e.x / w; val fy = e.y / h
                tahamVolby = Volby.zapnuto && fx in o[0]..o[2] && fy in o[1]..o[3]
                chytX = fx - o[0]; chytY = fy - o[1]
            }
            if (w > 0 && h > 0 && tahamVolby) {
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
        posuvnik(t("tvSirka"), 20, 100, { (rozvrh[2] * 100).toInt() }, { rozvrh[2] = it / 100f })
        posuvnik(t("tvPismo"), 50, 250, { (rozvrh[3] * 100).toInt() }, { rozvrh[3] = it / 100f })

        // ---- volby odpovědí: vypínač + velikost žlutého rámečku
        obal.addView(TextView(this).apply {
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        if (Volby.zapnuto) {
            obal.addView(TextView(this).apply {
                text = t("volbyNavod")
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, dp(6), 0, 0)
            })
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
            maxLines = 2
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
        // posílat titulky i do televize?
        tvTlacitko = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { vyberTelevizi() }
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
            addView(tvTlacitko, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
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
        }
        val horni = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111114"))
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            addView(FrameLayout(this@TelefonActivity).apply {
                addView(titulek, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(upravy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(napoveda, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(koren)
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
            stav.text = if (!RemotePlayService.bezi && (duvod.contains("ukončeno", true) || duvod.contains("✘")))
                t(klic) + "\n" + duvod else t(klic)
            tecka.background = zaobleni(barva)

            // titulky jdou do televize → telefon jen ukazuje stav a ztlumí displej (šetří baterii)
            val doTv = Propojeni.Vysilac.cilIp != null
            if (doTv != posledniDoTv) {
                posledniDoTv = doTv
                upravy.visibility = if (doTv) View.VISIBLE else View.GONE
                tip.visibility = if (doTv) View.GONE else View.VISIBLE
                if (doTv) {
                    titulek.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                    titulek.textSize = 19f
                } else nastavVelikost(titulek)
                if (doTv) Propojeni.Vysilac.nastavRozvrh(rozvrh.copyOf())
                window.attributes = window.attributes.apply {
                    screenBrightness = if (doTv) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                posledni = null
            }
            if (doTv) {
                val s = t("tvBezi") + "\n" + t("tvTip")
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
                    titulek.text = text
                    titulek.setTextColor(Color.WHITE)
                }
            }
            val n = if (nast.napovedy) TelefonTitulky.napoveda else ""
            napoveda.text = n
            napoveda.visibility = if (n.isEmpty()) View.GONE else View.VISIBLE
            ui.postDelayed(this, 100)
        }
    }
}
