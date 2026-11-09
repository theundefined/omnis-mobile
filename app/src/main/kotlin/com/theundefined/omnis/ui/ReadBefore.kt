package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.parseFlexibleDate
import java.text.Normalizer
import java.time.LocalDate

/**
 * Znaczek „już wypożyczone" przy wynikach wyszukiwania. Wyłącznie dane lokalne (cache wypożyczeń i
 * historii) — zero ruchu do serwera. Decyzje są tu, w czystych funkcjach, żeby dało się je pokryć
 * testami.
 */
data class ReadBeforeEntry(
    /** true = ta książka jest wypożyczona teraz (wygrywa z każdą historyczną). */
    val currentlyBorrowed: Boolean,
    /** Rok z `loanDate` ostatniego wypożyczenia; null, gdy daty nie da się odczytać. */
    val year: Int?,
    val ownerName: String?,
    internal val sortDate: LocalDate? = null
)

/** Indeks zbudowany raz z listy wypożyczeń; patrz [buildReadBeforeIndex] i [find]. */
class ReadBeforeIndex
internal constructor(
    private val byMmsid: Map<String, ReadBeforeEntry>,
    private val byTitleAuthor: Map<String, ReadBeforeEntry>
) {
    val isEmpty: Boolean
        get() = byMmsid.isEmpty() && byTitleAuthor.isEmpty()

    /**
     * Dopasowanie po `mmsid` któregokolwiek wydania wyniku; gdy brak — po znormalizowanym tytule i
     * autorze. Lepiej nie pokazać znaczka niż pokazać błędnie, więc sam tytuł nie wystarcza (chyba
     * że obie strony nie mają autora).
     */
    fun find(result: SearchResult): ReadBeforeEntry? {
        val byId = result.versions.mapNotNull { byMmsid[it.mmsid] }.reduceOrNull(::betterEntry)
        if (byId != null) return byId
        val candidates = buildList {
            titleAuthorKey(result.title, result.author)?.let { add(it) }
            result.versions.forEach { v -> titleAuthorKey(v.title, v.author)?.let { add(it) } }
        }
        return candidates.mapNotNull { byTitleAuthor[it] }.reduceOrNull(::betterEntry)
    }

    companion object {
        val EMPTY = ReadBeforeIndex(emptyMap(), emptyMap())
    }
}

/**
 * @param active bieżące wypożyczenia (cache) wszystkich kont aktywnego trybu
 * @param history zapisana historia (cache) tych samych kont
 */
fun buildReadBeforeIndex(active: List<Loan>, history: List<Loan>): ReadBeforeIndex {
    val byMmsid = HashMap<String, ReadBeforeEntry>()
    val byTitleAuthor = HashMap<String, ReadBeforeEntry>()

    fun add(loan: Loan, currentlyBorrowed: Boolean) {
        val date = parseFlexibleDate(loan.loanDate)
        val entry = ReadBeforeEntry(currentlyBorrowed, date?.year, loan.ownerName, date)
        if (loan.mmsid.isNotBlank()) byMmsid.merge(loan.mmsid, entry, ::betterEntry)
        // catalogAuthor (z rekordu katalogu) jest bliższy temu, co zwraca wyszukiwarka, niż
        // author z odpowiedzi wypożyczeń.
        val key = titleAuthorKey(loan.title, loan.catalogAuthor ?: loan.author)
        if (key != null) byTitleAuthor.merge(key, entry, ::betterEntry)
    }
    history.forEach { add(it, currentlyBorrowed = false) }
    active.forEach { add(it, currentlyBorrowed = true) }
    return ReadBeforeIndex(byMmsid, byTitleAuthor)
}

/** Bieżące wypożyczenie wygrywa z historią; w obrębie jednego rodzaju — nowsza data. */
private fun betterEntry(a: ReadBeforeEntry, b: ReadBeforeEntry): ReadBeforeEntry {
    if (a.currentlyBorrowed != b.currentlyBorrowed) return if (a.currentlyBorrowed) a else b
    val da = a.sortDate
    val db = b.sortDate
    return when {
        da == null -> if (db == null) a else b
        db == null -> a
        db.isAfter(da) -> b
        else -> a
    }
}

/**
 * Klucz „tytuł|autor" po normalizacji albo null, gdy tytuł po normalizacji jest pusty. Autor to
 * pierwszy z listy (rozdzielanej średnikiem), bez cyfr (daty życia w rekordach katalogu), a tokeny
 * są posortowane, żeby „Kowalski, Jan" i „Jan Kowalski" dawały to samo.
 */
internal fun titleAuthorKey(title: String?, author: String?): String? {
    // Część po " / " to w opisie bibliograficznym oznaczenie odpowiedzialności, nie tytuł.
    val titleKey = normalizeWords(title.orEmpty().substringBefore(" / ")).joinToString(" ")
    if (titleKey.isEmpty()) return null
    val authorTokens =
        normalizeWords(author.orEmpty().substringBefore(';'))
            .filter { token -> token.none { it.isDigit() } }
            .sorted()
    return "$titleKey|${authorTokens.joinToString(" ")}"
}

private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

/** Małe litery, bez diakrytyków (także ł/Ł), interpunkcja jako separator słów. */
private fun normalizeWords(text: String): List<String> {
    val folded =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).filter {
            Character.getType(it) != Character.NON_SPACING_MARK.toInt()
        }
    return folded.split(NON_WORD).filter { it.isNotEmpty() }
}
