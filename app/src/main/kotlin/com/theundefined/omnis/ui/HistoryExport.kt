package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.parseFlexibleDate

private const val BOM = "\uFEFF"

/** Nagłówki kolumn CSV w kolejności z [historyToCsv] — lokalizowane, więc z zewnątrz. */
data class HistoryCsvHeader(
    val title: String,
    val author: String,
    val loanDate: String,
    val dueDate: String,
    val returnDate: String,
    val library: String,
    val branch: String,
    val person: String,
    val barcode: String
) {
    fun columns() =
        listOf(title, author, loanDate, dueDate, returnDate, library, branch, person, barcode)
}

/**
 * Historia jako CSV zgodny z RFC 4180 (separator przecinek, końce linii CRLF, pole z przecinkiem,
 * cudzysłowem lub nową linią w cudzysłowie, `"` podwojone), z BOM na początku — bez niego Excel
 * czyta UTF-8 jako stronę kodową i psuje polskie znaki. Daty w ISO (yyyy-MM-dd); nieczytelnej daty
 * nie zgadujemy, zostaje surowa.
 */
fun historyToCsv(loans: List<Loan>, header: HistoryCsvHeader): String {
    val sb = StringBuilder(BOM)
    fun row(fields: List<String>) {
        sb.append(fields.joinToString(",") { csvField(it) }).append("\r\n")
    }
    row(header.columns())
    loans.forEach { loan ->
        row(
            listOf(
                loan.title,
                loan.author.orEmpty(),
                isoDate(loan.loanDate),
                isoDate(loan.dueDate),
                loan.returnDate?.let(::isoDate).orEmpty(),
                // Jak w tytule ekranu: nazwa z KNOWN_TENANTS; ilsinstitutionname bywa etykietą UI.
                loan.tenantName ?: loan.libraryName,
                loan.locationName,
                loan.ownerName.orEmpty(),
                loan.barcode
            )
        )
    }
    return sb.toString()
}

/**
 * Pole CSV. Komórka zaczynająca się od `=`, `+`, `-`, `@`, tabulatora lub CR bywa w arkuszu
 * wykonana jak formuła (wstrzyknięcie przez tytuł/autora z katalogu) — dostaje przedrostek `'`.
 */
internal fun csvField(value: String): String {
    val safe = if (value.firstOrNull() in FORMULA_PREFIXES) "'$value" else value
    return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
        "\"" + safe.replace("\"", "\"\"") + "\""
    else safe
}

private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')

private fun isoDate(raw: String): String = parseFlexibleDate(raw)?.toString() ?: raw

/** Historia do eksportu: od najnowszego wypożyczenia, pozycje z nieczytelną datą na końcu. */
fun sortForExport(loans: List<Loan>): List<Loan> {
    val (dated, undated) =
        loans.map { it to parseFlexibleDate(it.loanDate) }.partition { it.second != null }
    return dated.sortedByDescending { it.second }.map { it.first } + undated.map { it.first }
}
