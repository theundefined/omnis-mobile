package com.theundefined.omnis.guard

import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.parseFlexibleDate
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

// Czysta logika strażnika terminów (bez Androida) — decyduje, o czym powiadomić i co przedłużyć.
// Worker (DueDateWorker) tylko pobiera dane i wysyła to, co stąd wyjdzie.

/** Ustawienia przypomnień. Domyślnie wyłączone — użytkownik włącza je sam w Ustawieniach. */
@Serializable
data class GuardSettings(val enabled: Boolean = false, val thresholdDays: Int = DEFAULT_THRESHOLD)

const val DEFAULT_THRESHOLD = 3

/** Wybór progu w Ustawieniach (dni przed terminem). */
val THRESHOLD_CHOICES = listOf(1, 2, 3, 5, 7)

/**
 * Auto-przedłużanie dopiero na tyle dni przed terminem: część bibliotek liczy nowy termin od dnia
 * przedłużenia, więc za wczesne przedłużenie skraca wypożyczenie albo marnuje limit prolongat.
 */
const val AUTO_RENEW_DAYS = 2

/** Godzina codziennego przebiegu (pierwszego; dalej co 24 h, z dokładnością WorkManagera). */
val GUARD_RUN_AT: LocalTime = LocalTime.of(9, 0)

fun delayUntilNextRun(now: LocalDateTime): Duration {
    var next = now.toLocalDate().atTime(GUARD_RUN_AT)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next)
}

/** Po tylu kolejnych odrzuceniach logowania (dzień po dniu) — powiadomienie i wstrzymanie konta. */
const val AUTH_FAILURE_LIMIT = 3

/**
 * Stan między przebiegami. Trzymany osobno od kont — worker nigdy nie zapisuje `Account`, żeby nie
 * nadpisać zmian zrobionych w tym czasie w UI (wyłączenie konta, auto-przedłużanie).
 */
@Serializable
data class GuardState(
    /** Klucze już wysłanych powiadomień (i prób przedłużenia) — patrz [GuardKeys]. */
    val sent: Set<String> = emptySet(),
    /** Ostatnio widziana kwota kar per konto; brak wpisu = pierwszy przebieg, bez powiadomienia. */
    val finesBaseline: Map<String, Double> = emptyMap(),
    /** Kolejne odrzucenia logowania per konto (błędy sieci nie liczą się w żadną stronę). */
    val authFailures: Map<String, Int> = emptyMap(),
    /**
     * Konta wstrzymane po [AUTH_FAILURE_LIMIT] odrzuceniach -> odcisk loginu+hasła z chwili
     * wstrzymania. Zmiana danych logowania (inny odcisk) wznawia sprawdzanie, a do tego czasu nie
     * próbujemy się logować, żeby biblioteka nie zablokowała konta.
     */
    val paused: Map<String, String> = emptyMap(),
    val lastRunAt: Long? = null
)

fun credentialsFingerprint(account: Account): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${account.username}\u0000${account.password}".toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }

fun isPaused(state: GuardState, account: Account): Boolean =
    state.paused[account.id] == credentialsFingerprint(account)

/** Konta sprawdzane w tle: włączone i nie demo (auto-przedłużanie zmieniałoby wspólne konto). */
fun guardedAccounts(accounts: List<Account>): List<Account> =
    accounts.filter { it.isEnabled && !it.isDemo }

enum class DueTier {
    SOON, // w ciągu progu
    LAST_DAY, // jutro albo dziś
    OVERDUE
}

fun dueTier(due: LocalDate, today: LocalDate, thresholdDays: Int): DueTier? {
    val days = ChronoUnit.DAYS.between(today, due)
    return when {
        days < 0 -> DueTier.OVERDUE
        days <= 1 -> DueTier.LAST_DAY
        days <= thresholdDays -> DueTier.SOON
        else -> null
    }
}

