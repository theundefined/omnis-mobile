package com.theundefined.omnis.ui

private const val MAX_QUERY_LENGTH = 120

// Kandydat na ISBN w dowolnym tekście: cyfry z opcjonalnymi myślnikami/spacjami, 10-13 cyfr.
private val ISBN_CANDIDATE = Regex("""\d[\d\- ]{8,15}[\dXx]""")
private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

/**
 * Zapytanie wyszukiwania z tekstu przekazanego przez inną aplikację („Udostępnij" / „Szukaj w
 * Omnis" na zaznaczonym tekście). Tekst z zewnątrz jest niezaufany — wynik to wyłącznie zapytanie.
 *
 * ISBN w tekście wygrywa; inaczej pierwsza niepusta linia po usunięciu adresów URL, przycięta do
 * [MAX_QUERY_LENGTH] znaków. null = nic sensownego do wyszukania (wtedy tylko otwieramy
 * wyszukiwarkę).
 */
fun queryFromSharedText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    ISBN_CANDIDATE.findAll(text).forEach { match ->
        normalizeIsbn(match.value)?.let { return it }
    }
    val line =
        text
            .lineSequence()
            .map { URL.replace(it, " ") }
            .map { line -> line.map { if (it.isISOControl()) ' ' else it }.joinToString("") }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
    return line.take(MAX_QUERY_LENGTH).trim().ifEmpty { null }
}
