package com.theundefined.omnis.guard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.theundefined.omnis.data.local.AccountManager
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.repository.LoginRejectedException
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.widget.updateLoansWidgets
import java.time.LocalDate
import kotlinx.coroutines.sync.withLock

/**
 * Przycisk „Przedłuż" z powiadomienia: przedłuża dokładnie te pozycje, które były w powiadomieniu
 * ([RenewTarget]), jedno logowanie na konto ([OmnisRepository.guardCheck] bez kar i rezerwacji).
 * Sukces ocenia wyłącznie przesunięty termin po ponownym pobraniu; pozycje przedłużone w
 * międzyczasie w aplikacji (inny termin) lub zwrócone są pomijane bez zgłaszania porażki.
 */
class RenewActionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // Ta sama blokada co dzienny przebieg — nie przedłużymy tej samej pozycji dwa razy naraz.
    override suspend fun doWork(): Result = DueDateWorker.runLock.withLock { run() }

    private suspend fun run(): Result {
        val targets =
            inputData.getStringArray(KEY_TARGETS).orEmpty().mapNotNull { RenewTarget.decode(it) }
        val notifier = GuardNotifier(applicationContext)
        if (targets.isEmpty()) {
            notifier.clearRenewPending()
            return Result.success()
        }
        val accountManager =
            try {
                AccountManager(applicationContext)
            } catch (e: Exception) {
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else {
                    notifier.clearRenewPending()
                    Result.failure()
                }
            }
        val repository = OmnisRepository(accountManager)
        notifier.ensureChannels()
        val today = LocalDate.now()
        val state = accountManager.getGuardState()
        val accounts = guardedAccounts(accountManager.getAccounts())
        val byId = accounts.associateBy { it.id }

        val renewed = mutableListOf<GuardNotifier.Renewed>()
        val failed = mutableListOf<Loan>()
        val unknown = mutableListOf<Loan>()
        val renewKeys = mutableSetOf<String>()
        var cacheWritten = false
        // Cele kont, do których nie udało się dotrzeć przez sieć (patrz niżej).
        val unreachable = mutableListOf<Pair<String, List<RenewTarget>>>()
        var checked = 0

        // Konta usunięte, wyłączone i demo pomijamy po cichu — nie ma czego przedłużać.
        for ((accountId, accountTargets) in targets.groupBy { it.accountId }) {
            val account = byId[accountId] ?: continue
            if (isPaused(state, account)) {
                failed += cachedLoans(accountManager, accountTargets)
                continue
            }
            checked++
            var plan: ActionPlan? = null
            val result =
                repository.guardCheck(account, withExtras = false) { loans ->
                    planActionRenewals(accountTargets, loans).also { plan = it }.toRenew
                }
            val fetch =
                result.getOrElse { error ->
                    if (error is LoginRejectedException) {
                        failed += cachedLoans(accountManager, accountTargets)
                    } else {
                        unreachable += accountId to accountTargets
                    }
                    null
                } ?: continue
            val accountPlan = plan ?: continue
            val outcome = actionOutcome(accountPlan, if (fetch.refetched) fetch.loans else null)
            // Jak w dziennym przebiegu: próbę zapamiętujemy tylko, gdy znamy wynik — wtedy
            // przebieg dzienny nie ponowi odmowy.
            if (fetch.refetched) renewKeys += accountPlan.toRenew.map { GuardKeys.renew(it) }
            renewed += outcome.renewed.map { (loan, due) -> GuardNotifier.Renewed(loan, due) }
            failed += outcome.failed
            unknown += outcome.unknown

            cacheWritten = true
            accountManager.saveCachedLoans(
                account.id,
                repository.withCachedCatalogDetails(
                    fetch.loans,
                    accountManager.getCachedLoans(account.id)
                )
            )
        }

        if (cacheWritten) updateLoansWidgets(applicationContext)

        // Nikt nie odpowiedział (sieć/serwer) — spróbuj później; plan i tak odfiltruje to, co w
        // międzyczasie się zmieniło. Po wyczerpaniu prób zgłaszamy porażkę.
        if (checked > 0 && unreachable.size == checked && runAttemptCount < MAX_ATTEMPTS) {
            return Result.retry()
        }
        unreachable.forEach { (_, list) -> failed += cachedLoans(accountManager, list) }

        if (renewKeys.isNotEmpty()) {
            val current = accountManager.getGuardState()
            accountManager.saveGuardState(current.copy(sent = current.sent + renewKeys))
        }
        notifier.clearRenewPending()
        notifier.notifyLoans(
            notices = emptyList(),
            renewed = renewed,
            renewFailed = failed,
            showOwner = accounts.size > 1,
            today = today,
            renewUnknown = unknown
        )
        return Result.success()
    }

    /**
     * Pozycje z cache'u do linii „Nie udało się przedłużyć" tam, gdzie nie mamy świeżych danych.
     */
    private fun cachedLoans(
        accountManager: AccountManager,
        targets: List<RenewTarget>
    ): List<Loan> {
        val ids = targets.map { it.loanId }.toSet()
        val accountId = targets.firstOrNull()?.accountId ?: return emptyList()
        return accountManager.getCachedLoans(accountId).filter { it.id in ids }
    }

    companion object {
        const val KEY_TARGETS = "targets"
        private const val MAX_ATTEMPTS = 3
    }
}
