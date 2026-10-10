package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.ui.components.parseFlexibleDate
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Wypożyczenia jednego miesiąca (1-12) rozbite na konta (accountId -> liczba). */
data class MonthBucket(val month: Int, val perAccount: Map<String, Int>) {
    val total: Int
        get() = perAccount.values.sum()
}

/** Wypożyczenia jednego roku rozbite na konta (accountId -> liczba). */
data class YearBucket(val year: Int, val perAccount: Map<String, Int>) {
    val total: Int
        get() = perAccount.values.sum()
}

/** Pozycja rankingu (autor, rodzaj materiału, filia). `label == null` = brak danych. */
data class CountEntry(val label: String?, val count: Int)

/** Czas od wypożyczenia do zwrotu — tylko dla zwróconych pozycji. */
data class LoanDurationStats(
    val sampleSize: Int,
    val averageDays: Double,
    val medianDays: Double,
    val longestDays: Long,
    val longestTitle: String
)

data class ReadingStats(
    val availableYears: List<Int>, // malejąco
    val scopeTotal: Int, // w wybranym roku albo łącznie, gdy rok == null
    val previousYearTotal: Int?, // tylko gdy wybrano rok i rok wcześniej już coś wypożyczano
    val allTimeTotal: Int,
    val thisYearTotal: Int,
    val averagePerMonth: Double,
    val bestMonth: Pair<YearMonth, Int>?,
    val months: List<MonthBucket>, // 12 pozycji dla wybranego roku, pusta gdy rok == null
    val years: List<YearBucket>, // rosnąco, wszystkie lata (niezależnie od wybranego roku)
    val topAuthors: List<CountEntry>,
    val categories: List<CountEntry>,
    val libraries: List<CountEntry>,
    val duration: LoanDurationStats?
)

private const val TOP_LIMIT = 10

/**
 * Historia (zwrócone) + bieżące wypożyczenia jednego konta, bez duplikatów. Wersja z historii
 * wygrywa, bo ma datę zwrotu.
 */
fun mergeStatsLoans(history: List<Loan>, active: List<Loan>): List<Loan> {
    val seen = mutableSetOf<Pair<String?, String>>()
    return (history + active).filter { seen.add(it.accountId to it.id) }
}

/**
 * Statystyki wypożyczeń (nie przeczytanych książek — tego API nie wie). Miesiąc/rok = data
 * wypożyczenia; pozycje z nieczytelną datą są pomijane. [accountIds] == null = wszystkie konta,
 * [year] == null = cały okres. [today] jako parametr, żeby dało się testować.
 */
fun computeReadingStats(
    loans: List<Loan>,
    accountIds: Set<String>?,
    year: Int?,
    today: LocalDate
): ReadingStats {
    val dated =
        loans
            .filter { accountIds == null || it.accountId in accountIds }
            .mapNotNull { loan -> parseFlexibleDate(loan.loanDate)?.let { loan to it } }
    val inScope = dated.filter { (_, date) -> year == null || date.year == year }
    val scopeLoans = inScope.map { it.first }

    val years =
        dated
            .groupBy { (_, date) -> date.year }
            .toSortedMap()
            .map { (y, entries) -> YearBucket(y, countByAccount(entries.map { it.first })) }
    val months =
        if (year == null) emptyList()
        else {
            val byMonth = inScope.groupBy { (_, date) -> date.monthValue }
            (1..12).map { m ->
                MonthBucket(m, countByAccount(byMonth[m].orEmpty().map { it.first }))
            }
        }

    val bestMonth =
        inScope
            .groupingBy { (_, date) -> YearMonth.from(date) }
            .eachCount()
            .entries
            .maxWithOrNull(compareBy<Map.Entry<YearMonth, Int>> { it.value }.thenBy { it.key })
            ?.toPair()

    return ReadingStats(
        availableYears = years.map { it.year }.sortedDescending(),
        scopeTotal = inScope.size,
        previousYearTotal =
            year
                ?.takeIf { y -> years.isNotEmpty() && y - 1 >= years.first().year }
                ?.let { y -> dated.count { it.second.year == y - 1 } },
        allTimeTotal = dated.size,
        thisYearTotal = dated.count { it.second.year == today.year },
        averagePerMonth = averagePerMonth(inScope.size, dated.map { it.second }, year, today),
        bestMonth = bestMonth,
        months = months,
        years = years,
        topAuthors = ranking(scopeLoans, TOP_LIMIT, ::authorKey) { authorLabel(it) },
        categories =
            ranking(scopeLoans, null, { it.itemCategoryName?.trim()?.lowercase() }) {
                it.itemCategoryName?.trim()
            },
        libraries = ranking(scopeLoans, TOP_LIMIT, ::libraryLabel, ::libraryLabel),
        duration = durationStats(scopeLoans)
    )
}

