package cz.titulky.tv

/** LG potřebuje telefon jako zdroj OCR; převzetí spojení Android TV se nemění. */
internal object TelefonPozadi {
    fun pokracovatNaLg(aktivni: Boolean, idVybraneTv: String?, platforma: TvPlatforma?): Boolean =
        aktivni && !idVybraneTv.isNullOrBlank() && platforma == TvPlatforma.WEBOS
}
