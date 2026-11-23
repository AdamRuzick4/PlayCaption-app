package cz.titulky.tv

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VlastniKnihovnaTest {
    private val knihovna = VlastniKnihovna(RuntimeEnvironment.getApplication())

    @After fun zavri() = knihovna.close()

    @Test fun souborPredProfilemAPrekladacemAzNaVystupStory() {
        knihovna.nacti("""[{"original":"Hell yeah!","czech":"tak určitě!"},{"original":"Fine. Wait!","czech":"počkej…"}]""".reader())
        var pocetPozadavku = 0
        val relace = PribehovaRelace({ text, kontext, ok, _ ->
            pocetPozadavku++
            ok(GtaProfilPribehu.preloz(text.single(), null, kontext) ?: "běžný překlad")
        }, { fail(it) }, knihovna::najdi)
        fun zobraz(cislo: Long, text: String): String {
            relace.prijmiOcr(cislo, listOf(text), intArrayOf(0), false, cislo)
            val stranka = relace.krok(cislo, true, false)!!
            assertTrue(relace.potvrzeno(stranka.token))
            return stranka.text
        }
        assertEquals("tak určitě!", zobraz(1, "Hell yeah!"))
        assertEquals("Jason: tak určitě!", zobraz(2, "Jason: HELL YEAH."))
        assertEquals("počkej…", zobraz(3, "Fine. Wait!"))
        assertEquals(0, pocetPozadavku)
        assertEquals("Padáme!", zobraz(4, "Let's bounce!"))
        assertEquals("běžný překlad", zobraz(5, "Another sentence."))
        assertEquals(2, pocetPozadavku)
    }

    @Test fun chybejiciSlovaAKolizeNevyberouNespravnyPreklad() {
        knihovna.nacti("""[
            {"original":"I'll take it.","czech":"Beru to."},
            {"original":"Hold on!","czech":"Počkej!"},
            {"original":"Hold on?","czech":"Mám počkat?"}
        ]""".reader())
        assertEquals("Beru to.", knihovna.najdi("I’ll   take it"))
        assertNull(knihovna.najdi("I'll take"))
        assertNull(knihovna.najdi("I won't take it."))
        assertEquals("Počkej!", knihovna.najdi("Hold on!"))
        assertEquals("Mám počkat?", knihovna.najdi("Hold on?"))
        assertNull(knihovna.najdi("HOLD ON"))
    }

    @Test fun neplatnyImportPonechaMinulouKnihovnuIpoNovemOtevreni() {
        knihovna.nacti("""[{"original":"Hey.","czech":"Nazdar."}]""".reader())
        for (soubor in listOf("[]", """[{"original":"X","czech":"Y"},{"original":"Broken"}]""",
                """[{"original":"X","czech":"Y"}] zbytek""")) {
            assertTrue(runCatching { knihovna.nacti(soubor.reader()) }.isFailure)
        }
        val znovu = VlastniKnihovna(RuntimeEnvironment.getApplication())
        try {
            assertEquals(1, znovu.pocet())
            assertEquals("Nazdar.", znovu.najdi("Hey."))
            assertNull(znovu.najdi("X"))
            znovu.nacti("""[{"original":"New.","czech":"Nová."}]""".reader())
        } finally { znovu.close() }
        assertNull(knihovna.najdi("Hey."))
        assertEquals("Nová.", knihovna.najdi("New."))
        knihovna.smaz()
        assertEquals(0, knihovna.pocet())
        assertNull(knihovna.najdi("New."))
    }

    @Test fun delenouReplikuParujePodleZnacekVCetneZaverecnehoZvuku() {
        knihovna.nacti("""[{"original":"~sl:0~First.~sl:1~Second.~sl:2~~tone~ [laughs] ~tone~","czech":"~sl:0~První.~sl:1~Druhá.~sl:2~~tone~ [smích] ~tone~"}]""".reader())
        assertEquals("První. Druhá. [smích]", knihovna.najdi("First. Second. [laughs]"))
        assertEquals("Druhá.", knihovna.najdi("SECOND"))
        assertNull(knihovna.najdi("[laughs]"))
    }

    // Soukromý pilot se předává jen při lokální přejímce, nikdy není v testovacích assets.
    @Test fun lokalniPilot200ProjdeZImportuAzNaStrankuStory() {
        val cesta = System.getenv("PLAYCAPTION_PILOT_JSON")
        assumeNotNull(cesta)
        val soubor = File(cesta!!)
        assertEquals(200, soubor.bufferedReader().use { knihovna.nacti(it) })
        val pilot = JSONArray(soubor.readText())
        val cas = Regex("~sl:[0-9.:]+~")
        fun viditelne(text: String) = text.replace(cas, " ").replace("~tone~", "").trim().replace(Regex(" +"), " ")
        val relace = PribehovaRelace({ _, _, _, _ -> fail("Známá věta prošla do překladače") }, { fail(it) }, knihovna::najdi)
        var casti = 0
        for (i in 0 until pilot.length()) {
            val zaznam = pilot.getJSONObject(i)
            val en = zaznam.getString("original")
            val cs = zaznam.getString("czech")
            relace.prijmiOcr(i.toLong(), listOf(viditelne(en)), intArrayOf(0), false, i.toLong())
            val stranka = relace.krok(i.toLong(), true, false)!!
            assertEquals("Záznam $i", viditelne(cs), stranka.text)
            assertTrue(relace.potvrzeno(stranka.token))
            if (cas.containsMatchIn(en)) {
                val a = cas.split(en).filter { it.isNotBlank() && !it.contains("~tone~") }
                val b = cas.split(cs).filter { it.isNotBlank() && !it.contains("~tone~") }
                assertEquals(a.size, b.size)
                a.zip(b).forEach { (original, cesky) ->
                    assertEquals(cesky.trim(), knihovna.najdi(original.trim())); casti++
                }
            }
        }
        assertEquals(26, casti)
    }
}
