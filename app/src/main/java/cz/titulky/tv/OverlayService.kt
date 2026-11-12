package cz.titulky.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Služba, která zobrazuje titulky přes obraz jiných aplikací (a později přes HDMI vstup).
 * Text se mění zasláním Intentu s akcí ACTION_SET_TEXT (použijeme v krocích 3-4).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var textView: TextView? = null
    /** Druhá, stejně velká vrstva pod titulkem – zdvojí neprůhlednost, aby anglický text neprosvítal. */
    private var podklad: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        for (v in listOfNotNull(textView, podklad)) { v.text = ""; v.visibility = android.view.View.INVISIBLE }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        // bez povolení "Zobrazovat přes jiné aplikace" by addView aplikaci shodil (BadTokenException)
        // → nespadnout, jen titulky neukazovat; aplikace o povolení požádá na hlavní obrazovce
        if (!android.provider.Settings.canDrawOverlays(this) || runCatching { addOverlay() }.isFailure) {
            runCatching {
                java.io.File(filesDir, "titulky.log").appendText(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) +
                        " [CHYBÍ povolení „Zobrazovat přes jiné aplikace“ – titulky nejde ukázat]\n")
            }
        }
    }

    private fun startAsForeground() {
        val channelId = "titulky_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Titulky", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Titulky TV běží")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun novyText(barvaPisma: Int) = TextView(this).apply {
        text = ""
        // po spuštění nic neukazovat – box se objeví až s prvním přeloženým titulkem
        visibility = android.view.View.INVISIBLE
        textSize = VELIKOST_PISMA
        setTextColor(barvaPisma)
        if (barvaPisma == Color.WHITE) setShadowLayer(6f, 0f, 0f, Color.BLACK)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        // úplně černé pozadí (televize ho sama trochu zprůhlední – víc z aplikace nejde)
        setBackgroundColor(POZADI)
        setPadding(32, 12, 32, 12)
        gravity = Gravity.CENTER
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun addOverlay() {
        val tv = novyText(Color.WHITE)
        // podklad = stejný text, ale písmo v barvě pozadí → má přesně stejnou velikost jako titulek
        val pod = novyText(POZADI)
        val lp = WindowManager.LayoutParams(
            0,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        lp.alpha = 1f
        layout = lp
        textView = tv
        podklad = pod
        nastavVychoziPolohu()
        runCatching { wm.addView(pod, WindowManager.LayoutParams().apply { copyFrom(lp) }) }
        wm.addView(tv, lp)

        // druhé okno: spodní nápověda / úkol mise – žluté písmo
        val nap = novyText(Color.WHITE).apply {
            setTextColor(ZLUTA)
            text = ""
            visibility = android.view.View.INVISIBLE
            maxLines = 2
        }
        val lpN = WindowManager.LayoutParams().apply { copyFrom(lp) }
        napoveda = nap
        layoutNapovedy = lpN
        runCatching { wm.addView(nap, lpN) }
    }

    private var napoveda: TextView? = null
    private var layoutNapovedy: WindowManager.LayoutParams? = null
    private val schovejNapovedu = Runnable { napoveda?.apply { text = ""; visibility = android.view.View.INVISIBLE } }

    private fun ukazNapovedu(text: String, box: FloatArray?) {
        val tv = napoveda ?: return
        val lp = layoutNapovedy ?: return
        ui.removeCallbacks(schovejNapovedu)
        if (text.isBlank() || box == null || box.size != 4) { schovejNapovedu.run(); return }
        umisti(tv, lp, box, 1, sirsi = 1.08f, minSirka = 0.12f, maxSirka = 0.6f)
        tv.maxLines = 2
        runCatching { wm.updateViewLayout(tv, lp) }
        tv.text = text
        tv.visibility = android.view.View.VISIBLE
        ui.postDelayed(schovejNapovedu, 60_000) // pojistka, kdyby zpráva o zmizení nepřišla
    }

    /** Stejné nastavení (velikost, poloha, text) i pro podkladovou vrstvu. */
    private fun srovnejPodklad() {
        val tv = textView ?: return
        val pod = podklad ?: return
        val lp = layout ?: return
        pod.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.textSize)
        pod.maxLines = tv.maxLines
        pod.minHeight = tv.minHeight
        pod.text = tv.text.toString()
        pod.visibility = tv.visibility
        runCatching { wm.updateViewLayout(pod, WindowManager.LayoutParams().apply { copyFrom(lp) }) }
    }

    private var layout: WindowManager.LayoutParams? = null
    /** Konec úprav polohy → zase obyčejné černé pozadí bez rámečku. */
    private val konecUpravy = Runnable { textView?.setBackgroundColor(POZADI) }

    /** Výchozí poloha: dole uprostřed (pro hlášky a když nevíme, kde titulek je). */
    private fun nastavVychoziPolohu() {
        val lp = layout ?: return
        val obrazovka = resources.displayMetrics
        lp.width = (obrazovka.widthPixels * SIRKA_BOXU).toInt()
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = (obrazovka.heightPixels * ODSAZENI_ZDOLA).toInt()
        textView?.apply {
            textSize = VELIKOST_PISMA
            maxLines = 3
            minHeight = 0
        }
    }

    /**
     * Poloha a velikost, kterou si uživatel nastavil v telefonu (režim "titulky z telefonu").
     * [r] = (střed X, spodní hrana Y, šířka – podíl obrazovky 0..1, velikost písma – 1 = normální).
     */
    private fun nastavVlastniPolohu(r: FloatArray) {
        val lp = layout ?: return
        // telefon právě upravuje polohu → zelený přerušovaný rámeček, ať je vidět, kam box sahá
        if (r.size >= 5 && r[4] == 1f) {
            val d = resources.displayMetrics.density
            textView?.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(POZADI)
                setStroke((3 * d).toInt(), ZELENA, 14 * d, 8 * d)
            }
            ui.removeCallbacks(konecUpravy)
            ui.postDelayed(konecUpravy, 15_000)
        } else konecUpravy.run()
        val obrazovka = resources.displayMetrics
        val w = obrazovka.widthPixels.toFloat()
        val h = obrazovka.heightPixels.toFloat()
        val sirka = (r[2].coerceIn(0.2f, 1f) * w)
        lp.width = sirka.toInt()
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = (r[0] * w - sirka / 2).coerceIn(0f, w - sirka).toInt()
        lp.y = ((1f - r[1].coerceIn(0.05f, 1f)) * h).toInt()
        textView?.apply {
            textSize = VELIKOST_PISMA * r[3].coerceIn(0.5f, 2.5f)
            maxLines = 3
            minHeight = 0
        }
    }

    /**
     * Umístí box přesně přes anglický titulek.
     * [box] = (vlevo, nahoře, vpravo, dole) jako podíl šířky/výšky obrazovky (0..1).
     */
    private fun nastavPolohuPodle(box: FloatArray, radku: Int) {
        val lp = layout ?: return
        val tv = textView ?: return
        umisti(tv, lp, box, radku)
        // nastavení "Překrývat původní titulky" vypnuto → box nad anglický titulek
        if (!Nastaveni(this).prekryvat) {
            val obrazovka = resources.displayMetrics
            tv.minHeight = 0
            lp.y = ((1 - box[1]) * obrazovka.heightPixels + 8 * obrazovka.density).toInt()
        }
    }

    private fun umisti(tv: TextView, lp: WindowManager.LayoutParams, box: FloatArray, radku: Int, sirsi: Float = 1.15f, minSirka: Float = 0.4f, maxSirka: Float = 0.98f) {
        val obrazovka = resources.displayMetrics
        val w = obrazovka.widthPixels.toFloat()
        val h = obrazovka.heightPixels.toFloat()
        // větší rezerva kolem anglického titulku (čtení z menšího obrazu není na pixel přesné)
        val okraj = 18 * obrazovka.density

        // písmo podle výšky jednoho řádku původního titulku
        val vyskaRadku = (box[3] - box[1]) * h / radku.coerceAtLeast(1)
        val minPx = 14 * obrazovka.scaledDensity
        val maxPx = 34 * obrazovka.scaledDensity
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (vyskaRadku * 0.72f).coerceIn(minPx, maxPx))
        tv.maxLines = (radku + 1).coerceAtLeast(3)
        // box musí být aspoň tak vysoký jako anglický titulek, aby z něj nic nevykukovalo
        tv.minHeight = ((box[3] - box[1]) * h + 2 * okraj).toInt()

        // šířka jako původní titulek (čeština bývá delší, tak trochu víc), ale v rozumných mezích
        val sirka = ((box[2] - box[0]) * w * sirsi + 2 * okraj).coerceIn(w * minSirka, w * maxSirka)
        val stredX = (box[0] + box[2]) / 2 * w
        lp.width = sirka.toInt()
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = (stredX - sirka / 2).coerceIn(0f, w - sirka).toInt()
        // spodní hrana boxu = spodní hrana anglického titulku (box roste nahoru, když je česky víc řádků)
        lp.y = ((1 - box[3]) * h - okraj).coerceAtLeast(0f).toInt()
    }

    // ---------------------------------------------------------------- volby odpovědí

    private var ramecekVoleb: SrafovanyRamecek? = null
    /** Modrý šrafovaný rámeček ruční oblasti titulků (jen při nastavování v telefonu). */
    private var ramecekTitulku: SrafovanyRamecek? = null
    private val schovejRamecekTitulku = Runnable { ramecekTitulku?.visibility = android.view.View.INVISIBLE }

    private fun ukazOblastTitulku(o: FloatArray?, ukazat: Boolean) {
        ui.removeCallbacks(schovejRamecekTitulku)
        if (!ukazat || o == null || o.size != 4) { schovejRamecekTitulku.run(); return }
        val obr = resources.displayMetrics
        val w = obr.widthPixels.toFloat(); val h = obr.heightPixels.toFloat()
        val r = ramecekTitulku ?: SrafovanyRamecek(this, "Titulky hry", OblastTitulku.MODRA).also { v ->
            ramecekTitulku = v
            runCatching { wm.addView(v, oknoVoleb(true)) }
        }
        val lp = (r.layoutParams as? WindowManager.LayoutParams) ?: oknoVoleb(true)
        lp.x = (o[0] * w).toInt(); lp.y = (o[1] * h).toInt()
        lp.width = ((o[2] - o[0]) * w).toInt().coerceAtLeast(20)
        lp.height = ((o[3] - o[1]) * h).toInt().coerceAtLeast(20)
        runCatching { wm.updateViewLayout(r, lp) }
        r.visibility = android.view.View.VISIBLE
        r.invalidate()
        // pojistka: zmizí sám i bez zprávy z telefonu
        ui.postDelayed(schovejRamecekTitulku, 8_000)
    }
    private var seznamVoleb: android.widget.LinearLayout? = null
    private val schovejRamecek = Runnable { ramecekVoleb?.visibility = android.view.View.INVISIBLE }

    private fun oknoVoleb(pruhledne: Boolean) = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        if (pruhledne) PixelFormat.TRANSLUCENT else PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * Volby odpovědí: český seznam VEDLE anglických voleb ve hře (nezakrývá je),
     * vybraná volba podbarvená žlutě. [ramecek] = ukázat i žlutou šrafovanou oblast čtení.
     */
    private fun ukazVolby(volby: Array<String>, vybrana: Int, oblast: FloatArray?, ramecek: Boolean, trvale: Boolean = false) {
        val obr = resources.displayMetrics
        val w = obr.widthPixels.toFloat(); val h = obr.heightPixels.toFloat()
        val d = obr.density
        val o = if (oblast != null && oblast.size == 4) oblast else floatArrayOf(0.55f, 0.45f, 0.95f, 0.8f)

        // 1) žlutý šrafovaný rámeček (jen při nastavování v telefonu)
        ui.removeCallbacks(schovejRamecek)
        if (ramecek) {
            val r = ramecekVoleb ?: SrafovanyRamecek(this, "Volby odpovědí").also { v ->
                ramecekVoleb = v
                runCatching { wm.addView(v, oknoVoleb(true)) }
            }
            val lp = (r.layoutParams as? WindowManager.LayoutParams) ?: oknoVoleb(true)
            lp.x = (o[0] * w).toInt(); lp.y = (o[1] * h).toInt()
            lp.width = ((o[2] - o[0]) * w).toInt().coerceAtLeast(20)
            lp.height = ((o[3] - o[1]) * h).toInt().coerceAtLeast(20)
            runCatching { wm.updateViewLayout(r, lp) }
            r.visibility = android.view.View.VISIBLE
            r.invalidate()
            // pojistka: když nemá svítit pořád, po 8 s zmizí i bez zprávy z telefonu
            if (!trvale) ui.postDelayed(schovejRamecek, 8_000)
        } else schovejRamecek.run()

        // 2) český seznam voleb
        val seznam = seznamVoleb ?: android.widget.LinearLayout(this).also { v ->
            v.orientation = android.widget.LinearLayout.VERTICAL
            v.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6000000.toInt()); cornerRadius = 10 * d
            }
            v.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            v.visibility = android.view.View.INVISIBLE
            seznamVoleb = v
            runCatching { wm.addView(v, oknoVoleb(true)) }
        }
        if (volby.isEmpty()) { seznam.visibility = android.view.View.INVISIBLE; return }
        seznam.removeAllViews()
        volby.forEachIndexed { i, text ->
            val vybrano = i == vybrana
            seznam.addView(TextView(this).apply {
                this.text = text
                textSize = VELIKOST_PISMA * 0.9f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                if (vybrano) {
                    setTextColor(Color.BLACK)
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(ZLUTA); cornerRadius = 6 * d }
                } else setTextColor(ZLUTA)
                maxWidth = (w * 0.42f).toInt()
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (i == 0) 0 else (4 * d).toInt() })
        }
        // vedle oblasti voleb – tam, kde je víc místa (vlevo / vpravo)
        val mezera = 16 * d
        val vlevoMisto = o[0] * w - mezera * 2
        val vpravoMisto = (1 - o[2]) * w - mezera * 2
        val lp = (seznam.layoutParams as? WindowManager.LayoutParams) ?: oknoVoleb(true)
        // seznam jen tak široký jako nejdelší volba (ne přes půl obrazovky)
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        if (vlevoMisto >= vpravoMisto) {
            // vlevo od voleb: měřit od pravého okraje obrazovky, ať pravá hrana sedí u rámečku
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = (w - (o[0] * w - mezera)).coerceAtLeast(0f).toInt()
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (o[2] * w + mezera).toInt()
        }
        lp.y = (o[1] * h).toInt()
        runCatching { wm.updateViewLayout(seznam, lp) }
        seznam.visibility = android.view.View.VISIBLE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // potvrdit "běžím v popředí" hned na začátku každého startu – na slabé televizi se jinak
        // může stát, že to systém nestihne zaznamenat a aplikaci shodí
        // (ForegroundServiceDidNotStartInTimeException)
        runCatching { startAsForeground() }
        if (intent?.action == ACTION_SET_OBLAST_TITULKU) {
            ukazOblastTitulku(intent.getFloatArrayExtra(EXTRA_OBLAST), intent.getBooleanExtra(EXTRA_RAMECEK, false))
        }
        if (intent?.action == ACTION_SET_VOLBY) {
            ukazVolby(
                intent.getStringArrayExtra(EXTRA_VOLBY) ?: emptyArray(),
                intent.getIntExtra(EXTRA_VYBRANA, -1),
                intent.getFloatArrayExtra(EXTRA_OBLAST),
                intent.getBooleanExtra(EXTRA_RAMECEK, false),
                intent.getBooleanExtra(EXTRA_RAMECEK_TRVALE, false))
        }
        if (intent?.action == ACTION_SET_NAPOVEDA) {
            ukazNapovedu(intent.getStringExtra(EXTRA_TEXT).orEmpty(), intent.getFloatArrayExtra(EXTRA_BOX))
        }
        if (intent?.action == ACTION_SET_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val box = intent.getFloatArrayExtra(EXTRA_BOX)
            val vlastni = intent.getFloatArrayExtra(EXTRA_VLASTNI)
            if (vlastni != null && vlastni.size >= 4) {
                nastavVlastniPolohu(vlastni)
            } else if (box != null && box.size == 4) {
                konecUpravy.run()
                nastavPolohuPodle(box, intent.getIntExtra(EXTRA_RADKU, 1))
            } else {
                konecUpravy.run()
                nastavVychoziPolohu()
            }
            textView?.let { tv -> layout?.let { lp -> runCatching { wm.updateViewLayout(tv, lp) } } }
            showText(obarvi(text, intent.getIntArrayExtra(EXTRA_BARVY_JMEN)), intent.getLongExtra(EXTRA_DOBA, 6000))
        }
        return START_STICKY
    }

    /** Jména postav na začátku řádků ("Lucia: …") obarví barvou ze hry. */
    private fun obarvi(text: String, barvy: IntArray?): CharSequence {
        if (barvy == null || barvy.none { it != 0 }) return text
        val sb = android.text.SpannableStringBuilder(text)
        var zacatek = 0
        text.split("\n").forEachIndexed { i, radek ->
            val barva = barvy.getOrNull(i) ?: 0
            val dvojtecka = radek.indexOf(':')
            if (barva != 0 && dvojtecka in 1..25) {
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(barva),
                    zacatek, zacatek + dvojtecka + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            zacatek += radek.length + 1
        }
        return sb
    }

    private fun showText(text: CharSequence, hideAfterMs: Long = 6000) {
        textView?.apply {
            this.text = text
            visibility = if (text.isBlank()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
        srovnejPodklad()
        ui.removeCallbacks(hideRunnable)
        if (text.isNotBlank()) ui.postDelayed(hideRunnable, hideAfterMs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ui.removeCallbacks(hideRunnable)
        ui.removeCallbacks(konecUpravy)
        ui.removeCallbacks(schovejRamecek)
        ramecekVoleb?.let { runCatching { wm.removeView(it) } }
        ui.removeCallbacks(schovejRamecekTitulku)
        ramecekTitulku?.let { runCatching { wm.removeView(it) } }
        ramecekTitulku = null
        seznamVoleb?.let { runCatching { wm.removeView(it) } }
        ramecekVoleb = null
        seznamVoleb = null
        textView?.let { runCatching { wm.removeView(it) } }
        napoveda?.let { runCatching { wm.removeView(it) } }
        ui.removeCallbacks(schovejNapovedu)
        podklad?.let { runCatching { wm.removeView(it) } }
        textView = null
        podklad = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_TEXT = "cz.titulky.tv.SET_TEXT"
        /** Spodní nápověda / úkol mise (žlutě, vlastní box). EXTRA_TEXT prázdný = schovat. */
        const val ACTION_SET_NAPOVEDA = "cz.titulky.tv.SET_NAPOVEDA"
        /** Volby odpovědí (česky vedle anglických) + volitelně žlutý rámeček oblasti čtení. */
        const val ACTION_SET_VOLBY = "cz.titulky.tv.SET_VOLBY"
        /** Modrý rámeček ruční oblasti titulků (EXTRA_OBLAST + EXTRA_RAMECEK = ukázat). */
        const val ACTION_SET_OBLAST_TITULKU = "cz.titulky.tv.SET_OBLAST_TITULKU"
        const val EXTRA_VOLBY = "volby"
        const val EXTRA_VYBRANA = "vybrana"
        const val EXTRA_OBLAST = "oblast"
        const val EXTRA_RAMECEK = "ramecek"
        const val EXTRA_RAMECEK_TRVALE = "ramecek_trvale"
        val ZLUTA = 0xFFFFD54A.toInt()
        /** Rámeček při úpravě polohy – stejná zelená jako v mini televizi v telefonu. */
        val ZELENA = 0xFF3CFF6E.toInt()
        const val EXTRA_TEXT = "text"
        const val EXTRA_DOBA = "doba"
        /** Volitelně: kde je anglický titulek – FloatArray(vlevo, nahoře, vpravo, dole) v rozsahu 0..1. */
        const val EXTRA_BOX = "box"
        /** Vlastní poloha a velikost z telefonu: (střed X, spodní Y, šířka, velikost písma). */
        const val EXTRA_VLASTNI = "vlastni"
        /** Volitelně: kolik řádků měl anglický titulek. */
        const val EXTRA_RADKU = "radku"
        /** Volitelně: barva jména postavy pro každý řádek (0 = bílá). */
        const val EXTRA_BARVY_JMEN = "barvy_jmen"

        /** Velikost písma českého titulku (sp). */
        const val VELIKOST_PISMA = 20f
        /** Šířka boxu jako podíl šířky obrazovky. */
        const val SIRKA_BOXU = 0.62f
        /** Jak vysoko nad spodním okrajem box je (podíl výšky obrazovky). */
        const val ODSAZENI_ZDOLA = 0.07f
        /** Barva pozadí titulku – úplně neprůhledná. */
        val POZADI = 0xFF000000.toInt()
    }
}
