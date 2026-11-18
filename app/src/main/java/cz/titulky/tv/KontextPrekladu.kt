package cz.titulky.tv

/** Historie stabilních zdrojů; aktuální replika ani její stará revize nejsou vlastním kontextem. */
internal class KontextPrekladu {
    private data class Zdroj(val id: Long, val text: String, val kdy: Long)
    private val zdroje = mutableListOf<Zdroj>()
    fun prijmi(id: Long, text: String, ted: Long): String {
        zdroje.removeAll { it.id == id || ted - it.kdy !in 0..20000 }
        val kontext = zdroje.joinToString("\n") { it.text }
        if (text.isNotBlank() && text.length <= 1600) zdroje += Zdroj(id, text, ted)
        while (zdroje.size > 3 || zdroje.sumOf { it.text.length } + (zdroje.size - 1).coerceAtLeast(0) > 1600) zdroje.removeAt(0)
        return kontext
    }
    fun reset() = zdroje.clear()
}
