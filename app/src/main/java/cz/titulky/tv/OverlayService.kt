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
        addOverlay()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // potvrdit "běžím v popředí" hned na začátku každého startu – na slabé televizi se jinak
        // může stát, že to systém nestihne zaznamenat a aplikaci shodí
        // (ForegroundServiceDidNotStartInTimeException)
        runCatching { startAsForeground() }
        if (intent?.action == ACTION_SET_NAPOVEDA) {
            ukazNapovedu(intent.getStringExtra(EXTRA_TEXT).orEmpty(), intent.getFloatArrayExtra(EXTRA_BOX))
        }
        if (intent?.action == ACTION_SET_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val box = intent.getFloatArrayExtra(EXTRA_BOX)
            if (box != null && box.size == 4) {
                nastavPolohuPodle(box, intent.getIntExtra(EXTRA_RADKU, 1))
            } else {
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
        val ZLUTA = 0xFFFFD54A.toInt()
        const val EXTRA_TEXT = "text"
        const val EXTRA_DOBA = "doba"
        /** Volitelně: kde je anglický titulek – FloatArray(vlevo, nahoře, vpravo, dole) v rozsahu 0..1. */
        const val EXTRA_BOX = "box"
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
