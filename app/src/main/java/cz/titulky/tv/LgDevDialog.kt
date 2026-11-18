package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

internal fun lgDevPopis(activity: Activity, id: String): String {
    fun t(key: String) = Texty.t(activity, key)
    val value = LgDevUloziste(activity).cas(id)
    if (value == null) return t(if (LgDevMode.bezi(id)) "lgCasNacitam" else if (LgDevMode.chyba(id)) "lgCasChyba" else "lgCasNezjisteno")
    val now = System.currentTimeMillis()
    val aktualni = value.cerstvy(now) && !LgDevMode.chyba(id)
    val seconds = if (aktualni) value.zbyva(now) else value.sekundy
    val doba = t("lgCasDoba").replace("{d}", (seconds / 86400).toString())
        .replace("{h}", (seconds / 3600 % 24).toString()).replace("{m}", (seconds / 60 % 60).toString())
    val key = if (!aktualni) "lgCasPosledni" else if (seconds == 0L) "lgCasVyprsel" else if (value.brzy(now)) "lgCasBrzy" else "lgCasZbyva"
    return t(key).replace("{cas}", doba) + "\n" + t("lgCasOvereno").replace("{kdy}",
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.overeno))) +
        (if (LgDevMode.chyba(id)) "\n" + t("lgCasChyba") else "")
}

internal class LgDevDialog(private val activity: Activity, private val profil: TvProfil) {
    private val ui = Handler(Looper.getMainLooper())
    private fun t(key: String) = Texty.t(activity, key)
    fun ukaz() {
        val text = TextView(activity).apply { textSize = 18f; setPadding(30, 20, 30, 20) }
        val store = LgDevUloziste(activity)
        val auto = android.widget.CheckBox(activity).apply {
            this.text = t("lgCasAutoObnova")
            isChecked = store.automaticky(profil.id)
            setOnCheckedChangeListener { _, zapnuto ->
                if (store.automaticky(profil.id) == zapnuto) return@setOnCheckedChangeListener
                store.nastavAutomaticky(profil.id, zapnuto)
                if (zapnuto) LgDevMode.obnov(activity, profil.id, true)
            }
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(text); addView(auto) }
        val dialog = AlertDialog.Builder(activity).setTitle(profil.jmeno + " · Developer Mode")
            .setView(box).setPositiveButton(t("lgCasZkontrolovat"), null)
            .setNeutralButton(t("lgCasNacistTv"), null).setNegativeButton(t("lgZavrit"), null).create()
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || activity.isDestroyed) return
                if (dialog.window?.decorView?.hasWindowFocus() == true) LgDevMode.obnov(activity, profil.id)
                val prijimac = LgDevUloziste(activity).verzePrijimace(profil.id)
                text.text = lgDevPopis(activity, profil.id) + (if (prijimac != null) "\n" + t("lgVerzePosledni").replace("{verze}", prijimac.ifEmpty { t("lgChybiPrijimac") }) else "") + "\n\n" + t("lgCasRucniObnova")
                auto.isEnabled = store.maRelaci(profil.id) && !LgDevMode.bezi(profil.id)
                auto.isChecked = store.automaticky(profil.id)
                if (store.chybaProdlouzeni(profil.id)) text.append("\n" + t("lgCasObnovaChyba"))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = store.maRelaci(profil.id) && !LgDevMode.bezi(profil.id)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !LgDevMode.bezi(profil.id)
                ui.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener { ui.removeCallbacks(refresh) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { LgDevMode.obnov(activity, profil.id, true) }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { propoj() }
        ui.post(refresh)
    }

    private fun propoj() {
        val pass = EditText(activity).apply {
            hint = t("lgPassphrase"); contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30, 12, 30, 12)
            addView(TextView(activity).apply { text = profil.ip + "\n\n" + t("lgCasPropojeni"); textSize = 16f })
            addView(pass)
        }
        val otisky = activity.getSharedPreferences("lg_ssh_otisky", Activity.MODE_PRIVATE)
        val job = LgInstalator.vytvor(activity, profil.id)
        if (LgDevUloziste(activity).maParovani(profil.ip)) pass.hint = t("lgParovaniUlozene")
        val dialog = AlertDialog.Builder(activity).setTitle(t("lgCasNacistTv"))
            .setView(box).setPositiveButton(t("lgCasNacistTv"), null).setNegativeButton(t("zpet"), null).create()
        val zruseno = java.util.concurrent.atomic.AtomicBoolean(false)
        dialog.setOnDismissListener { zruseno.set(true); pass.text.clear(); job.zrus() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val secret = pass.text.toString().toCharArray()
            pass.text.clear(); pass.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Thread {
                try {
                    val token = job.nactiRelaci(profil.ip, secret)
                    if (zruseno.get() || activity.isDestroyed) return@Thread
                    LgDevUloziste(activity).token(profil.id, token)
                    LgDevMode.obnov(activity, profil.id, true)
                    ui.post { if (dialog.isShowing && !activity.isDestroyed) dialog.dismiss() }
                } catch (e: Exception) {
                    val key = if (e is LgInstalacniChyba) "lgChyba" + e.kod.name else "lgCasChyba"
                    ui.post { if (dialog.isShowing && !activity.isDestroyed) {
                        pass.error = t(key); pass.isEnabled = true
                        if (e is LgInstalacniChyba && e.kod == LgChyba.IDENTITA) {
                            AlertDialog.Builder(activity).setTitle(t("lgNoveParovani"))
                                .setMessage(t("lgNoveParovaniPopis").replace("{ip}", profil.ip))
                                .setPositiveButton(t("ano")) { _, _ ->
                                    otisky.edit().remove(lgSha256(profil.ip.toByteArray())).apply()
                                    LgDevUloziste(activity).zapomenParovani(profil.ip, profil.id)
                                }
                                .setNegativeButton(t("zpet"), null).show()
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    } }
                } finally { secret.fill('\u0000') }
            }.apply { isDaemon = true }.start()
        }
    }
}

/** Pouze viditelná karta, žádný nový proces ani úloha na pozadí. */
internal class LgDevModeView(private val activity: Activity, private val profil: () -> TvProfil?) : TextView(activity) {
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != View.VISIBLE) return
            val p = profil()?.takeIf { it.platforma == TvPlatforma.WEBOS }
            visibility = if (p == null) View.GONE else View.VISIBLE
            if (p != null) {
                if (isShown && hasWindowFocus()) LgDevMode.obnov(activity, p.id)
                text = lgDevPopis(activity, p.id) + "  ›"
                val cas = LgDevUloziste(activity).cas(p.id)
                setTextColor(Color.parseColor(if (cas?.brzy(System.currentTimeMillis()) == true && !LgDevMode.chyba(p.id)) "#FFC978" else "#B2B3BF"))
                setOnClickListener { LgDevDialog(activity, p).ukaz() }
            }
            postDelayed(this, 1000)
        }
    }
    init {
        val density = resources.displayMetrics.density
        textSize = 14f; setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        minimumHeight = (48 * density).toInt(); isFocusable = true; visibility = View.GONE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) { removeCallbacks(tick); if (visibility == View.VISIBLE) post(tick) }
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
