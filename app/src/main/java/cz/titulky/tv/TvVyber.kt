package cz.titulky.tv

import android.app.Activity

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
                    val p = televize.najdi(tv.id)?.platforma ?: TvPlatforma.NEZNAMA
                    platforma == TvPlatforma.NEZNAMA || p == platforma || p == TvPlatforma.NEZNAMA
                }
                val ulozene = televize.seznam().filter { profil ->
                    vhodne.none { it.id == profil.id } && (platforma == TvPlatforma.NEZNAMA || profil.platforma == platforma)
                }
                val polozky = vhodne.map { "📺  " + it.jmeno } + ulozene.map { it.jmeno + " · " + t("tvOffline") } + t("tvJenTelefon")
                android.app.AlertDialog.Builder(activity)
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
                            android.app.AlertDialog.Builder(activity).setTitle(profil.jmeno)
                                .setMessage(platformaText(profil.platforma) + "\n" + t("tvOffline") + "\n" + t("tvVerzeNeznama"))
                                .setPositiveButton("OK", null).show()
                        } else lokalne()
                    }.show()
            }
        }
    }
}
