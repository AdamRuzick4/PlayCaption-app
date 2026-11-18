package cz.titulky.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Diagnostika – záznam (pád, titulky.log, chiaki.log) přes celou obrazovku – stačí vyfotit a poslat. */
object Zaznam {
    internal fun lgPrijem(activity: Activity, zprava: String) {
        runCatching {
            val cas = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            File(activity.filesDir, "titulky.log").appendText("$cas [LG příjem] $zprava\n")
        }
    }

    /** Celý text záznamu (pád, stav, titulky.log, xbox.log, chiaki.log). */
    fun obsah(a: Activity): String {
        fun konec(jmeno: String, radku: Int): String = runCatching {
            val f = File(a.filesDir, jmeno)
            if (!f.exists()) "(soubor $jmeno neexistuje)"
            else f.readLines().takeLast(radku).joinToString("\n").ifBlank { "(prázdný)" }
        }.getOrDefault("(nejde přečíst $jmeno)")
        // chiaki.log: opakované "re-sending" řádky jen spočítat, ať je vidět i to ostatní
        fun chiaki(): String = runCatching {
            val f = File(a.filesDir, "chiaki.log")
            if (!f.exists()) return@runCatching "(soubor chiaki.log neexistuje)"
            if (f.length() == 0L) return@runCatching "(0 B; nativní zápis může být bufferovaný nebo přepsaný novou Session. Nula řádků nedokazuje absenci video paketů. Diagnostika dekódovaných snímků je v části PS5 / obraz; nativní logcat má tag Chiaki.)"
            val radky = f.readLines().takeLast(3000)
            val znovu = radky.count { it.contains("re-sending") }
            "(posledních ${radky.size} řádků, z toho opakované odeslání: $znovu×)\n" +
                radky.filter { !it.contains("re-sending") }.takeLast(40).joinToString("\n")
        }.getOrDefault("(nejde přečíst chiaki.log)")
        // připojení a odpojení zvlášť – v titulky.log by je titulky rychle odsunuly z dohledu
        fun spojeni(): String = runCatching {
            val f = File(a.filesDir, "titulky.log")
            if (!f.exists()) return@runCatching "(nic)"
            val klice = listOf("připojuji", "PŘIPOJENO", "UKONČENO", "žádný obraz", "převz", "odpoj", "Hotovo – hrát",
                "Xbox:", "staré spojení", "kodek", "obraz: ", "jiné zařízení", "ukončeno v aplikaci", "[LG příjem]")
            f.readLines().filter { r -> klice.any { it in r } }.takeLast(30).joinToString("\n").ifBlank { "(nic)" }
        }.getOrDefault("(nejde přečíst)")
        fun diagnostikaPs5(): String = runCatching {
            val f = File(a.filesDir, "titulky.log")
            if (!f.exists()) "(nic)" else f.readLines()
                .filter { "[PS5 " in it || "[obraz:" in it || "[TV příjem]" in it }
                .takeLast(100).joinToString("\n").ifBlank { "(nic)" }
        }.getOrDefault("(nejde přečíst)")
        val verze = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull()
        val jazyk = Nastaveni(a).jazykNeboCs
        val prijem = PrijemService.prehled()
        val stavy = "\nObraz: " + Texty.spojeni(jazyk, RemotePlayService.prehledSpojeni(), podrobne = true) +
            "\nVýstup: " + (if (Propojeni.Vysilac.cilIp == null) "toto zařízení" else
                Propojeni.Vysilac.cilJmeno + " · " + Texty.prenos(jazyk, Propojeni.Vysilac.stavPrenosu, podrobne = true)) +
            (if (PrijemService.bezi || prijem.uroven == UrovenSpojeni.CHYBA) "\nTV příjem: " + Texty.spojeni(jazyk, prijem, podrobne = true) else "") +
            OverlayService.chyba?.let { "\nPřekryv: " + Texty.spojeni(jazyk, it, podrobne = true) }.orEmpty()
        val obsah = "Verze: $verze   Stav: ${RemotePlayService.stav}" + stavy + "\n\n=== pád ===\n" + konec("pad.txt", 25) +
            "\n\n=== spojení (připojení / odpojení) ===\n" + spojeni() +
            "\n\n=== PS5 / obraz / příjem z telefonu ===\n" + diagnostikaPs5() +
            "\n\n=== titulky.log ===\n" + konec("titulky.log", 120) +
            "\n\n=== xbox.log ===\n" + konec("xbox.log", 15) +
            "\n\n=== chiaki.log ===\n" + chiaki()
        return obsah
    }

