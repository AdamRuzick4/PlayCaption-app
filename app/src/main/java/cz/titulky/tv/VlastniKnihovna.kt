package cz.titulky.tv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader

/** Privátní index v telefonu. Import čte po jedné replice a celou knihovnu vymění jedinou transakcí. */
internal class VlastniKnihovna(context: Context) : SQLiteOpenHelper(context.applicationContext, "vlastni-preklady.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE repliky (k TEXT NOT NULL, uroven INTEGER NOT NULL, cs TEXT, PRIMARY KEY(k, uroven))")
        db.execSQL("CREATE TABLE stav (pocet INTEGER NOT NULL)")
        db.execSQL("INSERT INTO stav VALUES (0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("Neznámá verze knihovny")

    fun pocet(): Int = readableDatabase.rawQuery("SELECT pocet FROM stav", null).use { it.moveToFirst(); it.getInt(0) }

    fun smaz() = nahrad { 0 }

    private fun nahrad(zapis: (SQLiteDatabase) -> Int): Int {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("repliky", null, null)
            val pocet = zapis(db)
            db.execSQL("UPDATE stav SET pocet = ?", arrayOf(pocet))
            db.setTransactionSuccessful()
            return pocet
        } finally { db.endTransaction() }
    }

    fun nacti(reader: Reader): Int = nahrad { db ->
        db.compileStatement("INSERT OR IGNORE INTO repliky VALUES (?,?,?)").use { vloz ->
            db.compileStatement("UPDATE repliky SET cs=NULL WHERE k=? AND uroven=? AND cs<>?").use { kolize ->
                ctiZaznamy(reader) { en, cs, cast ->
                    val original = zobrazeni(en)
                    val cesky = zobrazeni(cs)
                    require(original.isNotBlank() && cesky.isNotBlank())
                    for (klic in listOf("0" + original.trim(), "1" + normalizuj(original))) {
                        vloz.bindString(1, klic); vloz.bindLong(2, cast); vloz.bindString(3, cesky)
                        if (vloz.executeInsert() == -1L) {
                            kolize.bindString(1, klic); kolize.bindLong(2, cast); kolize.bindString(3, cesky)
                            kolize.executeUpdateDelete()
                        }
                    }
                }
            }
        }
    }

    private fun hledej(text: String): String? {
        val zdroj = zobrazeni(text)
        return readableDatabase.rawQuery("SELECT cs FROM repliky WHERE k IN (?, ?) ORDER BY k, uroven LIMIT 1",
            arrayOf("0" + zdroj.trim(), "1" + normalizuj(zdroj))).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun najdi(text: String): String? {
        hledej(text)?.let { return it }
        val jmeno = VyberTitulku.JMENO.find(text)?.value ?: return null
        return hledej(text.substring(jmeno.length))?.let { jmeno + it }
    }

    companion object {
        @Volatile private var instance: VlastniKnihovna? = null
        fun pro(context: Context): VlastniKnihovna = instance ?: synchronized(this) {
            instance ?: VlastniKnihovna(context).also { instance = it }
        }

        private val cas = Regex("~sl:[0-9.:]+~", RegexOption.IGNORE_CASE)
        private val ton = Regex("~tone~", RegexOption.IGNORE_CASE)
        private val pouzeZvuk = Regex("""(?:~tone~\s*)?\[[^\]]+\](?:\s*~tone~)?""", RegexOption.IGNORE_CASE)
        private val mezery = Regex("\\s+")
        private val interpunkce = Regex("[.,!?…:;\"“”()]+")
        private fun normalizuj(s: String) = s.lowercase().replace('’', '\'')
            .replace(interpunkce, " ").replace(mezery, " ").trim()

        private fun zobrazeni(s: String): String {
            if ('~' !in s) return s
            return cas.split(s).map { ton.replace(it, "").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun ctiZaznamy(reader: Reader, pridej: (String, String, Long) -> Unit): Int {
            var pocet = 0
            JsonReader(reader).use { json ->
                json.beginArray()
                while (json.hasNext()) {
                    var en: String? = null
                    var cs: String? = null
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "original" -> { require(json.peek() == JsonToken.STRING && en == null); en = json.nextString() }
                            "czech" -> { require(json.peek() == JsonToken.STRING && cs == null); cs = json.nextString() }
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    require(!en.isNullOrBlank() && !cs.isNullOrBlank())
                    pridej(en, cs, 0)
                    if (cas.containsMatchIn(en)) {
                        val anglicke = cas.split(en).filter { it.isNotBlank() && !pouzeZvuk.matches(it.trim()) }
                        val ceske = cas.split(cs).filter { it.isNotBlank() && !pouzeZvuk.matches(it.trim()) }
                        if (anglicke.size == ceske.size) anglicke.zip(ceske).forEach { (a, b) -> pridej(a.trim(), b.trim(), 1) }
                    }
                    pocet++
                }
                json.endArray()
                require(pocet > 0 && json.peek() == JsonToken.END_DOCUMENT)
            }
            return pocet
        }
    }
}
