package com.theundefined.omnis.guard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.parseFlexibleDate
import com.theundefined.omnis.data.repository.LoginRejectedException
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.ui.holdDeadline
import com.theundefined.omnis.ui.isHoldDeadlineUrgent
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Codzienny przebieg strażnika terminów: dla każdego sprawdzanego konta jedna sesja
 * ([OmnisRepository.guardCheck]), zapis świeżych wypożyczeń do cache'u i zbiorcze powiadomienia.
 * Konta po kolei — bez równoległych logowań, jak przy ręcznym odświeżaniu.
 */
class DueDateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Przebieg okresowy i "Sprawdź teraz" mogą nałożyć się w jednym procesie — bez blokady oba
    // przeczytałyby ten sam stan i przedłużyły tę samą pozycję dwa razy (zużywając prolongatę).
    override suspend fun doWork(): Result = runLock.withLock { check() }

    private suspend fun check(): Result {
        val accountManager =
            try {
                AccountManager(applicationContext)
            } catch (e: Exception) {
                // Keystore bywa chwilowo niedostępny (np. tuż po starcie urządzenia).
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        val settings = accountManager.getGuardSettings()
        if (!settings.enabled) return Result.success()

        val repository = OmnisRepository(accountManager)
        val notifier = GuardNotifier(applicationContext).apply { ensureChannels() }
        val today = LocalDate.now()
        var state = accountManager.getGuardState()

        val accounts = guardedAccounts(accountManager.getAccounts())
        val showOwner = accounts.size > 1
        val sent = state.sent
        val newKeys = mutableSetOf<String>()
        val live = mutableMapOf<String, Set<String>>()
        val finesBaseline = state.finesBaseline.toMutableMap()
        val authFailures = state.authFailures.toMutableMap()
        val paused = state.paused.toMutableMap()

        val dueNotices = mutableListOf<DueNotice>()
        val renewed = mutableListOf<GuardNotifier.Renewed>()
        val renewFailed = mutableListOf<Loan>()
        val renewKeys = mutableSetOf<String>()
        val readyHolds = mutableListOf<Pair<Hold, String>>()
        val finesUp = mutableListOf<Pair<Account, Double>>()
        val newlyPaused = mutableListOf<Account>()
        var networkFailures = 0
        var checked = 0

        for (account in accounts) {
            if (isPaused(state, account)) continue
            checked++
            val result =
                repository.guardCheck(account) { loans ->
                    if (account.autoRenew) renewCandidates(loans, today, sent) else emptyList()
                }
            val fetch =
                result.getOrElse { error ->
                    if (error is LoginRejectedException) {
                        val count = (authFailures[account.id] ?: 0) + 1
                        authFailures[account.id] = count
                        if (count >= AUTH_FAILURE_LIMIT) {
                            paused[account.id] = credentialsFingerprint(account)
                            authFailures.remove(account.id)
                            newlyPaused += account
                        }
                    } else {
                        networkFailures++
                    }
                    null
                } ?: continue
            authFailures.remove(account.id)
            paused.remove(account.id)

            // Wynik auto-przedłużania: liczy się tylko przesunięty termin po ponownym pobraniu.
            // Bez ponownego pobrania nic nie wiemy — klucze prób nie zapisane, jutro spróbujemy
            // znowu (przedłużona pozycja wypadnie wtedy z kandydatów przez nowy termin).
            val judged = if (fetch.refetched) fetch.renewAttempted else emptyList()
            judged.forEach { before ->
                renewKeys += GuardKeys.renew(before)
                val after = renewalSucceeded(before, fetch.loans)
                val newDue = after?.let { parseFlexibleDate(it.dueDate) }
                if (after != null && newDue != null) {
                    renewed += GuardNotifier.Renewed(after, newDue)
                } else {
                    renewFailed += before
                }
            }

            val loans =
                repository.withCachedCatalogDetails(
                    fetch.loans,
                    accountManager.getCachedLoans(account.id)
                )
            accountManager.saveCachedLoans(account.id, loans)
            // Wszystkie bieżące (do treści) — powiadomienie zastępuje poprzednie, więc nie może
            // gubić pozycji zgłoszonych wcześniej; wysyłamy je tylko, gdy jest coś nowego.
            dueNotices += newDueNotices(loans, today, settings.thresholdDays, emptySet())

            fetch.holds?.let { holds ->
                holds
                    .filter { it.available }
                    .forEach { hold ->
                        val urgent =
                            holdDeadline(hold)?.let { isHoldDeadlineUrgent(it, today) } == true
                        val key = GuardKeys.hold(hold, urgent)
                        readyHolds += hold to key
                    }
            }
            // Pozycje, które wciąż istnieją (do przycięcia kluczy). Gdy rezerwacji nie udało się
            // pobrać, ich dotychczasowe klucze zostają.
            val holdIds =
                fetch.holds?.map { it.id }
                    ?: sent
                        .filter { it.startsWith("hold|${account.id}|") }
                        .mapNotNull { GuardKeys.item(it) }
            live[account.id] = loans.map { it.id }.toSet() + holdIds

            fetch.finesAmount?.let { amount ->
                if (finesIncreased(finesBaseline[account.id], amount)) finesUp += account to amount
                finesBaseline[account.id] = amount
            }
        }

        // Powiadomienia; klucze zapamiętujemy tylko dla tego, co faktycznie wysłano — po
        // ponownym włączeniu powiadomień w systemie nic nie przepadnie.
        val anyNewDue = dueNotices.any { it.key !in sent }
        if (
            (anyNewDue || renewed.isNotEmpty() || renewFailed.isNotEmpty()) &&
                notifier.notifyLoans(dueNotices, renewed, renewFailed, showOwner, today)
        ) {
            newKeys += dueNotices.map { it.key }
        }
        if (
            readyHolds.any { it.second !in sent } &&
                notifier.notifyHolds(readyHolds.map { it.first }, showOwner)
        ) {
            newKeys += readyHolds.map { it.second }
        }
        notifier.notifyFines(finesUp)
        notifier.notifyLoginProblems(newlyPaused)

        // Próby przedłużenia zapamiętujemy zawsze — nie ponawiamy odmowy codziennie.
        newKeys += renewKeys

        val accountIds = accounts.map { it.id }.toSet()
        state =
            GuardState(
                // Klucze usuniętych/wyłączonych kont odpadają (po ponownym włączeniu przypomnimy).
                sent =
                    pruneSent(sent + newKeys, live, today)
                        .filter { GuardKeys.account(it) in accountIds }
                        .toSet(),
                finesBaseline = finesBaseline.filterKeys { it in accountIds },
                authFailures = authFailures.filterKeys { it in accountIds },
                paused = paused.filterKeys { it in accountIds },
                lastRunAt = System.currentTimeMillis()
            )
        accountManager.saveGuardState(state)

        // Żadne konto nie odpowiedziało z powodu sieci/serwera — spróbuj później (backoff).
        if (checked > 0 && networkFailures == checked && runAttemptCount < MAX_ATTEMPTS) {
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private val runLock = Mutex()
    }
}
