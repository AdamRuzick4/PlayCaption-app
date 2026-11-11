package cz.titulky.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Zjišťuje, co je na televizi zrovna v popředí, aby se titulky z PS5 neukazovaly
 * přes YouTube, Netflix nebo hlavní obrazovku.
 *
 * Potřebuje oprávnění "přístup k využití" – na TV se povolí jednou přes adb:
 *   adb shell appops set cz.titulky.tv GET_USAGE_STATS allow
 * Bez něj vrací null a titulky se ukazují vždy (jako dřív).
 */
class Popredi(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = context.packageManager
    private val domov: String? by lazy {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
    }

    private var posledni: String? = null
    private var kdyZjisteno = 0L

    /** Balíček aplikace v popředí (null = nevíme). Výsledek si drží 1,5 s. */
    fun balicek(): String? {
        val ted = System.currentTimeMillis()
        if (ted - kdyZjisteno < 1500) return posledni
        kdyZjisteno = ted
        runCatching {
            val udalosti = usm.queryEvents(ted - 60 * 60 * 1000L, ted)
            val e = UsageEvents.Event()
            var nalez: String? = posledni
            while (udalosti.hasNextEvent()) {
                udalosti.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) nalez = e.packageName
            }
            posledni = nalez
        }
        return posledni
    }

    /**
     * Je na televizi zobrazený vstup (HDMI / živé vysílání), tedy místo, kde může být PS5?
     * - hlavní obrazovka → ne
     * - běžná aplikace z Google Play (YouTube, Netflix…) → ne
     * - systémová aplikace televize (vstupy, Live TV) nebo naše aplikace → ano
     * - nevíme (chybí oprávnění) → ano
     */
    fun jeVstupTv(): Boolean {
        val p = balicek() ?: return true
        if (p == context.packageName) return true
        if (p == domov) return false
        if (p in ZNAME_VSTUPY) return true
        val info = runCatching { pm.getApplicationInfo(p, 0) }.getOrNull() ?: return true
        val systemova = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val aktualizovana = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val spustitelna = pm.getLeanbackLaunchIntentForPackage(p) != null ||
            pm.getLaunchIntentForPackage(p) != null
        // aplikace stažená nebo aktualizovaná z Google Play, kterou jde spustit z menu = YouTube, Netflix…
        if (spustitelna && (!systemova || aktualizovana)) return false
        return true
    }

    companion object {
        /** Aplikace, které ukazují HDMI/živé vysílání (doplníme podle titulky.log). */
        val ZNAME_VSTUPY = setOf(
            "com.android.tv",
            "com.google.android.tv",
            "com.tcl.tv",
            "com.tcl.tvinput",
            "com.tcl.inputsource"
        )

        fun maOpravneni(context: Context): Boolean = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val ted = System.currentTimeMillis()
            usm.queryEvents(ted - 60_000, ted).hasNextEvent() ||
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, ted - 86_400_000L, ted).isNotEmpty()
        }.getOrDefault(false)
    }
}
