package com.theundefined.omnis

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Podstawowe ścieżki z już włączonym kontem demo (omnis-mock): lista wypożyczeń, grupowanie,
 * wyszukiwarka, rezerwacje i historia. Testy tylko czytają — nic nie przedłużają ani nie rezerwują,
 * bo stan konta demo jest współdzielony przez wszystkich jego użytkowników.
 */
@RunWith(AndroidJUnit4::class)
class DemoAccountFlowsTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(AppStateRule(seedDemoAccount = true))
            .around(composeRule)
            .around(ScreenshotRule(composeRule))

    @Test
    fun mainScreen_showsAllDemoLoans() {
        composeRule.waitForText(DEMO_LOAN_TITLES.first())
        composeRule.screenshot("loans_top")
        DEMO_LOAN_TITLES.forEach { composeRule.waitAndScrollToText(it) }
    }

    @Test
    fun manualRefresh_keepsDemoLoans() {
        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first())

        composeRule.onNodeWithContentDescription(str(R.string.cd_refresh)).performClick()

        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first())
    }

    @Test
    fun changeGroupingToNone_showsSingleAllGroup() {
        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first())

        composeRule.onNodeWithText(str(R.string.group_by_account), substring = true).performClick()
        composeRule.screenshot("grouping_menu")
        composeRule.onNodeWithText(str(R.string.group_none)).performClick()

        // Lista zachowuje przewinięcie po przegrupowaniu, więc nagłówek może być poza ekranem.
        // Dokładne dopasowanie — "wszystkie" występuje też w serii "Dzieła wszystkie".
        composeRule.waitAndScrollToText(
            str(R.string.group_all_header),
            substring = false,
            timeoutMs = 5_000
        )
        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first(), timeoutMs = 5_000)
    }

    @Test
    fun searchCatalog_findsDemoTitle() {
        composeRule.onNodeWithContentDescription(str(R.string.cd_search)).performClick()
        composeRule.waitForText(str(R.string.search_title), substring = false, timeoutMs = 5_000)
        composeRule.screenshot("search_empty")

        composeRule.onNode(hasSetTextAction()).performTextInput("Nibylandii")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        composeRule.waitForText("Cienie Nibylandii")
    }

    @Test
    fun holdsScreen_loadsDemoHolds() {
        openFromMoreMenu(R.string.holds_title)

        // Rezerwacje konta demo są współdzielone (każdy może anulować seedowaną), więc
        // sprawdzamy tylko, że lista się wczytała — z rezerwacjami albo bez.
        val statusPrefix = str(R.string.hold_status_label, "").trim()
        composeRule.waitForNode(
            hasText(statusPrefix, substring = true) or
                hasText(str(R.string.no_holds), substring = false)
        )
    }

    @Test
    fun historyScreen_showsDemoHistory() {
        openFromMoreMenu(R.string.history_title)

        // Pierwsze zakończone wypożyczenie z `_HISTORY_TEMPLATES` w omnis-mock.
        composeRule.waitAndScrollToText("Ostatni rejs wyobraźni")
    }

    @Test
    fun statsScreen_showsDemoStats() {
        openFromMoreMenu(R.string.stats_title)

        val year = java.time.LocalDate.now().year
        composeRule.waitForText(str(R.string.stats_chart_months, year), substring = false)
        composeRule.screenshot("stats_year")

        composeRule.onNodeWithText(str(R.string.stats_all_years), substring = false).performClick()
        composeRule.waitForText(str(R.string.stats_chart_years), substring = false)
        composeRule.screenshot("stats_all_years")
        // Autor z `_HISTORY_TEMPLATES` w omnis-mock, a karta czasu wypożyczenia pojawia się tylko,
        // jeśli daty wypożyczenia i zwrotu z historii dały się sparsować.
        composeRule.waitAndScrollToText("Karolina Nibylska")
        composeRule.screenshot("stats_rankings")
        composeRule.waitAndScrollToText(str(R.string.stats_duration), substring = false)
    }

    @Test
    fun backFromSecondaryScreen_returnsToLoans() {
        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first())
        openFromMoreMenu(R.string.cd_settings)
        composeRule.waitForText(
            str(R.string.demo_mode_disable),
            substring = false,
            timeoutMs = 5_000
        )
        composeRule.screenshot("settings")

        composeRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        composeRule.waitAndScrollToText(DEMO_LOAN_TITLES.first(), timeoutMs = 5_000)
    }

    private fun openFromMoreMenu(itemRes: Int) {
        composeRule.onNodeWithContentDescription(str(R.string.cd_more_options)).performClick()
        composeRule.onNodeWithText(str(itemRes), substring = false).performClick()
    }

    companion object {
        @BeforeClass @JvmStatic fun wakeUpServer() = wakeUpDemoServer()
    }
}