    /** Diagnostika přes celou obrazovku: šipky ▲▼ posouvají záznam, ZPĚT zavře. */
    fun ukaz(a: Activity, sVyvojem: Boolean = false) {
        val obsah = obsah(a)
        val jazyk = Nastaveni(a).jazykNeboCs
        fun t(k: String) = Texty.t(jazyk, k)
        val d = a.resources.displayMetrics.density
        fun px(x: Int) = (x * d).toInt()
        val telefon = Rezim.jeTelefon(a)
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val zluta = Color.parseColor("#FFC24B")
        val seda = Color.parseColor("#A8A8B3")
        fun kulate(barva: Int, ramecek: Int? = null) = GradientDrawable().apply {
            setColor(barva); cornerRadius = 16f * d
            if (ramecek != null) setStroke(px(3), ramecek)
        }
        fun vyber(barva: Int) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), kulate(barva, Color.WHITE))
            addState(intArrayOf(), kulate(barva))
        }

        val pocetRadku = obsah.count { it == '\n' } + 1
        val pozice = TextView(a).apply { textSize = 13f; setTextColor(seda) }
        val zaznamText = TextView(a).apply {
            this.text = obsah
            textSize = if (telefon) 11f else 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D8D8E0"))
            setLineSpacing(0f, 1.2f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        val posuv = ScrollView(a).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            background = vyber(Color.parseColor("#111117"))
            addView(zaznamText)
        }
        fun obnovPozici() {
            val vyska = zaznamText.lineHeight.coerceAtLeast(1)
            val prvni = posuv.scrollY / vyska + 1
            val posledni = ((posuv.scrollY + posuv.height) / vyska).coerceAtMost(pocetRadku)
            pozice.text = t("diagRadky").replace("{od}", prvni.toString()).replace("{do}", posledni.toString())
                .replace("{z}", pocetRadku.toString())
        }
        posuv.viewTreeObserver.addOnScrollChangedListener { obnovPozici() }
        posuv.setOnKeyListener { _, kod, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val krok = zaznamText.lineHeight * 3
            when (kod) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // na konci záznamu → šipka dolů přejde na tlačítka
                    if (!posuv.canScrollVertically(1)) false else { posuv.smoothScrollBy(0, krok); true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> { posuv.smoothScrollBy(0, -krok); true }
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { posuv.smoothScrollBy(0, posuv.height); true }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { posuv.smoothScrollBy(0, -posuv.height); true }
                else -> false
            }
        }

        fun tlacitko(s: String, akce: () -> Unit) = TextView(a).apply {
            this.text = s
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = vyber(Color.parseColor("#2A2B36"))
            setPadding(px(20), px(10), px(20), px(10))
            setOnClickListener { akce() }
        }

        val koren = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(px(if (telefon) 16 else 40), px(if (telefon) 24 else 28), px(if (telefon) 16 else 40), px(if (telefon) 16 else 24))
        }
        val hlava = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nadpisy = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        nadpisy.addView(TextView(a).apply {
            this.text = t("diagNadpis"); textSize = if (telefon) 22f else 26f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; setTextColor(Color.WHITE)
        })
        nadpisy.addView(TextView(a).apply { this.text = t("diagPopis"); textSize = 13f; setTextColor(seda) })
        hlava.addView(nadpisy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!telefon) hlava.addView(pozice)
        koren.addView(hlava)
        koren.addView(posuv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(14) })

        val pata = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pata.addView(TextView(a).apply {
            this.text = if (telefon) t("diagOvladaniTel") else t("diagOvladani")
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(zluta)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tlacitka = mutableListOf<View>()
        tlacitka += tlacitko(t("diagZacatek")) { posuv.smoothScrollTo(0, 0); posuv.requestFocus() }
        tlacitka += tlacitko(t("diagKonec")) { posuv.post { posuv.fullScroll(View.FOCUS_DOWN) } }
        if (sVyvojem) tlacitka += tlacitko("Vývoj") { dlg.dismiss(); a.startActivity(Intent(a, VyvojActivity::class.java)) }
        tlacitka += tlacitko(t("diagZavrit")) { dlg.dismiss() }
        tlacitka.forEach { b ->
            pata.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(10) })
        }
        if (telefon) {
            // telefon: nápověda nad tlačítky, ať se vejde
            pata.orientation = LinearLayout.VERTICAL
            pata.gravity = Gravity.END
        }
        koren.addView(pata, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })

        dlg.setContentView(koren)
        dlg.show()
        posuv.post { posuv.requestFocus(); obnovPozici() }
    }
}