private fun countByAccount(loans: List<Loan>): Map<String, Int> =
    loans.groupingBy { it.accountId ?: "" }.eachCount()

/**
 * Średnia na miesiąc w okresie od miesiąca pierwszego wypożyczenia (albo początku wybranego roku,
 * jeśli później) do bieżącego miesiąca (albo końca wybranego roku, jeśli wcześniej).
 */
private fun averagePerMonth(
    count: Int,
    allDates: List<LocalDate>,
    year: Int?,
    today: LocalDate
): Double {
    val first = allDates.minOrNull()?.let { YearMonth.from(it) } ?: return 0.0
    var start = first
    var end = YearMonth.from(today)
    if (year != null) {
        start = maxOf(start, YearMonth.of(year, 1))
        end = minOf(end, YearMonth.of(year, 12))
    }
    val months = ChronoUnit.MONTHS.between(start, end) + 1
    return if (months <= 0) count.toDouble() else count.toDouble() / months
}

private fun <K : Any> ranking(
    loans: List<Loan>,
    limit: Int?,
    key: (Loan) -> K?,
    label: (Loan) -> String?
): List<CountEntry> {
    val groups = loans.groupBy(key)
    val entries =
        groups
            .map { (k, group) ->
                // Etykieta najczęstszego wariantu zapisu w grupie (np. różna interpunkcja).
                val name =
                    if (k == null) null
                    else group.groupingBy(label).eachCount().maxByOrNull { it.value }?.key
                CountEntry(name, group.size)
            }
            .sortedWith(compareByDescending<CountEntry> { it.count }.thenBy { it.label ?: "￿" })
    return if (limit != null) entries.filter { it.label != null }.take(limit) else entries
}

private val AUTHOR_LIFE_DATES = Regex("""[,\s]*\d{3,4}\s*-\s*(\d{3,4})?\s*$""")
private val AUTHOR_ROLE_SUFFIX =
    Regex(
        """[.,]\s*(autor|aut\.|author|tłum.*|ilustr.*|red\..*|oprac.*)\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Autor w czytelnej postaci: pierwszy z listy, bez dat życia ("(1962- )", ", 1962-"), roli
 * ("Autor") i końcowej interpunkcji. Katalog i API wypożyczeń zapisują tego samego autora różnie.
 */
fun normalizeAuthor(raw: String?): String? {
    var s = raw?.substringBefore(';')?.substringBefore('(') ?: return null
    s = s.replace(AUTHOR_ROLE_SUFFIX, "")
    s = s.replace(AUTHOR_LIFE_DATES, "")
    s = s.trim().trimEnd(',', '.', ';', ':', '/', ' ').trim()
    return s.takeIf { it.isNotEmpty() }
}

private fun authorLabel(loan: Loan): String? = normalizeAuthor(loan.catalogAuthor ?: loan.author)

private fun authorKey(loan: Loan): String? = authorLabel(loan)?.lowercase()

/**
 * Filia z nazwą biblioteki z KNOWN_TENANTS — nie `libraryName`, który dla wypożyczeń z sieci OMNIS
 * jest etykietą z UI Primo.
 */
private fun libraryLabel(loan: Loan): String? {
    val location = loan.locationName.trim().takeIf { it.isNotEmpty() }
    val tenant = loan.tenantName?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        tenant != null && location != null -> "$tenant · $location"
        else -> location ?: tenant
    }
}

private fun durationStats(loans: List<Loan>): LoanDurationStats? {
    val durations =
        loans.mapNotNull { loan ->
            val from = parseFlexibleDate(loan.loanDate) ?: return@mapNotNull null
            val to = loan.returnDate?.let { parseFlexibleDate(it) } ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(from, to)
            if (days < 0) null else loan to days
        }
    if (durations.isEmpty()) return null
    val sorted = durations.map { it.second }.sorted()
    val median =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    val longest = durations.maxBy { it.second }
    return LoanDurationStats(
        sampleSize = durations.size,
        averageDays = sorted.average(),
        medianDays = median,
        longestDays = longest.second,
        longestTitle = longest.first.title
    )
}
