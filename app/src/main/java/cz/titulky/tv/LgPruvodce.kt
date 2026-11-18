package cz.titulky.tv

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Navigace je pouze rozpracovaný návod, nikoliv potvrzení instalace nebo spojení. */
internal class LgPruvodceStav(ulozeny: Int) {
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
    companion object { const val POSLEDNI = 4 }
}

/** LG průvodce s místním instalátorem; běžný Android TV průvodce zůstává beze změny. */
class LgPruvodce(private val activity: Activity, private val vyhledej: () -> Unit) {
    private val prefs = activity.getSharedPreferences("lg_pruvodce", Activity.MODE_PRIVATE)
    private val stav = LgPruvodceStav(prefs.getInt("krok", 0))
    private val ui = Handler(Looper.getMainLooper())
    private val otisky = activity.getSharedPreferences("lg_ssh_otisky", Activity.MODE_PRIVATE)
    private var manualni = false
    private var pocitac = false
    private var bezi = false
    private var faze = LgFaze.BALICEK
    private var vysledek: LgInstalaceVysledek? = null
    private var prijimac: LgKontrola? = null
    private var overenaIp = ""
    private var chyba: LgChyba? = null
    private var detailChyby: String? = null
    private var instalator: LgInstalator? = null
    private var stavInstalace: TextView? = null
    private val overeniPrijmu = LgOvereniPrijimace()
    private var stavPrijmu: TextView? = null
    private var tlacitkoPrijmu: TextView? = null
    private var zadanaIp = TvProfily(activity).let { p -> p.posledniId?.let { p.najdi(it) } }
        ?.takeIf { it.platforma == TvPlatforma.WEBOS }?.ip.orEmpty()
    private val dialog = object : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (bezi) { instalator?.zrus(); return }
            overeniPrijmu.zrus()
            if (stav.zpet()) vykresli() else @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
    private fun t(klic: String) = Texty.t(activity, klic)
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun ukaz() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.setOwnerActivity(activity)
        dialog.setOnCancelListener { uloz() }
        dialog.setOnDismissListener { instalator?.zrus(); overeniPrijmu.zrus() }
        vykresli()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { instalator?.zrus() }
        })
    }

    private fun uloz() { prefs.edit().putInt("krok", stav.krok).apply() }

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
        uloz()
        stavPrijmu = null; tlacitkoPrijmu = null
        val koren = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            keepScreenOn = true
            setBackgroundColor(Color.parseColor("#0B0B0E"))
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        val hlavicka = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        hlavicka.addView(text("PlayCaption · LG webOS", 22f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hlavicka.addView(tlacitko(t("lgZavrit")) { dialog.dismiss() })
        koren.addView(hlavicka)
        koren.addView(text(t("lgKrok").replace("{n}", (stav.krok + 1).toString()), 14f, Color.parseColor("#B2B3BF")).apply {
            setPadding(0, dp(18), 0, dp(12))
        })
        val obsah = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(20)) }
        obsah.addView(text(t("lgNadpis" + stav.krok), 27f).apply { typeface = Typeface.DEFAULT_BOLD })
        obsah.addView(text(t(if (stav.krok == 3 && !manualni) "lgAutoText" else if (stav.krok == 3 && pocitac) "lgTextPc" else "lgText" + stav.krok), 18f, Color.parseColor("#E2E2E8")).apply { setPadding(0, dp(18), 0, dp(14)) })
        fun akce(popis: String, proved: () -> Unit) {
            obsah.addView(tlacitko(popis, akce = proved),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        fun odkaz(popis: String, url: String) = akce(t(popis) + "  ↗") { otevri(url) }
        if (stav.krok >= 2) {
            obsah.addView(text(t("lgPlatnostNadpis"), 18f, Color.parseColor("#FFC978")).apply { typeface = Typeface.DEFAULT_BOLD })
            obsah.addView(text(t("lgPlatnostText"), 16f, Color.parseColor("#E2E2E8")).apply { setPadding(0, dp(8), 0, dp(20)) })
        }
        when (stav.krok) {
            0 -> obsah.addView(tlacitko(t("lgUzMam")) { stav.prijimacMam(); vykresli() })
            1 -> odkaz("lgUcetOdkaz", "https://webostv.developer.lge.com/")
            2 -> odkaz("lgOficialniNavod", "https://webostv.developer.lge.com/develop/getting-started/developer-mode-app")
            3 -> if (!manualni) {
                instalacniPole(obsah)
            } else {
                akce(t("lgZpetAutomatika")) { manualni = false; pocitac = false; vykresli() }
                akce(t("lgUlozitIpk")) { activity.startActivity(Intent(activity, LgExportActivity::class.java)) }
                if (!pocitac) {
                    odkaz("lgAphanesAndroid", "https://github.com/symonxdd/aphanes/releases/tag/v1.0.2")
                    odkaz("lgAphanesNavod", "https://symonxdd.github.io/aphanes/")
                    akce(t("lgPouzitPc")) { pocitac = true; vykresli() }
                } else {
                    akce(t("lgPouzitAndroid")) { pocitac = false; vykresli() }
                    odkaz("lgAphanesPc", "https://github.com/symonxdd/aphanes/releases/tag/desktop-v1.0.0")
                    odkaz("lgSpravce", "https://github.com/webosbrew/dev-manager-desktop/releases/latest")
                    odkaz("lgBalicek", "https://github.com/AdamRuzick4/PlayCaption-app/releases")
                }
                odkaz("lgCelyNavod", "https://github.com/AdamRuzick4/PlayCaption-app/blob/master/webos/NAVOD-LG.md")
            }
            4 -> {
                cilKontroly()?.let { cil -> pridejKontroluPrijmu(obsah, cil) { overPrijimac(cil) } }
                    ?: obsah.addView(text(t("lgOvereniNejdriv"), 16f))
                odkaz("lgOficialniNavod", "https://webostv.developer.lge.com/develop/getting-started/developer-mode-app")
            }
        }
        koren.addView(ScrollView(activity).apply { isFillViewport = true; addView(obsah) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigace = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        navigace.addView(tlacitko(t("zpet")) {
            if (!bezi) { overeniPrijmu.zrus(); if (stav.zpet()) vykresli() else dialog.dismiss() }
        }.apply { isEnabled = !bezi; alpha = if (bezi) 0.4f else 1f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        navigace.addView(tlacitko(t(if (stav.krok == LgPruvodceStav.POSLEDNI) "lgHledat" else "lgDalsi"), hlavni = true) {
            overeniPrijmu.zrus()
            if (stav.dalsi()) vykresli() else {
                dialog.dismiss()
                vyhledej()
            }
        }.apply {
            isEnabled = !bezi && (stav.krok != 3 || manualni || vysledek != null)
            alpha = if (isEnabled) 1f else 0.4f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        koren.addView(navigace)
        dialog.setContentView(koren)
    }
    private fun instalacniPole(obsah: LinearLayout) {
        fun radek(view: View) {
            obsah.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        fun pole(popis: String, heslo: Boolean = false): EditText = EditText(activity).apply {
            hint = popis
            contentDescription = popis
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#B2B3BF"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(54)
            isSingleLine = true
            isEnabled = !bezi
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = InputType.TYPE_CLASS_TEXT or if (heslo) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        }
        val ip = pole(t("lgIp"))
        ip.setText(zadanaIp)
        val pass = pole(t("lgPassphrase"), true)
        radek(ip); radek(pass)
        if (LgDevUloziste(activity).maParovani(zadanaIp)) radek(text(t("lgParovaniUlozene"), 16f))
        stavInstalace = text(when {
            bezi -> t("lgFaze" + faze.name)
            chyba != null -> t("lgChyba" + chyba!!.name) +
                (detailChyby?.let { "\n\n" + t("lgChybaDetail") + it }
                    ?: if (chyba == LgChyba.INSTALACE) "\n\n" + t("lgChybaBezDetailu") else "")
            vysledek != null -> t(if (vysledek!!.zmeneno) "lgOvereno" else "lgBezPrepisu").replace("{verze}", vysledek!!.verze) + "\n" + t(if (vysledek!!.spusteno) "lgSpusteno" else "lgSpustRucne")
            prijimac != null -> t("lgVerze" + prijimac!!.stav.name).replace("{tv}", prijimac!!.verze.orEmpty()).replace("{apk}", prijimac!!.dostupna)
            else -> t("lgPripravenoInstalace")
        }, 17f, Color.parseColor(when { chyba != null -> "#FF9191"; vysledek != null -> "#3CFF6E"; else -> "#B2B3BF" })).also { it.setTextIsSelectable(true); radek(it) }
        cilKontroly()?.let { cil ->
            pridejKontroluPrijmu(obsah, cil) {
                if (ip.text.toString().trim() != cil.ip) {
                    Toast.makeText(activity, t("lgOvereniNejdriv"), Toast.LENGTH_LONG).show()
                } else overPrijimac(cil)
            }
        }
        if (bezi) {
            radek(ProgressBar(activity))
            radek(tlacitko(t("lgZrusitInstalaci")) { instalator?.zrus() })
        } else {
            fun heslo(): CharArray {
                zadanaIp = ip.text.toString().trim()
                val value = pass.text.toString().toCharArray()
                pass.text.clear()
                return value
            }
            radek(tlacitko(t("lgOveritTv"), hlavni = prijimac == null) { spustInstalaci(heslo(), true) })
            prijimac?.let { p ->
                radek(tlacitko(t(when (p.stav) {
                    LgStavVerze.CHYBI -> "lgInstalovatNove"
                    LgStavVerze.AKTUALIZACE -> "lgAktualizovat"
                    else -> "lgSpustitPrijimac"
                }), hlavni = true) {
                    val secret = heslo()
                    spustInstalaci(secret, zadanaIp != overenaIp)
                })
            }
            if (chyba == LgChyba.IDENTITA) radek(tlacitko(t("lgNoveParovani")) {
                AlertDialog.Builder(activity).setTitle(t("lgNoveParovani"))
                    .setMessage(t("lgNoveParovaniPopis").replace("{ip}", zadanaIp))
                    .setPositiveButton(t("ano")) { _, _ ->
                        otisky.edit().remove(lgSha256(zadanaIp.toByteArray())).apply()
                        val profiles = TvProfily(activity)
                        val id = prijimac?.id ?: profiles.posledniId?.let { profiles.najdi(it) }?.takeIf { it.platforma == TvPlatforma.WEBOS && it.ip == zadanaIp }?.id
                        LgDevUloziste(activity).zapomenParovani(zadanaIp, id)
                        prijimac = null
                        chyba = null; vykresli()
                    }.setNegativeButton(t("zpet"), null).show()
            })
            radek(tlacitko(t("lgRucniZaloha")) { manualni = true; pocitac = false; vykresli() })
            radek(tlacitko(t("lgUzMam")) { stav.prijimacMam(); vykresli() })
        }
    }

    private fun spustInstalaci(heslo: CharArray, jenKontrola: Boolean) {
        if (bezi) { heslo.fill('\u0000'); return }
        overeniPrijmu.zrus()
        bezi = true; chyba = null; detailChyby = null; vysledek = null; faze = LgFaze.BALICEK
        val ipOperace = zadanaIp
        val profiles = TvProfily(activity)
        val expected = prijimac?.takeIf { overenaIp == ipOperace }?.id
            ?: profiles.posledniId?.let { profiles.najdi(it) }?.takeIf { it.platforma == TvPlatforma.WEBOS && it.ip == ipOperace }?.id
        val job = LgInstalator.vytvor(activity, expected)
        instalator = job
        vykresli()
        Thread {
            try {
                val prubeh: (LgFaze) -> Unit = { f ->
                    ui.post { if (dialog.isShowing && !activity.isDestroyed && instalator === job) {
                        faze = f; stavInstalace?.text = t("lgFaze" + f.name)
                    } }
                }
                if (jenKontrola) {
                    val stav = job.zkontroluj(ipOperace, heslo, prubeh)
                    val profiles = TvProfily(activity)
                    val old = profiles.najdi(stav.id)
                    profiles.zapamatuj(stav.id, old?.jmeno ?: "LG PlayCaption · " + stav.id.removePrefix("lg-").uppercase(), ipOperace, TvPlatforma.WEBOS)
                    LgDevMode.obnov(activity, stav.id)
                    ui.post { if (dialog.isShowing && !activity.isDestroyed && instalator === job) {
                        bezi = false; prijimac = stav; overenaIp = ipOperace; vykresli()
                    } }
                } else {
                    val hotovo = job.nainstaluj(ipOperace, heslo, prubeh)
                    prijimac?.id?.let { LgDevMode.obnov(activity, it) }
                    ui.post { if (dialog.isShowing && !activity.isDestroyed && instalator === job) {
                        bezi = false; vysledek = hotovo; vykresli()
                    } }
                }
            } catch (e: Exception) {
                val kod = (e as? LgInstalacniChyba)?.kod ?: LgChyba.OVERENI
                val detail = (e as? LgInstalacniChyba)?.detail
                ui.post { if (dialog.isShowing && !activity.isDestroyed && instalator === job) {
                    bezi = false; chyba = kod; detailChyby = detail; prijimac = null; vykresli()
                } }
            } finally { heslo.fill('\u0000') }
        }.apply { isDaemon = true }.start()
    }

    private fun cilKontroly(): Propojeni.Televize? {
        val profily = TvProfily(activity)
        return LgOvereniPrijimace.cil(prijimac, overenaIp, zadanaIp, profily.seznam(), profily.posledniId)
    }

    private fun pridejKontroluPrijmu(obsah: LinearLayout, cil: Propojeni.Televize, overit: () -> Unit) {
        obsah.addView(text(t("lgPrijemCil").replace("{tv}", cil.jmeno).replace("{ip}", cil.ip), 17f)
            .apply { setPadding(0, dp(18), 0, dp(8)) })
        stavPrijmu = text("", 16f).also { it.setTextIsSelectable(true); obsah.addView(it) }
        tlacitkoPrijmu = tlacitko(t("lgOveritPrijem"), akce = overit).also { obsah.addView(it) }
        obsah.addView(text(t("lgPrijemRozsah"), 16f).apply { setPadding(0, dp(8), 0, dp(12)) })
        obnovPrijem()
    }

    private fun obnovPrijem() {
        val vysledek = overeniPrijmu.vysledek
        stavPrijmu?.text = t(when {
            overeniPrijmu.bezi -> "lgPrijemHledam"
            vysledek == null -> "lgPrijemNeovereno"
            vysledek.nalezen -> "lgPrijemOdpoved"
            else -> "lgPrijemBezOdpovedi"
        }) + vysledek?.let {
            "\n" + t("lgPrijemCas").replace("{cas}", java.text.DateFormat.getTimeInstance().format(java.util.Date(it.overenoKdy)))
        }.orEmpty()
        stavPrijmu?.setTextColor(Color.parseColor(if (vysledek?.nalezen == true) "#3CFF6E" else "#FFC978"))
        tlacitkoPrijmu?.apply { isEnabled = !bezi && !overeniPrijmu.bezi; alpha = if (isEnabled) 1f else 0.4f }
    }

    private fun overPrijimac(cil: Propojeni.Televize) {
        if (bezi || overeniPrijmu.bezi) return
        val pokus = overeniPrijmu.zacni(cil)
        obnovPrijem()
        Propojeni.hledej(activity) { nalezene -> ui.post {
            if (dialog.isShowing && !activity.isDestroyed && overeniPrijmu.dokonci(pokus, nalezene, System.currentTimeMillis())) {
                obnovPrijem()
                Zaznam.lgPrijem(activity, "kontrola TV ${cil.ip}: ${if (overeniPrijmu.vysledek?.nalezen == true) "přijímač se ozval" else "bez odpovědi"}")
            }
        } }
    }

}
