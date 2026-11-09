package com.theundefined.omnis.guard

import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.Tenant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardLogicTest {
    private val today = LocalDate.of(2026, 10, 11)

    private fun loan(
        id: String,
        dueDate: String,
        renewable: Boolean = true,
        account: String = "a"
    ) =
        Loan(
            id = id,
            mmsid = "mms$id",
            title = "Title $id",
            author = null,
            dueDate = dueDate,
            dueHour = "2359",
            loanDate = "20260901",
            status = "Zwykłe",
            libraryName = "Lib",
            locationName = "Filia",
            subLocationName = null,
            barcode = "b$id",
            renewable = renewable,
            accountId = account
        )

    private fun account(
        id: String,
        password: String = "p",
        enabled: Boolean = true,
        demo: Boolean = false
    ) =
        Account(
            id = id,
            username = "u$id",
            password = password,
            tenant = Tenant(name = "Lib", baseUrl = "https://x", institution = "I", view = "I:V"),
            isEnabled = enabled,
            isDemo = demo
        )

    @Test
    fun tiersFollowThreshold() {
        assertEquals(DueTier.OVERDUE, dueTier(today.minusDays(1), today, 3))
        assertEquals(DueTier.LAST_DAY, dueTier(today, today, 3))
        assertEquals(DueTier.LAST_DAY, dueTier(today.plusDays(1), today, 3))
        assertEquals(DueTier.SOON, dueTier(today.plusDays(3), today, 3))
        assertNull(dueTier(today.plusDays(4), today, 3))
        // Próg 1: tylko ostatni dzień.
        assertNull(dueTier(today.plusDays(2), today, 1))
    }

    @Test
    fun noticesSkipAlreadySentAndUnparseableDates() {
        val soon = loan("1", "20261013")
        val far = loan("2", "20261101")
        val broken = loan("3", "jutro")
        val first = newDueNotices(listOf(soon, far, broken), today, 3, emptySet())
        assertEquals(listOf("1"), first.map { it.loan.id })

        val again = newDueNotices(listOf(soon), today, 3, first.map { it.key }.toSet())
        assertTrue(again.isEmpty())
    }

    @Test
    fun lastDayRemindsAgainAfterSoonNotice() {
        val soon = loan("1", "20261013")
        val sent = newDueNotices(listOf(soon), today, 3, emptySet()).map { it.key }.toSet()
        val dayBefore = newDueNotices(listOf(soon), today.plusDays(1), 3, sent)
        assertEquals(DueTier.LAST_DAY, dayBefore.single().tier)
    }

    @Test
    fun renewedLoanGetsNewKey() {
        val before = loan("1", "20261013")
        val sent = newDueNotices(listOf(before), today, 3, emptySet()).map { it.key }.toSet()
        // Po przedłużeniu termin wypada daleko — brak powiadomienia; gdy znów się zbliży, nowy
        // klucz.
        val renewed = before.copy(dueDate = "20261110")
        val later = newDueNotices(listOf(renewed), LocalDate.of(2026, 11, 8), 3, sent)
        assertEquals(1, later.size)
    }

    @Test
    fun overdueRemindsDailyButOncePerDay() {
        val overdue = loan("1", "20261001")
        val sent = newDueNotices(listOf(overdue), today, 3, emptySet()).map { it.key }.toSet()
        assertTrue(newDueNotices(listOf(overdue), today, 3, sent).isEmpty())
        assertEquals(1, newDueNotices(listOf(overdue), today.plusDays(1), 3, sent).size)
    }

    @Test
    fun renewCandidatesOnlyCloseRenewableAndUntried() {
        val close = loan("1", "20261013")
        val tooEarly = loan("2", "20261014")
        val notRenewable = loan("3", "20261012", renewable = false)
        val loans = listOf(close, tooEarly, notRenewable)
        assertEquals(listOf("1"), renewCandidates(loans, today, emptySet(), 2).map { it.id })
        assertTrue(renewCandidates(loans, today, setOf(GuardKeys.renew(close))).isEmpty())
    }

    @Test
    fun renewalCountsOnlyWhenDueDateMoved() {
        val before = loan("1", "20261013")
        assertEquals(
            "20261110",
            renewalSucceeded(before, listOf(before.copy(dueDate = "20261110")))?.dueDate
        )
        assertNull(renewalSucceeded(before, listOf(before)))
        assertNull(renewalSucceeded(before, emptyList()))
        // Skrócony termin to nie sukces.
        assertNull(renewalSucceeded(before, listOf(before.copy(dueDate = "20261012"))))
    }

    @Test
    fun finesFirstRunOnlyRecordsBaseline() {
        assertFalse(finesIncreased(null, 5.0))
        assertTrue(finesIncreased(0.0, 0.2))
        assertFalse(finesIncreased(1.0, 1.0))
        assertFalse(finesIncreased(1.0, 0.5))
    }

    @Test
    fun pruneDropsGoneItemsKeepsUncheckedAccounts() {
        val keys =
            setOf(
                "due|a|1|20261013|SOON",
                "due|a|2|20261013|SOON",
                "due|b|9|20261013|SOON",
                "overdue|a|1|2026-10-10",
                "overdue|a|1|$today"
            )
        val pruned = pruneSent(keys, mapOf("a" to setOf("1")), today)
        assertEquals(
            setOf("due|a|1|20261013|SOON", "due|b|9|20261013|SOON", "overdue|a|1|$today"),
            pruned
        )
    }

    @Test
    fun pauseLiftsWhenCredentialsChange() {
        val acc = account("a", password = "old")
        val state = GuardState(paused = mapOf("a" to credentialsFingerprint(acc)))
        assertTrue(isPaused(state, acc))
        assertFalse(isPaused(state, acc.copy(password = "new")))
        assertNotEquals(
            credentialsFingerprint(acc),
            credentialsFingerprint(acc.copy(password = "new"))
        )
    }

    @Test
    fun demoAndDisabledAccountsAreNotGuarded() {
        val accounts =
            listOf(account("a"), account("b", enabled = false), account("c", demo = true))
        assertEquals(listOf("a"), guardedAccounts(accounts).map { it.id })
    }

    @Test
    fun nextRunIsTodayOrTomorrowAtNine() {
        assertEquals(Duration.ofHours(1), delayUntilNextRun(LocalDateTime.of(2026, 10, 11, 8, 0)))
        assertEquals(Duration.ofHours(23), delayUntilNextRun(LocalDateTime.of(2026, 10, 11, 10, 0)))
    }

    @Test
    fun nextRunFollowsChosenHour() {
        val now = LocalDateTime.of(2026, 10, 11, 8, 0)
        assertEquals(Duration.ofHours(10), delayUntilNextRun(now, 18))
        assertEquals(Duration.ofHours(22), delayUntilNextRun(now, 6))
        // Dokładnie o pełnej godzinie — dzisiejszy termin już minął, następny jutro.
        assertEquals(
            Duration.ofHours(24),
            delayUntilNextRun(LocalDateTime.of(2026, 10, 11, 18, 0), 18)
        )
        // Uszkodzona godzina wraca do domyślnej 9:00.
        assertEquals(Duration.ofHours(1), delayUntilNextRun(now, 99))
    }

    @Test
    fun renewCandidatesRespectConfiguredDays() {
        val in3 = loan("1", "20261014")
        val in1 = loan("2", "20261012")
        val loans = listOf(in3, in1)
        assertEquals(listOf("2"), renewCandidates(loans, today, emptySet(), 1).map { it.id })
        assertEquals(listOf("2"), renewCandidates(loans, today, emptySet()).map { it.id })
        assertEquals(listOf("1", "2"), renewCandidates(loans, today, emptySet(), 3).map { it.id })
    }

    @Test
    fun oldSettingsJsonLoadsWithTodaysBehaviour() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val old =
            json.decodeFromString<GuardSettings>(
                """{"enabled":true,"thresholdDays":5,"watchlistNotify":false}"""
            )
        assertEquals(9, old.runHour)
        assertEquals(2, old.autoRenewDays)
        assertTrue(old.enabled)
        assertEquals(5, old.thresholdDays)
        val bare = json.decodeFromString<GuardSettings>("""{"enabled":true}""")
        assertEquals(DEFAULT_RUN_HOUR, bare.runHour)
        assertEquals(DEFAULT_AUTO_RENEW_DAYS, bare.autoRenewDays)
    }

    private fun notice(l: Loan, tier: DueTier = DueTier.SOON) =
        DueNotice(l, LocalDate.of(2026, 10, 12), tier, "k${l.id}")

    @Test
    fun actionTargetsAreRenewableVisibleAndUntried() {
        val a = loan("1", "20261012")
        val notRenewable = loan("2", "20261012", renewable = false)
        val tried = loan("3", "20261012")
        val b = loan("4", "20261012", account = "b")
        val noAccount = loan("5", "20261012").copy(accountId = null)
        val notices = listOf(a, notRenewable, tried, b, noAccount).map { notice(it) }
        val targets = renewActionTargets(notices, 0, 6, setOf(GuardKeys.renew(tried)))
        assertEquals(
            listOf(RenewTarget("a", "1", "20261012"), RenewTarget("b", "4", "20261012")),
            targets
        )
    }

    @Test
    fun actionTargetsExcludeLinesHiddenBehindMoreSummary() {
        val notices = (1..8).map { notice(loan("$it", "20261012")) }
        // 2 linie wyniku auto-przedłużania + limit 6 => widoczne tylko 4 pierwsze terminy.
        assertEquals(
            listOf("1", "2", "3", "4"),
            renewActionTargets(notices, 2, 6, emptySet()).map { it.loanId }
        )
        assertTrue(renewActionTargets(notices, 6, 6, emptySet()).isEmpty())
        assertTrue(renewActionTargets(notices, 9, 6, emptySet()).isEmpty())
    }

    @Test
    fun targetEncodingRoundTrips() {
        val t = RenewTarget("acc-1", "99", "20261012")
        assertEquals(t, RenewTarget.decode(t.encode()))
        assertNull(RenewTarget.decode("a\u001Fb"))
        assertNull(RenewTarget.decode("a\u001F\u001Fc"))
        assertNull(RenewTarget.decode("garbage"))
    }

    @Test
    fun planSkipsLoansRenewedMeanwhileOrReturned() {
        val fresh = loan("1", "20261012")
        val movedInApp = loan("2", "20261110")
        val blocked = loan("3", "20261012", renewable = false)
        val targets =
            listOf(
                RenewTarget("a", "1", "20261012"),
                RenewTarget("a", "2", "20261012"),
                RenewTarget("a", "3", "20261012"),
                RenewTarget("a", "4", "20261012")
            )
        val plan = planActionRenewals(targets, listOf(fresh, movedInApp, blocked))
        assertEquals(listOf("1"), plan.toRenew.map { it.id })
        assertEquals(listOf("2"), plan.alreadyDone.map { it.loanId })
        assertEquals(listOf("4"), plan.gone.map { it.loanId })
        assertEquals(listOf("3"), plan.blocked.map { it.id })
    }

    @Test
    fun planComparesDueDatesAcrossFormats() {
        val current = loan("1", "20261012")
        val plan = planActionRenewals(listOf(RenewTarget("a", "1", "2026-10-12")), listOf(current))
        assertEquals(listOf("1"), plan.toRenew.map { it.id })
    }

    @Test
    fun outcomeJudgesOnlyByMovedDueDate() {
        val ok = loan("1", "20261012")
        val refused = loan("2", "20261012")
        val blocked = loan("3", "20261012", renewable = false)
        val plan = ActionPlan(listOf(ok, refused), emptyList(), emptyList(), listOf(blocked))
        val after = listOf(ok.copy(dueDate = "20261110"), refused)
        val outcome = actionOutcome(plan, after)
        assertEquals(listOf("1"), outcome.renewed.map { it.first.id })
        assertEquals(LocalDate.of(2026, 11, 10), outcome.renewed.single().second)
        assertEquals(setOf("2", "3"), outcome.failed.map { it.id }.toSet())
        assertTrue(outcome.unknown.isEmpty())
    }

    @Test
    fun outcomeWithoutRefetchIsUnknownNotFailure() {
        val a = loan("1", "20261012")
        val outcome =
            actionOutcome(ActionPlan(listOf(a), emptyList(), emptyList(), emptyList()), null)
        assertTrue(outcome.renewed.isEmpty())
        assertTrue(outcome.failed.isEmpty())
        assertEquals(listOf("1"), outcome.unknown.map { it.id })
    }

    @Test
    fun nothingToDoWhenEverythingAlreadyRenewed() {
        val moved = loan("1", "20261110")
        val plan = planActionRenewals(listOf(RenewTarget("a", "1", "20261012")), listOf(moved))
        val outcome = actionOutcome(plan, listOf(moved))
        assertTrue(plan.toRenew.isEmpty())
        assertTrue(
            outcome.renewed.isEmpty() && outcome.failed.isEmpty() && outcome.unknown.isEmpty()
        )
    }
}
