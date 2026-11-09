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
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

// Czysta logika strażnika terminów (bez Androida) — decyduje, o czym powiadomić i co przedłużyć.
// Worker (DueDateWorker) tylko pobiera dane i wysyła to, co stąd wyjdzie.

/** Ustawienia przypomnień. Domyślnie wyłączone — użytkownik włącza je sam w Ustawieniach. */
@Serializable
data class GuardSettings(
    val enabled: Boolean = false,
    val thresholdDays: Int = DEFAULT_THRESHOLD,
    /**
     * Powiadamiaj, gdy tytuł z listy „Do przeczytania" pojawi się na półce (działa przy włączonym
     * strażniku).
     */
    val watchlistNotify: Boolean = true,
    /**
     * Godzina (pełna) codziennego przebiegu. Pola dopisane później mają domyślne wartości =
     * dotychczasowe zachowanie, więc stary zapis wczytuje się bez zmian (9:00, 2 dni).
     */
    val runHour: Int = DEFAULT_RUN_HOUR,
    /** Auto-przedłużanie na tyle dni przed terminem (dla kont z włączonym auto-przedłużaniem). */
    val autoRenewDays: Int = DEFAULT_AUTO_RENEW_DAYS
)

/**
 * Ustawienia z zapisu mogą mieć wartości spoza wyborów z Ustawień (uszkodzony/ręcznie zmieniony
 * zapis) — wracają wtedy do domyślnych.
 */
fun GuardSettings.validated(): GuardSettings =
    copy(
        runHour = if (runHour in RUN_HOUR_CHOICES) runHour else DEFAULT_RUN_HOUR,
        autoRenewDays =
            if (autoRenewDays in AUTO_RENEW_CHOICES) autoRenewDays else DEFAULT_AUTO_RENEW_DAYS
    )

const val DEFAULT_THRESHOLD = 3

/** Wybór progu w Ustawieniach (dni przed terminem). */
val THRESHOLD_CHOICES = listOf(1, 2, 3, 5, 7)

/**
 * Auto-przedłużanie dopiero na tyle dni przed terminem (domyślnie): część bibliotek liczy nowy
 * termin od dnia przedłużenia, więc za wczesne przedłużenie skraca wypożyczenie albo marnuje limit
 * prolongat. Użytkownik może zmienić w Ustawieniach ([AUTO_RENEW_CHOICES]).
 */
const val DEFAULT_AUTO_RENEW_DAYS = 2

/** Wybór „ile dni przed terminem przedłużać" w Ustawieniach. */
val AUTO_RENEW_CHOICES = listOf(1, 2, 3, 5)

/**
 * Domyślna godzina codziennego przebiegu (pierwszego; dalej co 24 h, z dokładnością WorkManagera).
 */
const val DEFAULT_RUN_HOUR = 9

/** Wybór godziny przebiegu w Ustawieniach — w ciągu dnia, żeby powiadomienie nie budziło. */
val RUN_HOUR_CHOICES = (6..21).toList()

/** Godzina z zapisu może być spoza zakresu (uszkodzony zapis) — nie wywalamy planowania. */
fun normalizedRunHour(hour: Int): Int = if (hour in 0..23) hour else DEFAULT_RUN_HOUR

fun delayUntilNextRun(now: LocalDateTime, runHour: Int = DEFAULT_RUN_HOUR): Duration {
    var next = now.toLocalDate().atTime(LocalTime.of(normalizedRunHour(runHour), 0))
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next)
}

/**
 * Moment (epoch ms) najbliższego przebiegu o [runHour] — z uwzględnieniem strefy i zmiany czasu.
 */
