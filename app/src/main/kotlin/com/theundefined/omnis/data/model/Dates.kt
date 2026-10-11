package com.theundefined.omnis.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Daty z API Primo przychodzą w różnych formatach (Raczyńskich: yyyyMMdd, omnis-py zakłada
 * dd/MM/yyyy) — zawsze parsować tą funkcją, nigdy nie porównywać surowych napisów. W warstwie
 * danych, bo używa jej też strażnik terminów działający w tle (bez UI).
 */
fun parseFlexibleDate(dateStr: String): LocalDate? {
    val formatters =
        listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
        )
    for (formatter in formatters) {
        try {
            return LocalDate.parse(dateStr, formatter)
        } catch (e: Exception) {
            continue
        }
    }
    return null
}
