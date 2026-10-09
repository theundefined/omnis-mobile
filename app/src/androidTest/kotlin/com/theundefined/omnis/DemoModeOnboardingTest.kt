package com.theundefined.omnis

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Ścieżka nowego użytkownika: pusta aplikacja → ustawienia → "Włącz tryb demo" → wypożyczenia konta
 * demo z omnis-mock na ekranie głównym, i z powrotem "Wyłącz tryb demo".
 */
@RunWith(AndroidJUnit4::class)
class DemoModeOnboardingTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(AppStateRule(seedDemoAccount = false))
            .around(composeRule)
            .around(ScreenshotRule(composeRule))

    @Test
    fun enableDemoModeFromEmptyApp_showsDemoLoans() {
        composeRule.screenshot("empty_app")
        composeRule.onNodeWithText(str(R.string.add_first_account)).performClick()
        composeRule.screenshot("settings_demo_off")
        composeRule.onNodeWithText(str(R.string.demo_mode_enable)).performClick()
        composeRule.waitForText(
            str(R.string.demo_mode_disable),
            substring = false,
            timeoutMs = 5_000
        )
        composeRule.screenshot("settings_demo_on")

        composeRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        DEMO_LOAN_TITLES.forEach { composeRule.waitAndScrollToText(it) }
    }

    @Test
    fun disableDemoMode_hidesDemoLoans() {
        composeRule.onNodeWithText(str(R.string.add_first_account)).performClick()
        composeRule.onNodeWithText(str(R.string.demo_mode_enable)).performClick()
        composeRule.onNodeWithText(str(R.string.demo_mode_disable)).performClick()
        composeRule.waitForText(
            str(R.string.demo_mode_enable),
            substring = false,
            timeoutMs = 5_000
        )

        composeRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        // Konto demo zostaje na liście (wyłączone), więc zamiast "Dodaj pierwsze konto" jest
        // zwykły ekran główny — z paskiem grupowania, ale bez wypożyczeń.
        composeRule.waitForText(str(R.string.group_by_account), timeoutMs = 5_000)
        composeRule.onNodeWithText(str(R.string.add_first_account)).assertDoesNotExist()
        DEMO_LOAN_TITLES.forEach { title ->
            composeRule.onNodeWithText(title, substring = true).assertDoesNotExist()
        }
    }

    companion object {
        @BeforeClass @JvmStatic fun wakeUpServer() = wakeUpDemoServer()
    }
}

/** Tytuły (po `displayTitle`) 4 wypożyczeń konta demo — fixture `_LOAN_TEMPLATES` w omnis-mock. */
val DEMO_LOAN_TITLES = listOf("Lalka", "Pan Tadeusz", "Quo vadis", "Dziady")
