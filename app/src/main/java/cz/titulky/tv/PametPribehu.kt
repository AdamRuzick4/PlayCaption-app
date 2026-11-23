package cz.titulky.tv

internal enum class MotorPribehu(val id: String, val nazev: String) {
    AUTO("auto", "Automaticky"), DEEPL("deepl", "DeepL"), OFFLINE("offline", "Offline (ML Kit)");
    fun pouzitProDialog(story: Boolean, pozadovano: Boolean, rychle: Boolean) = !rychle && (story || (pozadovano && this != AUTO))
    fun zahrivatGoogle(pozadovano: Boolean) = !pozadovano || this == AUTO
    companion object { fun zId(id: String?) = values().firstOrNull { it.id == id } ?: AUTO }
}

internal class PametPribehu(private val limit: Int = 500) {
    var generace = 0L
        private set
    data class Klic(val motor: MotorPribehu, val jazyk: String, val text: String, val kontext: String)
    private val data = object : LinkedHashMap<Klic, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Klic, String>?) = size > limit
    }
    fun najdi(klic: Klic) = data[klic]
    fun uloz(klic: Klic, text: String, generace: Long = this.generace) {
        if (generace == this.generace && text.isNotBlank()) data[klic] = text
    }
    fun reset() { data.clear(); generace++ }
}
