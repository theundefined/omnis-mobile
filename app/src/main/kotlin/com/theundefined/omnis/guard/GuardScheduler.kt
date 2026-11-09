package com.theundefined.omnis.guard

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Planowanie strażnika: raz na dobę, pierwszy przebieg o wybranej w Ustawieniach godzinie
 * ([GuardSettings.runHour], domyślnie 9:00). WorkManager nie gwarantuje dokładnej godziny (Doze),
 * ale terminy liczymy w dniach, więc to wystarcza — bez exact alarms i dodatkowych uprawnień.
 */
object GuardScheduler {
    private const val PERIODIC = "due_date_guard"
    const val CHECK_NOW = "due_date_guard_now"
    private const val RENEW_ACTION = "guard_renew_action"

    private val constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Przy starcie aplikacji: KEEP, żeby nie przesuwać zaplanowanego przebiegu przy każdym
     * otwarciu. Po włączeniu w Ustawieniach — `replace`, żeby od razu liczyć od najbliższej
     * wybranej godziny. Zmianę godziny obsługuje [updateRunHour] (bez zabijania trwającego
     * przebiegu).
     */
    fun schedule(context: Context, runHour: Int, replace: Boolean = false) {
        val request =
            PeriodicWorkRequestBuilder<DueDateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(
                    delayUntilNextRun(LocalDateTime.now(), runHour).toMinutes(),
                    TimeUnit.MINUTES
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    /**
     * Zmiana godziny: UPDATE nie przerywa trwającego przebiegu (CANCEL_AND_REENQUEUE by go zabiło),
     * ale sam UPDATE zostawia stary harmonogram — dlatego jawnie ustawiamy czas najbliższego
     * przebiegu ([nextRunMillis]).
     */
    fun updateRunHour(context: Context, runHour: Int) {
        val request =
            PeriodicWorkRequestBuilder<DueDateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setNextScheduleTimeOverride(nextRunMillis(ZonedDateTime.now(), runHour))
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /**
     * "Sprawdź teraz" w Ustawieniach. Zwraca id zlecenia — przy KEEP, gdy poprzednie jeszcze czeka
     * (np. na sieć), nowe przepada i jego id nigdy się nie pojawi; wołający ma na to limit czasu.
     */
    fun checkNow(context: Context): UUID {
        val request = OneTimeWorkRequestBuilder<DueDateWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CHECK_NOW, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /**
     * Akcja „Przedłuż" z powiadomienia: jednorazowa praca (wymaga sieci), unikalna z
     * APPEND_OR_REPLACE — kolejne dotknięcia ustawiają się w kolejkę. Plan ([planActionRenewals])
     * pomija pozycje przedłużone w międzyczasie i te, których próbę już zapisano, więc powtórka nie
     * przedłuży ani nie ponowi odmowy.
     */
    fun renewFromNotification(context: Context, targets: List<RenewTarget>) {
        val request =
            OneTimeWorkRequestBuilder<RenewActionWorker>()
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putStringArray(
                            RenewActionWorker.KEY_TARGETS,
                            targets.map { it.encode() }.toTypedArray()
                        )
                        .build()
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RENEW_ACTION, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
