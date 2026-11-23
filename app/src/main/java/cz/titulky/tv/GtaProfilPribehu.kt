package cz.titulky.tv

/** Lokální české ekvivalenty celých výrazů. null ponechá repliku zvolenému překladači. */
internal object GtaProfilPribehu {
    private val mezery = Regex("\\s+")
    private val smena = Regex("\\b(?:(?:my|your|our|the|night|day|work) shift|shift (?:starts|ends)|overtime|timesheet|clocked in)\\b", RegexOption.IGNORE_CASE)
    private val spech = Regex("\\b(?:countdown|time limit|seconds left|minutes left|running out of time)\\b", RegexOption.IGNORE_CASE)

    private val dluh = Regex("\\b(?:paid in full|still owe you|settle (?:my|our) debt)\\b", RegexOption.IGNORE_CASE)
    private val zdravi = Regex("\\b(?:anyone (?:hurt|injured)|are you (?:both |guys )?(?:hurt|injured))\\b", RegexOption.IGNORE_CASE)

    fun aktivni(pribeh: Boolean, zapnuto: Boolean, cil: String) = pribeh && zapnuto && cil == "cs"

    fun preloz(veta: String, mluvci: String?, kontext: String): String? {
        val text = veta.trim().lowercase().replace('’', '\'').replace(mezery, " ")
        val jadro = text.trimEnd('!').removeSuffix(".")
        val preklad = when (jadro) {
            "the po-po", "five-o" -> "Fízlové"
            "the po-po are here", "five-o is here" -> "Jsou tu fízlové"
            "he's packing heat", "she's packing heat" -> "Má u sebe bouchačku"
            "let's bounce" -> "Padáme"
            "hell yeah" -> "Kurva jo"
            "i got busted" -> "Sebrali mě"
            "i was there", "i'm ready" -> {
                val zena = when (mluvci?.lowercase()) {
                    "lucia", "lucia caminos" -> true
                    "jason", "jason duval" -> false
                    else -> return null
                }
                when (jadro) {
                    "i was there" -> if (zena) "Byla jsem tam" else "Byl jsem tam"
                    else -> if (zena) "Jsem připravená" else "Jsem připravený"
                }
            }
            "i'm on the clock", "we're on the clock" -> {
                val jednaOsoba = jadro == "i'm on the clock"
                podleKontextu(kontext, smena, if (jednaOsoba) "Mám směnu" else "Máme směnu",
                    spech, if (jednaOsoba) "Tlačí mě čas" else "Tlačí nás čas")
            }
            "we're good" -> podleKontextu(kontext, dluh, "Jsme vyrovnaní", zdravi, "Jsme v pořádku")
            else -> null
        } ?: return null
        return preklad + if (text.endsWith('!')) "!" else "."
    }

    // Novější vodítko přebije starší. Když jedna replika připouští oba významy, rozhodne překladač.
    private fun podleKontextu(kontext: String, prvni: Regex, prvniVyznam: String, druhy: Regex, druhyVyznam: String): String? {
        for (radek in kontext.lines().asReversed()) {
            val maPrvni = prvni.containsMatchIn(radek)
            val maDruhy = druhy.containsMatchIn(radek)
            if (maPrvni && maDruhy) return null
            if (maPrvni) return prvniVyznam
            if (maDruhy) return druhyVyznam
        }
        return null
    }
}