fun nextRunMillis(now: ZonedDateTime, runHour: Int = DEFAULT_RUN_HOUR): Long {
    val time = LocalTime.of(normalizedRunHour(runHour), 0)
    var next = now.toLocalDate().atTime(time).atZone(now.zone)
    if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    return next.toInstant().toEpochMilli()
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

    /** Ten sam klucz dla celu akcji „Przedłuż" (konto + pozycja + termin z powiadomienia). */
    fun renew(target: RenewTarget): String =
        "renew|${target.accountId}|${target.loanId}|${target.dueDate}"

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
fun renewCandidates(
    loans: List<Loan>,
    today: LocalDate,
    sent: Set<String>,
    autoRenewDays: Int = DEFAULT_AUTO_RENEW_DAYS
): List<Loan> =
    loans.filter { loan ->
        val due = parseFlexibleDate(loan.dueDate) ?: return@filter false
        loan.renewable &&
            ChronoUnit.DAYS.between(today, due) <= autoRenewDays &&
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

// --- Akcja „Przedłuż" w powiadomieniu o terminach ---

/**
 * Pozycja do przedłużenia z powiadomienia. Termin z chwili powiadomienia pozwala wykryć, że
 * użytkownik przedłużył ją w międzyczasie w aplikacji (termin się zmienił) — wtedy nie przedłużamy
 * drugi raz i nie zgłaszamy porażki.
 */
data class RenewTarget(val accountId: String, val loanId: String, val dueDate: String) {
    /** Jedna linia do extras/Data; separator spoza zbioru znaków identyfikatorów i dat. */
    fun encode(): String = listOf(accountId, loanId, dueDate).joinToString(TARGET_SEPARATOR)

    companion object {
        private const val TARGET_SEPARATOR = "\u001F"

        fun decode(text: String): RenewTarget? {
            val parts = text.split(TARGET_SEPARATOR)
            if (parts.size != 3 || parts.any { it.isEmpty() }) return null
            return RenewTarget(parts[0], parts[1], parts[2])
        }
    }
}

/** Górny limit pozycji w jednym zleceniu (powiadomienie pokazuje i tak kilka linii). */
const val MAX_RENEW_TARGETS = 20

/**
 * Pozycje, które przedłuży przycisk „Przedłuż": przedłużalne spośród linii faktycznie widocznych w
 * powiadomieniu (przed nimi idą linie wyniku auto-przedłużania — [leadingLines]; powiadomienie
 * ucina listę na [maxLines]). Bez pozycji, których przedłużenie już próbowaliśmy w tym terminie
 * ([attempted] to klucze z [GuardKeys.renew]) — ponawianie odmowy tylko obciąża serwer.
 */
fun renewActionTargets(
    notices: List<DueNotice>,
    leadingLines: Int,
    maxLines: Int,
    attempted: Set<String>
): List<RenewTarget> =
    notices
        .take((maxLines - leadingLines).coerceAtLeast(0))
        .map { it.loan }
        .filter { it.renewable && GuardKeys.renew(it) !in attempted }
        .mapNotNull { loan -> loan.accountId?.let { RenewTarget(it, loan.id, loan.dueDate) } }
        .distinct()
        .take(MAX_RENEW_TARGETS)

/** Co zrobić z celami akcji po świeżym pobraniu wypożyczeń konta (przed jakimkolwiek żądaniem). */
data class ActionPlan(
    /** Wciąż z tym samym terminem i przedłużalne — to jedyne, które wysyłamy do biblioteki. */
    val toRenew: List<Loan>,
    /**
     * Termin się zmienił (przedłużone w aplikacji lub przez auto-przedłużanie) — nic do zrobienia.
     */
    val alreadyDone: List<RenewTarget>,
    /** Zwrócone / zniknęły z listy — nic do zrobienia. */
    val gone: List<RenewTarget>,
    /** Wciąż z tym samym terminem, ale już nieprzedłużalne (np. limit) — zgłaszamy jako porażkę. */
    val blocked: List<Loan>
)

/** Cele, których próby przedłużenia (w tym terminie) jeszcze nie zapisano w [attempted]. */
fun notYetAttempted(targets: List<RenewTarget>, attempted: Set<String>): List<RenewTarget> =
    targets.filter { GuardKeys.renew(it) !in attempted }

fun planActionRenewals(targets: List<RenewTarget>, current: List<Loan>): ActionPlan {
    val toRenew = mutableListOf<Loan>()
    val alreadyDone = mutableListOf<RenewTarget>()
    val gone = mutableListOf<RenewTarget>()
    val blocked = mutableListOf<Loan>()
    targets.forEach { target ->
        val loan = current.firstOrNull { it.id == target.loanId }
        when {
            loan == null -> gone += target
            !sameDueDate(loan.dueDate, target.dueDate) -> alreadyDone += target
            !loan.renewable -> blocked += loan
            else -> toRenew += loan
        }
    }
    return ActionPlan(toRenew, alreadyDone, gone, blocked)
}

private fun sameDueDate(a: String, b: String): Boolean {
    val da = parseFlexibleDate(a)
    val db = parseFlexibleDate(b)
    return if (da != null && db != null) da == db else a == b
}

/** Wynik akcji do zgłoszenia użytkownikowi. */
data class ActionOutcome(
    val renewed: List<Pair<Loan, LocalDate>>,
    val failed: List<Loan>,
    /** Nie udało się ponownie pobrać listy — nie wiadomo, czy przedłużenie przeszło. */
    val unknown: List<Loan>
)

/**
 * Ocena po przedłużeniach: sukces tylko, gdy po ponownym pobraniu ([after], null = nie pobrano)
 * termin się przesunął ([renewalSucceeded]) — status HTTP renew_loans nic nie dowodzi.
 */
fun actionOutcome(plan: ActionPlan, after: List<Loan>?): ActionOutcome {
    if (after == null) return ActionOutcome(emptyList(), plan.blocked, plan.toRenew)
    val renewed = mutableListOf<Pair<Loan, LocalDate>>()
    val failed = plan.blocked.toMutableList()
    plan.toRenew.forEach { before ->
        val renewedLoan = renewalSucceeded(before, after)
        val newDue = renewedLoan?.let { parseFlexibleDate(it.dueDate) }
        if (renewedLoan != null && newDue != null) renewed += renewedLoan to newDue
        else failed += before
    }
    return ActionOutcome(renewed, failed, emptyList())
}
