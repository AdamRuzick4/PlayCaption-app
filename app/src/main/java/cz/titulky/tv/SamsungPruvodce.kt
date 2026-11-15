package cz.titulky.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Navigace je jen návod; úspěch potvrzuje až přijímač, který se sám ohlásí v síti. */
internal class SamsungPruvodceStav(ulozeny: Int) {
    var krok = ulozeny.coerceIn(0, POSLEDNI)
        private set
    fun dalsi(): Boolean {
        if (krok == POSLEDNI) return false
        krok++
        return true
    }
    fun zpet(): Boolean {
        if (krok == 0) return false
        krok--
        return true
    }
    fun prijimacMam() { krok = POSLEDNI }
    companion object { const val POSLEDNI = 3 }
}

/**
 * Samsung průvodce: Developer Mode, instalace a ověření přijímače.
 * Samostatný od LG (žádný Key Server, SSH ani webOS služby) i od původní Android TV.
 */
class SamsungPruvodce(private val activity: Activity, private val vyhledej: () -> Unit) {
    private val prefs = activity.getSharedPreferences("samsung_pruvodce", Activity.MODE_PRIVATE)
    private val stav = SamsungPruvodceStav(prefs.getInt("krok", 0))
    private var hledam = false
    private var nalezene: List<Propojeni.Televize>? = null
    private val dostupna = runCatching { SamsungBalicek.zAssets(activity).verze }.getOrNull()
    private val dialog = object : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (stav.zpet()) vykresli() else @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
    private fun t(klic: String) = Texty.t(activity, klic)
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun ukaz() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.setOwnerActivity(activity)
        vykresli()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun otevri(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(activity, t("lgBezProhlizece") + "\n" + url, Toast.LENGTH_LONG).show() }
    }

    private fun text(popis: String, velikost: Float, barva: Int = Color.WHITE) = TextView(activity).apply {
        text = popis
        textSize = velikost
        setTextColor(barva)
        setLineSpacing(dp(3).toFloat(), 1.1f)
    }

    private fun tlacitko(popis: String, hlavni: Boolean = false, akce: () -> Unit) = text(popis, 17f).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = dp(52)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (hlavni) "#E5091A" else "#33343E"))
            cornerRadius = dp(14).toFloat()
        }
        isFocusable = true
        setOnClickListener { akce() }
    }

    private fun vykresli() {
        prefs.edit().putInt("krok", stav.krok).apply()
        val koren = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            keepScreenOn = true
            setBackgroundColor(Color.parseColor("#0B0B0E"))
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        val hlavicka = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        hlavicka.addView(text("PlayCaption · Samsung", 22f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hlavicka.addView(tlacitko(t("lgZavrit")) { dialog.dismiss() })
        koren.addView(hlavicka)
        koren.addView(text(t("smKrok").replace("{n}", (stav.krok + 1).toString()), 14f, Color.parseColor("#B2B3BF")).apply {
            setPadding(0, dp(18), 0, dp(12))
        })
        val obsah = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(20)) }
        obsah.addView(text(t("smNadpis" + stav.krok), 27f).apply { typeface = Typeface.DEFAULT_BOLD })
        obsah.addView(text(t("smText" + stav.krok), 18f, Color.parseColor("#E2E2E8")).apply { setPadding(0, dp(18), 0, dp(14)) })
        fun radek(view: android.view.View) {
            obsah.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        fun akce(popis: String, hlavni: Boolean = false, proved: () -> Unit) = radek(tlacitko(popis, hlavni, proved))
        fun odkaz(popis: String, url: String) = akce(t(popis) + "  ↗") { otevri(url) }
        when (stav.krok) {
            0 -> akce(t("smUzMam")) { stav.prijimacMam(); vykresli() }
            1 -> {
                val ip = SamsungPrijimac.adresaTelefonu()
                radek(text(if (ip != null) t("smHostIp").replace("{ip}", ip) else t("smHostIpNeznama"), 20f, Color.parseColor("#FFC978")).apply { typeface = Typeface.DEFAULT_BOLD })
                odkaz("smOficialniNavod", "https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html")
                odkaz("smCelyNavod", NAVOD + "#2-zapni-developer-mode-na-televízii")
            }
            2 -> {
                akce(t("smUlozitWgt"), hlavni = true) { activity.startActivity(Intent(activity, SamsungExportActivity::class.java)) }
                odkaz("smInstalatorAndroid", "https://github.com/sunic007/tvinstaller")
                odkaz("smNavodPc", NAVOD + "#3b-inštalácia-z-počítača")
                odkaz("smCelyNavod", NAVOD)
            }
            3 -> {
                radek(text(vysledek(), 17f, Color.parseColor(when {
                    hledam || nalezene == null -> "#B2B3BF"
                    nalezene!!.isEmpty() -> "#FF9191"
                    else -> "#3CFF6E"
                })))
                if (hledam) radek(ProgressBar(activity))
                else akce(t("smOverit"), hlavni = nalezene.isNullOrEmpty()) { over() }
            }
        }
        koren.addView(ScrollView(activity).apply { isFillViewport = true; addView(obsah) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigace = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        navigace.addView(tlacitko(t("zpet")) { if (stav.zpet()) vykresli() else dialog.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        navigace.addView(tlacitko(t(if (stav.krok == SamsungPruvodceStav.POSLEDNI) "lgHledat" else "lgDalsi"), hlavni = stav.krok != SamsungPruvodceStav.POSLEDNI || !nalezene.isNullOrEmpty()) {
            if (stav.dalsi()) vykresli() else {
                dialog.dismiss()
                vyhledej()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        koren.addView(navigace)
        dialog.setContentView(koren)
    }

    private fun vysledek(): String {
        val seznam = nalezene
        return when {
            hledam -> t("smHledam")
            seznam == null -> t("smNeovereno")
            seznam.isEmpty() -> t("smNenalezen")
            else -> seznam.joinToString("\n\n") { tv ->
                val stavVerze = dostupna?.let { SamsungPrijimac.stav(tv.verze, it) } ?: SamsungStavVerze.NEHLASI
                "✔ " + tv.jmeno + " · " + tv.ip + "\n" + t("smVerze" + stavVerze.name)
                    .replace("{tv}", SamsungPrijimac.verze(tv.verze).orEmpty()).replace("{apk}", dostupna.orEmpty())
            }
        }
    }

    /** Přijímač potvrdí sám sebe ohlášením z TV; odeslaný soubor ani hotový návod důkazem nejsou. */
    private fun over() {
        if (hledam) return
        hledam = true
        vykresli()
        Propojeni.hledej(activity, 5000) { vse ->
            val samsung = vse.filter { SamsungPrijimac.jeSamsung(it.id) }
            val profily = TvProfily(activity)
            samsung.forEach { tv -> profily.zapamatuj(tv.id, profily.najdi(tv.id)?.jmeno ?: tv.jmeno, tv.ip, TvPlatforma.SAMSUNG) }
            activity.runOnUiThread {
                hledam = false
                nalezene = samsung
                if (dialog.isShowing && !activity.isDestroyed) vykresli()
            }
        }
    }

    private companion object {
        const val NAVOD = "https://github.com/AdamRuzick4/PlayCaption-app/blob/master/tizen/NAVOD-SAMSUNG.md"
    }
}
