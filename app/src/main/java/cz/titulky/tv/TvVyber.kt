package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import java.net.InetAddress
import java.net.PortUnreachableException

/** Stejný výběr přijímače v nastavení telefonu i během hraní. */
class TvVyber(
    private val activity: Activity,
    private val pripoj: (Propojeni.Televize, TvPlatforma?) -> Unit,
    private val lokalne: () -> Unit,
    private val platformaZmenena: () -> Unit = {}
) {
    private val televize = TvProfily(activity)
    private fun t(klic: String) = Texty.t(activity, klic)

    private fun platformaText(p: TvPlatforma) = when (p) {
        TvPlatforma.ANDROID_TV -> "Android TV / Google TV"
        TvPlatforma.WEBOS -> "LG webOS"
        TvPlatforma.SAMSUNG -> "Samsung (Tizen)"
        TvPlatforma.NEZNAMA -> t("tvPlatformaNeznama")
    }

    fun ukaz() {
        val lg = (if (Propojeni.Vysilac.cilIp != null) TvProfily.aktivniId else televize.posledniId)?.let { televize.najdi(it) }?.takeIf { it.platforma == TvPlatforma.WEBOS }
        android.app.AlertDialog.Builder(activity)
            .setTitle(t("tvNastaveni"))
            .setItems(arrayOf(t("tvPlatforma") + ": " + platformaText(televize.platforma), t("tvZmenit"), t("tvInformace"), t("tvJenTelefon")) + if (lg != null) arrayOf(t("lgCasMenu") + " · " + lg.jmeno) else emptyArray()) { _, i ->
                when (i) {
                    0 -> android.app.AlertDialog.Builder(activity).setTitle(t("tvPlatforma"))
                        .setSingleChoiceItems(TvPlatforma.values().map { platformaText(it) }.toTypedArray(), televize.platforma.ordinal) { dialog, n ->
                            televize.platforma = TvPlatforma.values()[n]
                            dialog.dismiss()
                            platformaZmenena()
                            when (televize.platforma) {
                                TvPlatforma.WEBOS -> lgPruvodce()
                                TvPlatforma.SAMSUNG -> samsungPruvodce()
                                else -> vyberTelevizi()
                            }
                        }.show()
                    1 -> vyberTelevizi()
                    2 -> if (televize.platforma == TvPlatforma.WEBOS) lgPruvodce() else if (televize.platforma == TvPlatforma.SAMSUNG) samsungPruvodce() else android.app.AlertDialog.Builder(activity).setTitle(platformaText(televize.platforma))
                        .setMessage(t(when (televize.platforma) {
                            TvPlatforma.ANDROID_TV -> "tvNavodAndroid"
                            else -> "tvNavodNeznamy"
                        }) + "\n\n" + t("tvVerzeNeznama"))
                        .setPositiveButton("OK", null).show()
                    4 -> lg?.let { LgDevDialog(activity, it).ukaz() }
                    else -> lokalne()
                }
            }.show()
    }

    private fun lgPruvodce() = LgPruvodce(activity) { vyberTelevizi() }.ukaz()
    private fun samsungPruvodce() = SamsungPruvodce(activity) { vyberTelevizi() }.ukaz()

    /** Najít televize s PlayCaption v síti a vybrat, kam posílat titulky. */
    private fun vyberTelevizi() {
        var zruseno = false
        val hledam = android.app.AlertDialog.Builder(activity)
            .setTitle(t("tvVyber"))
            .setMessage(t("tvHledamPrijimac"))
            .setOnCancelListener { zruseno = true }
            .show()
        Propojeni.hledej(activity) { seznam ->
            activity.runOnUiThread {
                if (zruseno || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                runCatching { hledam.dismiss() }
                val platforma = televize.platforma
                val vhodne = seznam.filter { tv ->
                    // Samsung přijímač se pozná podle vlastního ID; ostatní platformy se řídí profilem jako dosud.
                    if (SamsungPrijimac.jeSamsung(tv.id)) return@filter platforma == TvPlatforma.NEZNAMA || platforma == TvPlatforma.SAMSUNG
                    if (platforma == TvPlatforma.SAMSUNG) return@filter false
                    val p = televize.platformaPrijimace(tv.id)
                    platforma == TvPlatforma.NEZNAMA || p == platforma || p == TvPlatforma.NEZNAMA
                }
                val ulozene = televize.seznam().filter { profil ->
                    vhodne.none { it.id == profil.id } && (platforma == TvPlatforma.NEZNAMA || profil.platforma == platforma)
                }
                val polozky = vhodne.map { "📺  " + it.jmeno } + ulozene.map { it.jmeno + " · " + t("tvOffline") } + t("tvJenTelefon")
                val vyber = AlertDialog.Builder(activity)
                    .setTitle(if (vhodne.isEmpty()) t("tvNenalezenaPrijimac") else platformaText(platforma))
                    .setItems(polozky.toTypedArray()) { _, i ->
                        val tv = vhodne.getOrNull(i)
                        if (tv != null) {
                            fun pripoj() {
                                this@TvVyber.pripoj(tv, platforma.takeIf { it != TvPlatforma.NEZNAMA } ?: TvPlatforma.SAMSUNG.takeIf { SamsungPrijimac.jeSamsung(tv.id) })
                            }
                            if (platforma != TvPlatforma.NEZNAMA && platforma != TvPlatforma.SAMSUNG && televize.najdi(tv.id)?.platforma != platforma) {
                                android.app.AlertDialog.Builder(activity).setTitle(tv.jmeno)
                                    .setMessage(t("tvPotvrditPlatformu").replace("{platforma}", platformaText(platforma)))
                                    .setPositiveButton(t("ano")) { _, _ -> pripoj() }
                                    .setNegativeButton(t("zpet"), null).show()
                            } else pripoj()
                        } else if (i < vhodne.size + ulozene.size) {
                            val profil = ulozene[i - vhodne.size]
                            if (TvProfily.maIdLg(profil.id)) {
                                pripojitLgAdresou(profil)
                                return@setItems
                            }
                            android.app.AlertDialog.Builder(activity).setTitle(profil.jmeno)
                                .setMessage(platformaText(profil.platforma) + "\n" + t("tvOffline") + "\n" + t("tvVerzeNeznama"))
                                .setPositiveButton("OK", null).show()
                        } else lokalne()
                    }
                if (platforma == TvPlatforma.WEBOS || platforma == TvPlatforma.NEZNAMA)
                    vyber.setNeutralButton(t("lgPodleIp")) { _, _ -> pripojitLgAdresou(null) }
                vyber.show()
            }
        }
    }

    private fun pripojitLgAdresou(profil: TvProfil?) {
        val pole = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "192.168.1.20"
            setSingleLine()
            setText(profil?.ip.orEmpty())
        }
        val dialog = AlertDialog.Builder(activity).setTitle(profil?.jmeno ?: t("lgPodleIp"))
            .setMessage(t("lgPodleIpNavod")).setView(pole)
            .setPositiveButton(t("lgOveritPrijem"), null).setNegativeButton(t("zpet"), null).show()
        val overit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        overit.setOnClickListener {
            val ip = try { LgInstalacniProtokol.adresa(pole.text.toString()) }
                catch (_: LgInstalacniChyba) { pole.error = t("lgChybaADRESA"); return@setOnClickListener }
            pole.isEnabled = false
            overit.isEnabled = false
            dialog.setMessage(t("lgPrijemHledam"))
            Thread {
                val vysledek = runCatching { Propojeni.overLgNaAdrese(InetAddress.getByName(ip), profil?.id) }
                val chyba = when (vysledek.exceptionOrNull()) {
                    is PortUnreachableException -> "lgPodleIpPort"
                    is LgInstalacniChyba -> "lgPodleIpJina"
                    null -> if (vysledek.getOrNull() == null) "lgPodleIpTicho" else null
                    else -> "lgPodleIpSit"
                }
                Zaznam.lgPrijem(activity, "přímá kontrola $ip: " + (chyba?.let { t(it).replace("{ip}", ip) } ?: t("lgPrijemOdpoved")))
                activity.runOnUiThread {
                    if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val tv = vysledek.getOrNull()
                    if (chyba != null) {
                        dialog.setMessage(t(chyba).replace("{ip}", ip))
                        pole.isEnabled = true
                        overit.isEnabled = true
                    } else if (tv != null) {
                        dialog.dismiss()
                        AlertDialog.Builder(activity).setTitle(tv.jmeno).setMessage(t("lgPodleIpNalezena").replace("{ip}", ip))
                            .setPositiveButton(t("pripojit")) { _, _ -> pripoj(tv, TvPlatforma.WEBOS) }
                            .setNegativeButton(t("zpet"), null).show()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