/**
 * Klucze deduplikacji: `rodzaj|konto|pozycja|...`. Zmiana terminu (przedłużenie) daje nowy klucz,
 * więc przypomnienie o nowym terminie przyjdzie znowu. Przeterminowane mają w kluczu dzisiejszą
 * datę — przypominamy codziennie, ale tylko raz dziennie (także przy ręcznym "Sprawdź teraz").
 */
object GuardKeys {
    fun due(loan: Loan, tier: DueTier, today: LocalDate): String =
        if (tier == DueTier.OVERDUE) "overdue|${loan.accountId}|${loan.id}|$today"
        else "due|${loan.accountId}|${loan.id}|${loan.dueDate}|$tier"

    /** Próba auto-przedłużenia — jedna na termin, żeby nie ponawiać odmowy codziennie. */
    fun renew(loan: Loan): String = "renew|${loan.accountId}|${loan.id}|${loan.dueDate}"

    /**
     * Gotowa rezerwacja; wraca, gdy termin odbioru robi się pilny (jak baner na ekranie głównym).
     */
    fun hold(hold: Hold, urgent: Boolean): String =
        "hold|${hold.accountId}|${hold.id}|${if (urgent) "urgent" else "ready"}"

    fun account(key: String): String? = key.split('|').getOrNull(1)

    fun item(key: String): String? = key.split('|').getOrNull(2)
}

data class DueNotice(val loan: Loan, val due: LocalDate, val tier: DueTier, val key: String)

/** Pozycje z terminem w progu, o których jeszcze nie powiadomiliśmy. */
fun newDueNotices(
    loans: List<Loan>,
    today: LocalDate,
    thresholdDays: Int,
    sent: Set<String>
): List<DueNotice> =
    loans
        .mapNotNull { loan ->
            // Nieparsowalna data: lepiej nie powiadamiać, niż powiadamiać na ślepo.
            val due = parseFlexibleDate(loan.dueDate) ?: return@mapNotNull null
            val tier = dueTier(due, today, thresholdDays) ?: return@mapNotNull null
            DueNotice(loan, due, tier, GuardKeys.due(loan, tier, today))
        }
        .filter { it.key !in sent }
        .sortedBy { it.due }

/** Przedłużalne pozycje konta z włączonym auto-przedłużaniem, których jeszcze nie próbowaliśmy. */
fun renewCandidates(loans: List<Loan>, today: LocalDate, sent: Set<String>): List<Loan> =
    loans.filter { loan ->
        val due = parseFlexibleDate(loan.dueDate) ?: return@filter false
        loan.renewable &&
            ChronoUnit.DAYS.between(today, due) <= AUTO_RENEW_DAYS &&
            GuardKeys.renew(loan) !in sent
    }

/** Przedłużenie się udało tylko, jeśli po ponownym pobraniu termin faktycznie się przesunął. */
fun renewalSucceeded(before: Loan, after: List<Loan>): Loan? {
    val old = parseFlexibleDate(before.dueDate) ?: return null
    val now = after.firstOrNull { it.id == before.id } ?: return null
    val new = parseFlexibleDate(now.dueDate) ?: return null
    return now.takeIf { new.isAfter(old) }
}

/** Kara wzrosła względem ostatnio widzianej kwoty (pierwszy przebieg tylko zapisuje stan). */
fun finesIncreased(baseline: Double?, current: Double): Boolean =
    baseline != null && current > baseline + 0.005

/**
 * Przycina zbiór kluczy do pozycji, które wciąż istnieją. Dla kont, których nie udało się sprawdzić
 * (`live` nie ma wpisu), klucze zostają bez zmian. Klucze przeterminowanych z wcześniejszych dni
 * odpadają zawsze.
 */
fun pruneSent(sent: Set<String>, live: Map<String, Set<String>>, today: LocalDate): Set<String> =
    sent
        .filter { key ->
            if (key.startsWith("overdue|") && !key.endsWith("|$today")) return@filter false
            val items = live[GuardKeys.account(key)] ?: return@filter true
            GuardKeys.item(key) in items
        }
        .toSet()
