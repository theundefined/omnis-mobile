package com.theundefined.omnis.guard

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Planowanie strażnika: raz na dobę, pierwszy przebieg o [GUARD_RUN_AT]. WorkManager nie gwarantuje
 * dokładnej godziny (Doze), ale terminy liczymy w dniach, więc to wystarcza — bez exact alarms i
 * dodatkowych uprawnień.
 */
object GuardScheduler {
    private const val PERIODIC = "due_date_guard"
    const val CHECK_NOW = "due_date_guard_now"

    private val constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Przy starcie aplikacji: KEEP, żeby nie przesuwać zaplanowanego przebiegu przy każdym
     * otwarciu. Po włączeniu w Ustawieniach — `replace`, żeby od razu liczyć od najbliższej 9:00.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val request =
            PeriodicWorkRequestBuilder<DueDateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(
                    delayUntilNextRun(LocalDateTime.now()).toMinutes(),
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
}
