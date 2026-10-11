package com.theundefined.omnis.guard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.theundefined.omnis.MainActivity
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.ui.holdDeadline
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Powiadomienia strażnika: jedno zbiorcze na rodzaj (terminy / rezerwacje / kary / logowanie), nie
 * jedno na książkę — przy kilku kontach inaczej robi się spam. Osobne kanały, żeby dało się
 * wyciszyć np. same rezerwacje w ustawieniach systemu.
 */
class GuardNotifier(private val context: Context) {

    data class Renewed(val loan: Loan, val newDue: LocalDate)

    fun canNotify(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_DUE,
                    context.getString(R.string.guard_channel_due),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_HOLDS,
                    context.getString(R.string.guard_channel_holds),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_ACCOUNT,
                    context.getString(R.string.guard_channel_account),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        )
    }

    /** Terminy zwrotu i wynik auto-przedłużania. false = nic nie wysłano. */
    fun notifyLoans(
        notices: List<DueNotice>,
        renewed: List<Renewed>,
        renewFailed: List<Loan>,
        showOwner: Boolean,
        today: LocalDate
    ): Boolean {
        if (notices.isEmpty() && renewed.isEmpty() && renewFailed.isEmpty()) return false
        fun owner(loan: Loan) = if (showOwner) loan.ownerName?.let { " ($it)" } ?: "" else ""

        val lines =
            renewed.map {
                context.getString(
                    R.string.guard_line_renewed,
                    it.loan.title,
                    it.newDue.format(DATE),
                ) + owner(it.loan)
            } +
                renewFailed.map {
                    context.getString(R.string.guard_line_renew_failed, it.title) + owner(it)
                } +
                notices.map { notice ->
                    val days = ChronoUnit.DAYS.between(today, notice.due).toInt()
                    val text =
                        when {
                            notice.tier == DueTier.OVERDUE ->
                                context.getString(R.string.guard_line_overdue, notice.loan.title)
                            days == 0 ->
                                context.getString(R.string.guard_line_due_today, notice.loan.title)
                            days == 1 ->
                                context.getString(
                                    R.string.guard_line_due_tomorrow,
                                    notice.loan.title
                                )
                            else ->
                                context.resources.getQuantityString(
                                    R.plurals.guard_line_due_in,
                                    days,
                                    days,
                                    notice.loan.title
                                )
                        }
                    text + owner(notice.loan)
                }

        val title =
            when {
                notices.any { it.tier == DueTier.OVERDUE } ->
                    context.getString(R.string.guard_title_overdue)
                notices.isNotEmpty() || renewFailed.isNotEmpty() ->
                    context.getString(R.string.guard_title_due)
                else -> context.getString(R.string.guard_title_renewed)
            }
        return post(ID_LOANS, CHANNEL_DUE, title, lines, MainActivity.ACTION_OPEN_LOANS)
    }

    fun notifyHolds(holds: List<Hold>, showOwner: Boolean): Boolean {
        if (holds.isEmpty()) return false
        val lines =
            holds.map { hold ->
                val deadline = holdDeadline(hold)
                val base =
                    if (deadline != null)
                        context.getString(
                            R.string.guard_line_hold_until,
                            hold.title,
                            deadline.format(DATE)
                        )
                    else hold.title
                val place = hold.pickupLocation?.let { " — $it" } ?: ""
                base + place + if (showOwner) " (${hold.ownerName})" else ""
            }
        val title =
            context.resources.getQuantityString(R.plurals.holds_ready, holds.size, holds.size)
        return post(ID_HOLDS, CHANNEL_HOLDS, title, lines, MainActivity.ACTION_OPEN_HOLDS)
    }

    fun notifyFines(increased: List<Pair<Account, Double>>): Boolean {
        if (increased.isEmpty()) return false
        val lines =
            increased.map { (account, amount) ->
                context.getString(
                    R.string.guard_line_fines,
                    account.displayName ?: account.username,
                    amount,
                    account.finesCurrency
                )
            }
        return post(
            ID_FINES,
            CHANNEL_ACCOUNT,
            context.getString(R.string.guard_title_fines),
            lines,
            MainActivity.ACTION_OPEN_LOANS
        )
    }

    fun notifyLoginProblems(accounts: List<Account>): Boolean {
        if (accounts.isEmpty()) return false
        val lines =
            accounts.map {
                context.getString(
                    R.string.guard_line_login,
                    it.displayName ?: it.username,
                    it.tenant.name
                )
            }
        return post(
            ID_LOGIN,
            CHANNEL_ACCOUNT,
            context.getString(R.string.guard_title_login),
            lines,
            MainActivity.ACTION_OPEN_SETTINGS
        )
    }

    private fun post(
        id: Int,
        channel: String,
        title: String,
        lines: List<String>,
        action: String
    ): Boolean {
        if (!canNotify()) return false
        val intent =
            Intent(context, MainActivity::class.java).apply {
                this.action = action
                // SINGLE_TOP: otwarta aplikacja dostaje onNewIntent zamiast odtworzenia Activity.
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pending =
            PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val style = NotificationCompat.InboxStyle()
        lines.take(MAX_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_LINES) {
            style.setSummaryText(
                context.getString(R.string.guard_more_lines, lines.size - MAX_LINES)
            )
        }
        val notification =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(lines.first())
                .setStyle(style)
                .setNumber(lines.size)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            // Uprawnienie cofnięte między canNotify() a notify().
            false
        }
    }

    companion object {
        const val CHANNEL_DUE = "guard_due"
        const val CHANNEL_HOLDS = "guard_holds"
        const val CHANNEL_ACCOUNT = "guard_account"
        private const val ID_LOANS = 1001
        private const val ID_HOLDS = 1002
        private const val ID_FINES = 1003
        private const val ID_LOGIN = 1004
        private const val MAX_LINES = 6
        private val DATE = DateTimeFormatter.ofPattern("dd.MM")
    }
}
