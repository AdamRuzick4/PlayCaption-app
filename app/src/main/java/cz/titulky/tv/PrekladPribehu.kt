package cz.titulky.tv

/** Jeden stabilní výsledek, konečný rozpočet a offline záloha; všechny callbacky na stejném vlákně. */
internal class PrekladPribehu(
    private val online: (String, (String?) -> Unit) -> Boolean,
    private val offline: (String, (String) -> Unit, () -> Unit) -> Unit,
    private val odloz: (Long, () -> Unit) -> Unit,
    private val onlinePovoleno: Boolean = true
) {
    fun preloz(text: String, hotovo: (String) -> Unit, chyba: () -> Unit) {
        var hotov = false; var onlineHotov = false; var offlineHotov = false; var offlineSpusten = false
        fun konec(t: String?) {
            if (hotov) return
            hotov = true
            if (t.isNullOrBlank()) chyba() else hotovo(t)
        }
        fun zaloha() {
            if (hotov || offlineSpusten) return
            offlineSpusten = true
            offline(text, { t ->
                offlineHotov = true
                if (t.isNotBlank()) konec(t) else if (onlineHotov) konec(null)
            }, { offlineHotov = true; if (onlineHotov) konec(null) })
        }
        odloz(8000) { konec(null) }
        val spusten = onlinePovoleno && online(text) { t ->
            onlineHotov = true
            if (!t.isNullOrBlank()) konec(t)
            else { zaloha(); if (offlineHotov) konec(null) }
        }
        if (!spusten) { onlineHotov = true; zaloha() }
        else odloz(2500) { zaloha() }
    }
}
