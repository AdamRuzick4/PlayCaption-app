package cz.titulky.tv

import android.content.Context

/** Nastavení PlayCaption (jazyk, co překládat, jak zobrazovat). */
class Nastaveni(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gamelingo", Context.MODE_PRIVATE)

    /** Jazyk překladu i prostředí aplikace; null = ještě nevybráno (první spuštění). */
    var jazyk: String?
        get() = prefs.getString("jazyk", null)
        set(v) = prefs.edit().putString("jazyk", v).apply()

    val jazykNeboCs get() = jazyk ?: "cs"

    /** Box s překladem přes původní titulek (jinak nad ním). */
    var prekryvat: Boolean
        get() = prefs.getBoolean("prekryvat", true)
        set(v) = prefs.edit().putBoolean("prekryvat", v).apply()

    /** Překládat mluvené dialogy. */
    var dialogy: Boolean
        get() = prefs.getBoolean("dialogy", true)
        set(v) = prefs.edit().putBoolean("dialogy", v).apply()

    /** Překládat nápovědu k ovládání a úkoly (žlutě). */
    var napovedy: Boolean
        get() = prefs.getBoolean("napovedy", false)
        set(v) = prefs.edit().putBoolean("napovedy", v).apply()
}
