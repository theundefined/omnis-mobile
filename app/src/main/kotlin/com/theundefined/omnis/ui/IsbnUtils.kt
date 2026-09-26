package com.theundefined.omnis.ui

/**
 * Normalizuje zeskanowany (EAN-13) lub ręcznie wpisany kod do postaci ISBN nadającej się jako
 * zapytanie wyszukiwania. Primo samo dopasowuje ISBN-10 <-> ISBN-13 i ignoruje myślniki
 * (zweryfikowane na żywym katalogu, patrz docs/plans/isbn-scanner.md), więc nie konwertujemy między
 * formami — tylko odrzucamy kody, które na pewno nie są ISBN (EAN produktów, ISSN 977…, błędna suma
 * kontrolna), żeby nie odpalać wyszukiwania na śmieciowym zapytaniu.
 *
 * @return ISBN-13 lub ISBN-10 (bez separatorów, X wielką literą) albo null.
 */
fun normalizeIsbn(raw: String): String? {
    val cleaned = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
    return when {
        cleaned.length == 13 &&
            cleaned.all { it.isDigit() } &&
            (cleaned.startsWith("978") || cleaned.startsWith("979")) &&
            isValidEan13(cleaned) -> cleaned
        cleaned.length == 10 && isValidIsbn10(cleaned) -> cleaned
        else -> null
    }
}

private fun isValidEan13(code: String): Boolean {
    val sum = code.take(12).mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
    return (10 - sum % 10) % 10 == code[12] - '0'
}

private fun isValidIsbn10(code: String): Boolean {
    if (!code.take(9).all { it.isDigit() }) return false
    val last = code[9]
    val lastValue =
        when {
            last == 'X' -> 10
            last.isDigit() -> last - '0'
            else -> return false
        }
    val sum = code.take(9).mapIndexed { i, c -> (c - '0') * (10 - i) }.sum() + lastValue
    return sum % 11 == 0
}
